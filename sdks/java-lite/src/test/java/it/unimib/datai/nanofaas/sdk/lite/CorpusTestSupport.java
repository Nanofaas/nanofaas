package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class CorpusTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static Path find(String relative) {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve(relative))) return root.resolve(relative);
        }
        throw new AssertionError("missing " + relative);
    }
    static JsonNode load() throws Exception {
        return MAPPER.readTree(Files.readAllBytes(find("sdks/runtime-contract/saturation-wire-corpus.json")));
    }
    @SuppressWarnings("codeql[java/relative-path-command]") // Test toolchain intentionally resolves python3 via PATH.
    static void validate(JsonNode corpus) throws Exception {
        Path temporary = Files.createTempFile("java-corpus-", ".json");
        try {
            Files.write(temporary, MAPPER.writeValueAsBytes(corpus));
            Process process = new ProcessBuilder("python3",
                    find("sdks/runtime-contract/validate_saturation_wire_corpus.py").toString(),
                    temporary.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "validator timeout");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally { Files.deleteIfExists(temporary); }
    }
    static JsonNode scenario(JsonNode corpus, String id) {
        for (JsonNode row : corpus.path("scenarios")) if (row.path("id").asText().equals(id)) return row;
        throw new AssertionError("missing scenario " + id);
    }
    static ObjectNode object(JsonNode node) { return (ObjectNode) node; }
    static void mutate(JsonNode corpus, String mutation) {
        JsonNode definitions = corpus.path("contractDefinitions").path(
                corpus.path("policy").path("definitionsRef").asText());
        switch (mutation) {
            case "status" -> {
                object(definitions.path("wireOutcomes").path("success")).put("status", 201);
                for (JsonNode row : corpus.path("scenarios"))
                    for (JsonNode response : row.path("expected").path("responses"))
                        if (response.path("outcomeRef").asText().equals("success")) object(response).put("status", 201);
            }
            case "outcome" -> {
                object(definitions.path("wireOutcomes").path("success").path("body")).put("result", "mutated");
                for (JsonNode row : corpus.path("scenarios"))
                    for (JsonNode response : row.path("expected").path("responses"))
                        if (response.path("outcomeRef").asText().equals("success"))
                            object(response.path("body")).put("result", "mutated");
            }
            case "callback" -> {
                object(definitions.path("callbackEnvelopes").path("success").path("payload").path("output"))
                        .put("result", "mutated");
                for (JsonNode row : corpus.path("scenarios"))
                    for (JsonNode callback : row.path("expected").path("callbacks"))
                        if (callback.path("envelopeRef").asText().equals("success"))
                            object(callback.path("requestProjection").path("payload").path("output"))
                                    .put("result", "mutated");
            }
            case "attempts" -> {
                object(definitions.path("callbackLifecycles").path("delivered-once").path("attempts")).put("value", 2);
                for (JsonNode row : corpus.path("scenarios"))
                    for (JsonNode callback : row.path("expected").path("callbacks"))
                        if (callback.path("lifecycleRef").asText().equals("delivered-once")) {
                            object(callback).put("attempts", 2);
                            ArrayNode attempts = (ArrayNode) callback.path("dispatchAttempts");
                            attempts.add(attempts.get(0).asInt());
                        }
            }
            case "counters" -> object(scenario(corpus, "success-drain").path("initialCounters")).put("inputBytes", 128);
            case "observations" -> {
                ((ArrayNode) definitions.path("observationSets").path("success-drain")).add("handler-cancel");
                ((ArrayNode) scenario(corpus, "success-drain").path("expected").path("observations")).add("handler-cancel");
            }
            default -> throw new AssertionError(mutation);
        }
    }
}
