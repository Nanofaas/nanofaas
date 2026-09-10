package it.unimib.datai.nanofaas.sdk.lite;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedSaturationWireCorpusTest {
    @Test
    void consumesTheSharedRuntimeSaturationWireContract() throws Exception {
        Path corpusPath = findRepositoryFile("sdks/runtime-contract/saturation-wire-corpus.json");
        runAuthoritativeValidator(corpusPath);
        Corpus corpus = new ObjectMapper().readValue(Files.readAllBytes(corpusPath), Corpus.class);
        Projection projection = project(required(corpus, "corpus"));
        assertEquals(Set.copyOf(corpus.policy.vocabulary.scenarioKinds), projection.scenarioKinds);
        assertEquals(corpus.scenarios.size(), projection.scenarioCount);
        assertTrue(projection.requestCount >= projection.scenarioCount);
        assertTrue(projection.actionCount > projection.requestCount);
        assertFalse(corpus.mutationTests.isEmpty());
    }

    private static Projection project(Corpus corpus) {
        required(corpus.schemaVersion, "schemaVersion");
        Policy policy = required(corpus.policy, "policy");
        required(policy.maximumScenarioDeadlineMs, "maximumScenarioDeadlineMs");
        IdentityPolicy identity = required(policy.identity, "identity");
        required(identity.executionIdAcrossDispatchRetries, "executionIdAcrossDispatchRetries");
        required(identity.dispatchAttemptAcrossDispatchRetries, "dispatchAttemptAcrossDispatchRetries");
        required(identity.callbackDeliveryRetries, "callbackDeliveryRetries");
        required(identity.runtimeRedispatch, "runtimeRedispatch");
        touchVocabulary(required(policy.vocabulary, "vocabulary"));
        required(corpus.runtimeConfigurations, "runtimeConfigurations").values()
                .forEach(SharedSaturationWireCorpusTest::touchConfiguration);
        int requests = 0;
        int actions = 0;
        for (Scenario scenario : required(corpus.scenarios, "scenarios")) {
            required(scenario.id, "scenario.id");
            required(scenario.kind, "scenario.kind");
            required(scenario.implementationOwners, "scenario.implementationOwners").forEach(Objects::requireNonNull);
            required(scenario.runtimeConfigRef, "scenario.runtimeConfigRef");
            requests += required(scenario.requests, "scenario.requests").size();
            scenario.requests.forEach(SharedSaturationWireCorpusTest::touchRequest);
            touchBackend(required(scenario.backend, "scenario.backend"));
            actions += touchHarness(required(scenario.harness, "scenario.harness"));
            touchCounters(required(scenario.initialCounters, "scenario.initialCounters"));
            touchExpected(required(scenario.expected, "scenario.expected"));
            Integer deadline = required(scenario.deadlineMs, "scenario.deadlineMs");
            assertTrue(deadline > 0 && deadline <= policy.maximumScenarioDeadlineMs);
        }
        required(corpus.mutationTests, "mutationTests").forEach(SharedSaturationWireCorpusTest::touchMutation);
        Set<String> kinds = corpus.scenarios.stream().map(Scenario::kind).collect(Collectors.toSet());
        return new Projection(corpus.scenarios.size(), requests, actions, kinds);
    }

    private static void touchVocabulary(Vocabulary value) {
        required(value.scenarioKinds, "scenarioKinds"); required(value.implementationOwners, "implementationOwners");
        required(value.requestRoles, "requestRoles"); required(value.sizeRelations, "sizeRelations");
        required(value.handlerBehaviors, "handlerBehaviors"); required(value.callbackBehaviors, "callbackBehaviors");
        required(value.actors, "actors"); required(value.actions, "actions");
        required(value.handlerTerminals, "handlerTerminals"); required(value.callbackTerminals, "callbackTerminals");
        required(value.connectionOutcomes, "connectionOutcomes"); required(value.observations, "observations");
        required(value.counterNames, "counterNames");
    }

    private static void touchConfiguration(RuntimeConfiguration value) {
        required(value.maxConcurrentHandlers, "maxConcurrentHandlers"); required(value.maxInputBytes, "maxInputBytes");
        required(value.maxOutputBytes, "maxOutputBytes"); required(value.maxPendingCallbacks, "maxPendingCallbacks");
        required(value.maxPendingCallbackBytes, "maxPendingCallbackBytes"); required(value.handlerTimeoutMs, "handlerTimeoutMs");
        required(value.callbackAttemptTimeoutMs, "callbackAttemptTimeoutMs"); required(value.callbackMaxAttempts, "callbackMaxAttempts");
        required(value.bodyReadTimeoutMs, "bodyReadTimeoutMs"); required(value.shutdownTimeoutMs, "shutdownTimeoutMs");
    }

    private static void touchRequest(Request value) {
        required(value.id, "request.id"); required(value.role, "request.role"); required(value.method, "request.method");
        required(value.path, "request.path"); Metadata metadata = required(value.metadata, "request.metadata");
        if (metadata.executionId != null) required(metadata.executionId, "executionId");
        if (metadata.dispatchAttempt != null) required(metadata.dispatchAttempt, "dispatchAttempt");
        if (metadata.traceId != null) required(metadata.traceId, "traceId");
        if (metadata.callbackUrl != null) required(metadata.callbackUrl, "callbackUrl");
        Payload payload = required(value.payload, "request.payload");
        required(payload.inputBytes, "inputBytes"); required(payload.relationToInputLimit, "relationToInputLimit");
    }

    private static void touchBackend(Backend backend) {
        required(backend.handlers, "backend.handlers").forEach(handler -> {
            required(handler.requestId, "handler.requestId"); required(handler.behavior, "handler.behavior");
            required(handler.outputBytes, "handler.outputBytes"); required(handler.outputRelationToLimit, "handler.outputRelationToLimit");
            if (handler.barrier != null) required(handler.barrier, "handler.barrier");
        });
        required(backend.callbacks, "backend.callbacks").forEach(callback -> {
            required(callback.requestId, "callback.requestId"); required(callback.behavior, "callback.behavior");
            if (callback.barrier != null) required(callback.barrier, "callback.barrier");
        });
    }

    private static int touchHarness(Harness harness) {
        required(harness.barriers, "harness.barriers").forEach(barrier -> {
            required(barrier.id, "barrier.id"); required(barrier.initialState, "barrier.initialState");
        });
        required(harness.actions, "harness.actions").forEach(action -> {
            required(action.sequence, "action.sequence"); required(action.actor, "action.actor");
            required(action.action, "action.action");
            if (action.requestId != null) required(action.requestId, "action.requestId");
            if (action.barrier != null) required(action.barrier, "action.barrier");
        });
        return harness.actions.size();
    }

    private static void touchExpected(Expected expected) {
        required(expected.responses, "expected.responses").forEach(response -> {
            required(response.requestId, "response.requestId"); required(response.connectionOutcome, "response.connectionOutcome");
            required(response.status, "response.status"); required(response.requiredHeaders, "response.requiredHeaders");
        });
        required(expected.handlers, "expected.handlers").forEach(handler -> {
            required(handler.requestId, "expected.handler.requestId"); required(handler.started, "expected.handler.started");
            required(handler.cancelRequested, "expected.handler.cancelRequested"); required(handler.terminal, "expected.handler.terminal");
        });
        required(expected.callbacks, "expected.callbacks").forEach(callback -> {
            required(callback.requestId, "expected.callback.requestId"); required(callback.required, "expected.callback.required");
            required(callback.attempted, "expected.callback.attempted"); required(callback.delivered, "expected.callback.delivered");
            required(callback.attempts, "expected.callback.attempts"); required(callback.terminal, "expected.callback.terminal");
            required(callback.dispatchAttempts, "expected.callback.dispatchAttempts");
        });
        ExpectedIdentity identity = required(expected.identity, "expected.identity");
        if (identity.executionId != null) required(identity.executionId, "expected.identity.executionId");
        required(identity.requestDispatchAttempts, "expected.identity.requestDispatchAttempts");
        required(identity.runtimeRedispatchCount, "expected.identity.runtimeRedispatchCount");
        required(expected.observations, "expected.observations");
        touchCounters(required(expected.finalCounters, "expected.finalCounters"));
    }

    private static void touchCounters(Counters counters) {
        required(counters.activeHandlers, "activeHandlers"); required(counters.inputBytes, "inputBytes");
        required(counters.outputBytes, "outputBytes"); required(counters.pendingCallbacks, "pendingCallbacks");
        required(counters.pendingCallbackBytes, "pendingCallbackBytes"); required(counters.serializedCallbackBytes, "serializedCallbackBytes");
    }

    private static void touchMutation(Mutation mutation) {
        required(mutation.id, "mutation.id"); required(mutation.operation, "mutation.operation"); required(mutation.path, "mutation.path");
    }

    private static void runAuthoritativeValidator(Path corpusPath) throws Exception {
        Path validator = findRepositoryFile("sdks/runtime-contract/validate_saturation_wire_corpus.py");
        Process process = new ProcessBuilder("python3", validator.toString(), "--run-mutations", corpusPath.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                "shared validator exceeded finite adapter deadline");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
    }

    private static Path findRepositoryFile(String relativePath) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException(relativePath + " not found");
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    private record Projection(int scenarioCount, int requestCount, int actionCount, Set<String> scenarioKinds) {}
    private record Corpus(String schemaVersion, Policy policy, Map<String, RuntimeConfiguration> runtimeConfigurations, List<Scenario> scenarios, List<Mutation> mutationTests) {}
    private record Policy(Integer maximumScenarioDeadlineMs, IdentityPolicy identity, Vocabulary vocabulary) {}
    private record IdentityPolicy(String executionIdAcrossDispatchRetries, String dispatchAttemptAcrossDispatchRetries, String callbackDeliveryRetries, String runtimeRedispatch) {}
    private record Vocabulary(List<String> scenarioKinds, List<String> implementationOwners, List<String> requestRoles, List<String> sizeRelations, List<String> handlerBehaviors, List<String> callbackBehaviors, List<String> actors, List<String> actions, List<String> handlerTerminals, List<String> callbackTerminals, List<String> connectionOutcomes, List<String> observations, List<String> counterNames) {}
    private record RuntimeConfiguration(Integer maxConcurrentHandlers, Integer maxInputBytes, Integer maxOutputBytes, Integer maxPendingCallbacks, Integer maxPendingCallbackBytes, Integer handlerTimeoutMs, Integer callbackAttemptTimeoutMs, Integer callbackMaxAttempts, Integer bodyReadTimeoutMs, Integer shutdownTimeoutMs) {}
    private record Scenario(String id, String kind, List<String> implementationOwners, String runtimeConfigRef, List<Request> requests, Backend backend, Harness harness, Counters initialCounters, Expected expected, Integer deadlineMs) {}
    private record Request(String id, String role, String method, String path, Metadata metadata, Payload payload) {}
    private record Metadata(String executionId, Integer dispatchAttempt, String traceId, String callbackUrl) {}
    private record Payload(Integer inputBytes, String relationToInputLimit) {}
    private record Backend(List<HandlerBackend> handlers, List<CallbackBackend> callbacks) {}
    private record HandlerBackend(String requestId, String behavior, Integer outputBytes, String outputRelationToLimit, String barrier) {}
    private record CallbackBackend(String requestId, String behavior, String barrier) {}
    private record Harness(List<Barrier> barriers, List<Action> actions) {}
    private record Barrier(String id, String initialState) {}
    private record Action(Integer sequence, String actor, String action, String requestId, String barrier) {}
    private record Counters(Integer activeHandlers, Integer inputBytes, Integer outputBytes, Integer pendingCallbacks, Integer pendingCallbackBytes, Integer serializedCallbackBytes) {}
    private record Expected(List<ExpectedResponse> responses, List<ExpectedHandler> handlers, List<ExpectedCallback> callbacks, ExpectedIdentity identity, List<String> observations, Counters finalCounters) {}
    private record ExpectedResponse(String requestId, String connectionOutcome, Integer status, JsonNode body, Map<String, String> requiredHeaders) {}
    private record ExpectedHandler(String requestId, Boolean started, Boolean cancelRequested, String terminal) {}
    private record ExpectedCallback(String requestId, Boolean required, Boolean attempted, Boolean delivered, Integer attempts, String terminal, List<Integer> dispatchAttempts) {}
    private record ExpectedIdentity(String executionId, List<Integer> requestDispatchAttempts, Integer runtimeRedispatchCount) {}
    private record Mutation(String id, String operation, String path, JsonNode value) {}
}
