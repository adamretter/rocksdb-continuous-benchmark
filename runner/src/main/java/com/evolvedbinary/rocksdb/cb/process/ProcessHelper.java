package com.evolvedbinary.rocksdb.cb.process;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProcessHelper {

    public static final int INTERRUPTED_EXIT_CODE = -1;
    public static final int NORMAL_EXIT_CODE = 0;

    private static final DateTimeFormatter BASIC_ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static ProcessInfo start(final Path workingDirectory, final Map<String, String> environmentVariables,
            final String command, final List<String> arguments, final Path logDir) throws IOException {

        final LocalDateTime localDateTime = LocalDateTime.now();
        final String dateTimeStamp = localDateTime.format(BASIC_ISO_DATE_TIME);

        final Path stdOutputLogFile = logDir.resolve("stdout." + dateTimeStamp + ".log");
        final Path stdErrorLogFile = logDir.resolve("stderr." + dateTimeStamp + ".log");

        final ProcessBuilder processBuilder = new ProcessBuilder()
                .directory(workingDirectory.toFile());

        environmentVariables.forEach((name, value) -> processBuilder.environment().put(name, value));

        final List<String> commandAndArgs = new ArrayList<>();
        commandAndArgs.add(command);
        commandAndArgs.addAll(arguments);
        processBuilder.command(commandAndArgs);

        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(stdOutputLogFile.toFile()));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(stdErrorLogFile.toFile()));

        final Process process = processBuilder.start();
        return new ProcessInfo(stdOutputLogFile, stdErrorLogFile, process);
    }

    public static int waitFor(final ProcessInfo processInfo) {
        int exitCode = NORMAL_EXIT_CODE;
        try {
            exitCode = processInfo.process.waitFor();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();  // reset the interrupted thread
            exitCode = INTERRUPTED_EXIT_CODE;
        }

        return exitCode;
    }

    public static class ProcessInfo {
        public final Path stdOutputLogFile;
        public final Path stdErrorLogFile;
        public final Process process;

        public ProcessInfo(final Path stdOutputLogFile, final Path stdErrorLogFile, final Process process) {
            this.stdOutputLogFile = stdOutputLogFile;
            this.stdErrorLogFile = stdErrorLogFile;
            this.process = process;
        }
    }
}