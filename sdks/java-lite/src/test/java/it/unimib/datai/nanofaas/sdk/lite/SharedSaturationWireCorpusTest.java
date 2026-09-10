package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSaturationWireCorpusTest {

    @Test
    void consumesTheSharedRuntimeSaturationWireContract() throws Exception {
        JsonNode corpus = new ObjectMapper().readTree(Files.readAllBytes(findCorpus()));

        assertTrue(corpus.get("version").asInt() > 0);
        assertCommonPolicyAndCases(corpus);
    }

    private static void assertCommonPolicyAndCases(JsonNode corpus) {
        JsonNode runner = corpus.get("runner");
        assertEquals(runner.get("requiredReleaseEvents"), corpus.get("releaseOn"));
        assertFalse(corpus.get("scope").asText().isBlank());
        assertFalse(corpus.get("admissionPoint").asText().isBlank());
        corpus.get("retryIdentity").properties()
                .forEach(entry -> assertFalse(entry.getValue().asText().isBlank()));

        Set<String> requiredIds = new HashSet<>();
        runner.get("requiredCaseIds").forEach(id -> requiredIds.add(id.asText()));
        Set<String> actualIds = new HashSet<>();
        JsonNode cases = corpus.get("cases");
        cases.forEach(testCase -> {
            assertTrue(actualIds.add(testCase.get("id").asText()), "duplicate case id");
            assertFalse(testCase.get("implementationOwner").asText().isBlank());
            assertFalse(testCase.get("stimulus").asText().isBlank());
            long timeoutMs = testCase.get("timeoutMs").asLong();
            assertTrue(timeoutMs > 0 && timeoutMs <= runner.get("maximumCaseTimeoutMs").asLong());
            JsonNode expected = testCase.get("expected");
            int status = expected.get("httpStatus").asInt();
            assertTrue(status == runner.get("noSecondResponseStatus").asInt()
                    || status >= runner.get("minimumHttpStatus").asInt()
                    && status <= runner.get("maximumHttpStatus").asInt());
            assertTrue(expected.get("errorCode").asText().matches("[A-Z][A-Z0-9_]+"));
            assertFalse(expected.get("message").asText().isBlank());
            assertTrue(expected.get("retryable").isBoolean());
            assertTrue(expected.get("handlerStarted").isBoolean());
            assertTrue(expected.get("callbackExpected").isBoolean());
            assertTrue(expected.get("release").isArray());
            assertTrue(expected.get("release").size() > 0);
        });
        assertEquals(requiredIds, actualIds);
    }

    private static Path findCorpus() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("sdks/runtime-contract/saturation-wire-corpus.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("shared saturation wire corpus not found");
    }
}
