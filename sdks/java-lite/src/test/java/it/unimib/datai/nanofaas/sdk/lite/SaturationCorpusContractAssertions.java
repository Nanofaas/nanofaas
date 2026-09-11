package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** Only runtime observations are compared here; contract definitions are never an oracle. */
final class SaturationCorpusContractAssertions {
    private SaturationCorpusContractAssertions() { }
    static void assertScenario(JsonNode scenario, SaturationRuntimeHarness observed) {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> same(scenario.path("initialCounters"), observed.initial, "initialCounters"));
        JsonNode expected = scenario.path("expected");
        for (JsonNode response : expected.path("responses")) {
            var call = observed.calls.get(response.path("requestId").asText());
            for (String field : List.of("status", "connectionOutcome", "body", "requiredHeaders"))
                checks.add(() -> same(response.path(field), call.response.path(field), call.id + " response." + field));
        }
        for (JsonNode handler : expected.path("handlers")) {
            var call = observed.calls.get(handler.path("requestId").asText());
            var actual = observed.observedHandlers(call);
            for (String field : List.of("started", "cancelRequested", "terminal"))
                checks.add(() -> same(handler.path(field), actual.path(field), call.id + " handler." + field));
        }
        for (JsonNode callback : expected.path("callbacks")) {
            var call = observed.calls.get(callback.path("requestId").asText());
            var actual = observed.observedCallbacks(call);
            for (String field : List.of("required", "attempted", "delivered", "attempts",
                    "terminal", "dispatchAttempts", "requestProjection"))
                checks.add(() -> same(callback.path(field), actual.path(field), call.id + " callback." + field));
        }
        checks.add(() -> same(expected.path("identity"), observed.observedIdentity(), "identity"));
        for (JsonNode observation : expected.path("observations"))
            checks.add(() -> assertTrue(observed.observations.contains(observation.asText()),
                    "runtime mismatch: missing observation " + observation.asText()));
        checks.add(() -> same(expected.path("finalCounters"), observed.last, "finalCounters"));
        assertAll("runtime mismatch: " + scenario.path("id").asText(), checks);
    }
    private static void same(JsonNode expected, JsonNode actual, String name) throws Exception {
        assertNotNull(actual, "runtime mismatch: absent " + name);
        // Normalize only JSON integer node widths (LongNode versus IntNode).
        assertEquals(CorpusTestSupport.MAPPER.readTree(expected.toString()),
                CorpusTestSupport.MAPPER.readTree(actual.toString()), "runtime mismatch: " + name);
    }
}
