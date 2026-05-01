package it.unimib.datai.nanofaas.cli.commands.fn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "test", description = "Run JSON contract payloads against a function.")
public class FnTestCommand implements Callable<Integer> {

    @ParentCommand
    FnCommand parent;

    @Parameters(index = "0", description = "Function name")
    String name;

    @Option(names = {"--payloads"}, required = true, description = "Payload JSON file or directory.")
    Path payloads;

    @Option(names = {"--timeout-ms"}, description = "X-Timeout-Ms header")
    Integer timeoutMs;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Override
    public Integer call() {
        List<Path> files = payloadFiles(payloads);
        int passed = 0;
        int failed = 0;

        for (Path file : files) {
            PayloadCase payload = readPayload(file);
            InvocationRequest request = new InvocationRequest(payload.input(), null);
            InvocationResponse response;
            try {
                response = parent.root.controlPlaneClient().invokeSync(name, request, null, null, timeoutMs);
            } catch (RuntimeException e) {
                failed++;
                System.out.printf("\u274c %s - invocation failed: %s%n", file.getFileName(), e.getMessage());
                continue;
            }

            if (!"success".equalsIgnoreCase(response.status())) {
                failed++;
                System.out.printf("\u274c %s - %s%n", file.getFileName(), payload.description());
                System.out.printf("  status: %s%n", response.status());
                if (response.error() != null) {
                    System.out.printf("  error:  %s%n", compact(json.valueToTree(response.error())));
                }
                continue;
            }

            JsonNode actual = json.valueToTree(response.output());
            if (actual.equals(payload.expected())) {
                passed++;
                System.out.printf("\u2705 %s - %s%n", file.getFileName(), payload.description());
            } else {
                failed++;
                System.out.printf("\u274c %s - %s%n", file.getFileName(), payload.description());
                System.out.printf("  expected: %s%n", compact(payload.expected()));
                System.out.printf("  actual:   %s%n", compact(actual));
            }
        }

        System.out.printf("%d passed, %d failed%n", passed, failed);
        return failed == 0 ? 0 : 1;
    }

    private List<Path> payloadFiles(Path source) {
        if (Files.isRegularFile(source)) {
            return List.of(source);
        }
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Payload path does not exist: " + source);
        }
        try (Stream<Path> stream = Files.list(source)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalArgumentException("No JSON payload files found in: " + source);
            }
            return files;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list payloads: " + source, e);
        }
    }

    private PayloadCase readPayload(Path file) {
        JsonNode root;
        try {
            root = json.readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid payload JSON: " + file, e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("Payload must be a JSON object: " + file);
        }
        JsonNode input = require(root, "input", file);
        JsonNode expected = require(root, "expected", file);
        String description = root.path("description").asText(file.getFileName().toString());
        return new PayloadCase(description, resolveInput(file, input, root), expected);
    }

    private JsonNode resolveInput(Path payloadFile, JsonNode input, JsonNode root) {
        if (!input.isTextual() || !input.asText().startsWith("@")) {
            return input;
        }

        Path inputFile = payloadFile.getParent().resolve(input.asText().substring(1)).normalize();
        String encoding = root.path("input-encoding").asText("");
        try {
            if ("base64".equalsIgnoreCase(encoding)) {
                return json.getNodeFactory().textNode(Base64.getEncoder().encodeToString(Files.readAllBytes(inputFile)));
            }
            if (inputFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                return json.readTree(inputFile.toFile());
            }
            return json.getNodeFactory().textNode(Files.readString(inputFile));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read input reference: " + input.asText(), e);
        }
    }

    private JsonNode require(JsonNode root, String field, Path file) {
        if (!root.has(field)) {
            throw new IllegalArgumentException("Payload missing required field '" + field + "': " + file);
        }
        return root.get(field);
    }

    private String compact(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render JSON", e);
        }
    }

    private record PayloadCase(String description, JsonNode input, JsonNode expected) {
    }
}
