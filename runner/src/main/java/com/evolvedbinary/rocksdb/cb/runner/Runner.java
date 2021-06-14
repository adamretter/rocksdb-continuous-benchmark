package com.evolvedbinary.rocksdb.cb.runner;

import com.evolvedbinary.rocksdb.cb.dataobject.BuildRequest;
import com.evolvedbinary.rocksdb.cb.dataobject.BuildResponse;
import com.evolvedbinary.rocksdb.cb.dataobject.BuildState;
import com.evolvedbinary.rocksdb.cb.dataobject.DataObject;
import com.evolvedbinary.rocksdb.cb.scm.GitHelper;
import com.evolvedbinary.rocksdb.cb.scm.GitHelperException;
import com.evolvedbinary.rocksdb.cb.scm.GitHelperJGitImpl;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.apache.activemq.artemis.api.jms.JMSFactoryType;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.Closeable;
import java.io.IOException;
import java.lang.IllegalStateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.evolvedbinary.rocksdb.cb.common.CloseUtil.closeAndLogIfException;

class Runner {

    private enum State {
        IDLE,
        RUNNING,
        AWAITING_SHUTDOWN,
        SHUTTING_DOWN
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Runner.class);
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.IDLE);
    private static final String MAIN_GIT_BRANCH = "master";
    private static final String REPO_DIR_NAME = "repo";
    private static final String LOG_DIR_NAME = "log";
    private static final List<String> DEFAULT_MAKE_TARGETS = Arrays.asList("db_bench");

    private final Settings settings;

    private Connection connection;
    private Session session;
    private Queue buildRequestQueue;
    private Queue buildResponseQueue;
    private MessageConsumer buildRequestQueueConsumer;
    private MessageProducer buildResponseQueueProducer;

    public Runner(final Settings settings) {
        this.settings = settings;
    }

    public void runSync() throws InterruptedException {
        final Instance instance = runAsync();
        instance.awaitShutdown();
    }

    public Instance runAsync() {
        if (!STATE.compareAndSet(State.IDLE, State.RUNNING)) {
            throw new IllegalStateException("Already running");
        }

        // setup JMS
        final TransportConfiguration transportConfiguration = new TransportConfiguration(NettyConnectorFactory.class.getName());
        final ConnectionFactory connectionFactory = ActiveMQJMSClient.createConnectionFactoryWithoutHA(JMSFactoryType.CF, transportConfiguration);

        try {
            this.connection = connectionFactory.createConnection();
            this.connection.setClientID("runner-" + UUID.randomUUID());

            this.session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

            this.buildRequestQueue = session.createQueue(settings.buildRequestQueueName);
            this.buildResponseQueue = session.createQueue(settings.buildResponseQueueName);

            this.buildRequestQueueConsumer = session.createConsumer(buildRequestQueue);
            this.buildRequestQueueConsumer.setMessageListener(new BuildRequestQueueMessageListener());
            LOGGER.info("Listening to Queue: {}", settings.buildRequestQueueName);

            this.buildResponseQueueProducer = session.createProducer(buildResponseQueue);

            // start the connection
            this.connection.start();

        } catch (final JMSException e) {
            if (this.connection != null) {
                closeAndLogIfException(connection::stop, LOGGER);
            }

            closeAndLogIfException(this.buildResponseQueueProducer, LOGGER);
            closeAndLogIfException(this.buildRequestQueueConsumer, LOGGER);
            closeAndLogIfException(this.session, LOGGER);
            closeAndLogIfException(this.connection, LOGGER);

            throw new RuntimeException("Unable to setup JMS broker connection: " + e.getMessage(), e);
        }

        final ExecutorService executorService = Executors.newFixedThreadPool(1, r -> new Thread(r, "Runner-Thread"));
        final Future<?> runnerFuture = executorService.submit(new RunnerCallable());

        return new Instance(executorService, runnerFuture);
    }

    private class BuildRequestQueueMessageListener implements MessageListener {
        @Override
        public void onMessage(final Message message) {
            if (!(message instanceof TextMessage)) {
                // acknowledge invalid message so that it is removed from the queue
                if (acknowledgeMessage(message)) {
                    LOGGER.error("Discarded message with unexpected type {} from Queue: {}.", message.getClass().getName(), settings.buildRequestQueueName);
                }

                // can't process non-text message, so DONE
                return;
            }

            final TextMessage textMessage = (TextMessage) message;
            final String content;
            try {
                content = textMessage.getText();
            } catch (final JMSException e) {
                LOGGER.error("Could not get content of TextMessage from Queue: {}. Error: {}", settings.buildRequestQueueName, e.getMessage(), e);

                // can't access message content, so DONE
                return;
            }

            // attempt to parse as BuildRequest
            final BuildRequest buildRequest;
            try {
                buildRequest = new BuildRequest().deserialize(content);
            } catch (final IOException e) {
                // unable to deserialize, acknowledge invalid message so that it is removed from the queue
                if (acknowledgeMessage(message)) {
                    LOGGER.error("Discarded message with unexpected format from Queue: {}. Error: {}. Content: '{}'", settings.buildRequestQueueName, e.getMessage(), content);
                }
                return;
            }

            // TODO if we are already building, should we start another build (i.e. ack/no-ack)... or isn't this already controlled in the orchestrator already?

            if (!acknowledgeMessage(message)) {
                LOGGER.error("Unable to acknowledge message from Queue: {}. Content: '{}'. Skipping...", settings.buildRequestQueueName, content);
                return;
            }

            // TODO(AR) handle the build request

            // 1) do some sanity checks?
            // TODO(AR)

            // 2) Send BUILDING
            // TODO(AR)

            final Path repoDir = settings.dataDir.resolve(REPO_DIR_NAME);
            final Path projectRepoDir = repoDir.resolve(buildRequest.getRepository());

            // 3) Checkout repo with JGit or Git?
            GitHelper gitHelper = null;
            try {
                if (!Files.exists(projectRepoDir)) {
                    // clone the remote repo
                    final String repoUri = "https://github.com/" + buildRequest.getRepository();
                    gitHelper = GitHelperJGitImpl.clone(repoUri, projectRepoDir, MAIN_GIT_BRANCH);

                } else {
                    // fetch the latest from the remote repo
                    gitHelper = GitHelperJGitImpl.open(projectRepoDir).fetch();
                }

                // discard any unstaged changes (perhaps accumulated in a previous run), i.e. `git clean -fdx`
                gitHelper = gitHelper.cleanAll();

                // checkout the branch
                gitHelper = gitHelper.checkout(buildRequest.getCommit());

            } catch (final GitHelperException e) {
                LOGGER.error("Unable to open/update Git repo: {}. Error: {}",buildRequest.getRepository(), e.getMessage(), e);

                // send build failure
                final BuildResponse failedResponse = new BuildResponse(BuildState.FAILED, buildRequest, null, Arrays.asList(e.getMessage()));
                try {
                    sendBuildResponseOutput(failedResponse);
                } catch (final IOException | JMSException ee) {
                    LOGGER.error("Unable to send build failure message. Error: {}", e.getMessage(), ee);
                }

            } finally {
                if (gitHelper != null) {
                    gitHelper.close();
                }
            }

            // 4) build the repo
            final Path logDir = settings.dataDir.resolve(LOG_DIR_NAME);
            final Path projectLogDir = logDir.resolve(buildRequest.getRepository());

            final Builder builder = new Builder();
            final Builder.BuildResult buildResult;
            try {
                buildResult = builder.build(projectRepoDir, projectLogDir, DEFAULT_MAKE_TARGETS);
            } catch (final IOException e) {
                // TODO(AR) handle this
            }


            // 5) run the benchmarks

            // 6) send the results via BUILT

            // *) TODO(AR) need a BuildState.ERROR - or multiple types of error? to be able to send as a BuildResponse, e.g. CLONE_ERROR, COMPILATION_ERROR, BENCHMARK_ERROR
        }
    }

    private void sendBuildResponseOutput(final BuildResponse buildResponse) throws IOException, JMSException {
        // send the message
        sendMessage(buildResponse, buildResponseQueue);
    }

    private void sendMessage(final DataObject message, final Queue queue) throws IOException, JMSException {
        // send the message
        final String content = message.serialize();
        final TextMessage textMessage = session.createTextMessage(content);
        buildResponseQueueProducer.send(queue, textMessage);
        LOGGER.info("Sent {} to Queue: {}", message.getClass().getName(), queue.getQueueName());
    }

    private static boolean acknowledgeMessage(final Message message) {
        try {
            message.acknowledge();
            return true;
        } catch (final JMSException e) {
            LOGGER.error("Unable to acknowledge message: {}", e.getMessage(), e);
            return false;
        }
    }

    private class RunnerCallable implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            try {
                // loop and sleep... until InterruptedException
                while (true) {
                    Thread.sleep(5000);
                }

            } catch (final Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();  // restore interrupt flag
                }

                // attempt JMS shutdown
                if (connection != null) {
                    closeAndLogIfException(connection::stop, LOGGER);
                }
                closeAndLogIfException(buildResponseQueueProducer, LOGGER);
                closeAndLogIfException(buildRequestQueueConsumer, LOGGER);
                closeAndLogIfException(session, LOGGER);
                closeAndLogIfException(connection, LOGGER);

                throw e;
            }
        }
    }

    static class Instance implements Closeable {
        private final ExecutorService executorService;
        private final Future<?> runnerFuture;

        private Instance(final ExecutorService executorService, final Future<?> runnerFuture) {
            this.executorService = executorService;
            this.runnerFuture = runnerFuture;
        }

        /**
         * Wait until the orchestrate future completes.
         */
        public void awaitShutdown() throws InterruptedException {
            if (!STATE.compareAndSet(State.RUNNING, State.AWAITING_SHUTDOWN)) {
                throw new IllegalStateException("Not running");
            }

            try {
                runnerFuture.get();

                if (!executorService.isShutdown()) {
                    executorService.shutdownNow();
                }

            } catch (final ExecutionException e) {
                LOGGER.error("Orchestrator raised an exception: " + e.getMessage(), e);
            } finally {
                STATE.set(State.IDLE);
            }
        }

        @Override
        public void close() {
            if (!STATE.compareAndSet(State.RUNNING, State.SHUTTING_DOWN)) {
                throw new IllegalStateException("Not running");
            }

            try {
                runnerFuture.cancel(true);

                if (!executorService.isShutdown()) {
                    executorService.shutdownNow();
                }

            } finally {
                STATE.set(State.IDLE);
            }
        }
    }

    static class Settings {
        final String buildRequestQueueName;
        final String buildResponseQueueName;
        final Path dataDir;
        final boolean keepLogs;

        public Settings(final String buildRequestQueueName, final String buildResponseQueueName, final Path dataDir, final boolean keepLogs) {
            this.buildRequestQueueName = buildRequestQueueName;
            this.buildResponseQueueName = buildResponseQueueName;
            this.dataDir = dataDir;
            this.keepLogs = keepLogs;
        }
    }
}
