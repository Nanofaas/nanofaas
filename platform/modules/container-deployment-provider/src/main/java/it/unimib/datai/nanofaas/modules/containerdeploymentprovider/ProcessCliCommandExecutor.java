package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ProcessCliCommandExecutor implements CliCommandExecutor {

    private final Duration timeout;

    ProcessCliCommandExecutor() {
        this(Duration.ofMinutes(2));
    }

    ProcessCliCommandExecutor(Duration timeout) {
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofMinutes(2)
                : timeout;
    }

    @Override
    public ExecutionResult run(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                process.waitFor(5, TimeUnit.SECONDS);
                String output = readOutput(process);
                return ExecutionResult.failure(
                        124,
                        ("Timed out after " + timeout.toMillis() + "ms"
                                + (output.isBlank() ? "" : ": " + output)).trim()
                );
            }
            String output = readOutput(process);
            int exitCode = process.exitValue();
            return new ExecutionResult(exitCode, output);
        } catch (IOException e) {
            return ExecutionResult.failure(1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure(1, "Interrupted while running command");
        }
    }

    private static String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }
}
