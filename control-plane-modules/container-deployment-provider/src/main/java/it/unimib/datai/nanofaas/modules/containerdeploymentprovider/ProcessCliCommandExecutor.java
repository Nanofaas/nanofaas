package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ProcessCliCommandExecutor implements CliCommandExecutor {

    @Override
    public ExecutionResult run(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return new ExecutionResult(exitCode, output);
        } catch (IOException e) {
            return ExecutionResult.failure(1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure(1, "Interrupted while running command");
        }
    }
}
