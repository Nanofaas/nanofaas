package it.unimib.datai.nanofaas.cli.commands.invoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Command(name = "enqueue", description = "Invoke a function asynchronously.")
public class EnqueueCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    RootCommand root;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Option(names = {"-d", "--data"}, required = true, description = "Request body. Use @file or @- for stdin.")
    String data;

    @Option(names = {"--idempotency-key"}, description = "Idempotency-Key header")
    String idempotencyKey;

    @Option(names = {"--trace-id"}, description = "X-Trace-Id header")
    String traceId;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public void run() {
        JsonNode input = readJsonInput();
        InvocationRequest req = new InvocationRequest(input, null);
        InvocationResponse resp = root.controlPlaneClient().enqueue(name, req, idempotencyKey, traceId);
        try {
            System.out.println(json.writeValueAsString(resp));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write response JSON", e);
        }
    }

    private JsonNode readJsonInput() {
        String raw;
        if (data.startsWith("@")) {
            String ref = data.substring(1);
            try {
                if (ref.equals("-")) {
                    raw = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    raw = Files.readString(Path.of(ref));
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read input: " + data, e);
            }
        } else {
            raw = data;
        }
        try {
            return json.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON input", e);
        }
    }
}
