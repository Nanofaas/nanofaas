package it.unimib.datai.nanofaas.cli.commands.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.time.Duration;

@Command(name = "get", description = "Get execution status/result.")
public class ExecGetCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    ExecCommand parent;

    @Parameters(index = "0", description = "Execution ID")
    String executionId;

    @Option(names = {"--watch"}, description = "Poll until execution reaches a terminal state.")
    boolean watch;

    @Option(names = {"--interval"}, description = "Polling interval (e.g., PT1S). Default: PT1S")
    String interval = "PT1S";

    @Option(names = {"--timeout"}, description = "Max time to watch (e.g., PT30S). Default: PT5M")
    String timeout = "PT5M";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public void run() {
        RootCommand root = parent.root;
        if (!watch) {
            print(root.controlPlaneClient().getExecution(executionId));
            return;
        }

        Duration pollEvery = Duration.parse(interval);
        Duration max = Duration.parse(timeout);
        long deadline = System.nanoTime() + max.toNanos();

        while (true) {
            ExecutionStatus st = root.controlPlaneClient().getExecution(executionId);
            print(st);
            if (isTerminal(st.status())) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timeout waiting for execution: " + executionId);
            }
            try {
                Thread.sleep(pollEvery.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while watching execution", e);
            }
        }
    }

    private void print(ExecutionStatus st) {
        try {
            System.out.println(json.writeValueAsString(st));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }

    private static boolean isTerminal(String status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case "success", "error", "timeout" -> true;
            default -> false;
        };
    }
}
