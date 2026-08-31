package it.unimib.datai.nanofaas.cli.commands.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.cli.http.InvocationCallResult;
import it.unimib.datai.nanofaas.cli.io.JsonInput;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

@SuppressWarnings("java:S106") // CLI product output must go to stdout for pipes/scripts; a logger is wrong here.
@Command(name = "invoke", mixinStandardHelpOptions = true,
        description = "Invoke a function synchronously.")
public class InvokeCommand implements Callable<Integer> {

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
    public Integer call() {
        JsonNode input = JsonInput.read(data);
        InvocationRequest req = new InvocationRequest(input, null);
        InvocationCallResult result = root.controlPlaneClient().invokeSync(name, req, idempotencyKey, traceId, timeoutMs);
        try {
            if (result.httpStatus() == 204) {
                System.out.println(json.writeValueAsString(Map.of("statusCode", 204)));
            } else {
                System.out.println(json.writeValueAsString(result.response()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write response JSON", e);
        }
        return result.isSuccessful() ? 0 : 1;
    }
}
