package it.unimib.datai.nanofaas.cli.commands.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.io.JsonInput;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "invoke", mixinStandardHelpOptions = true,
        description = "Invoke a function synchronously.")
public class InvokeCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    RootCommand root;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Option(names = {"-d", "--data"}, required = true, description = "Request body. Use @file or @- for stdin.")
    String data;

    @Option(names = {"--timeout-ms"}, description = "X-Timeout-Ms header")
    Integer timeoutMs;

    @Option(names = {"--idempotency-key"}, description = "Idempotency-Key header")
    String idempotencyKey;

    @Option(names = {"--trace-id"}, description = "X-Trace-Id header")
    String traceId;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public void run() {
        JsonNode input = JsonInput.read(data);
        InvocationRequest req = new InvocationRequest(input, null);
        InvocationResponse resp = root.controlPlaneClient().invokeSync(name, req, idempotencyKey, traceId, timeoutMs);
        try {
            System.out.println(json.writeValueAsString(resp));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write response JSON", e);
        }
    }
}
