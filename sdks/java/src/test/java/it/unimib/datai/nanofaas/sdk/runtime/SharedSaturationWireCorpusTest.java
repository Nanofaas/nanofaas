package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        Definitions definitions = required(corpus.contractDefinitions, "contractDefinitions")
                .get(required(corpus.policy, "policy").definitionsRef);
        assertEquals(Set.copyOf(definitions.vocabulary.scenarioKinds), projection.scenarioKinds);
        assertEquals(corpus.scenarios.size(), projection.scenarioCount);
        assertTrue(projection.requestCount >= projection.scenarioCount);
        assertTrue(projection.actionCount > projection.requestCount);
        assertFalse(corpus.mutationTests.isEmpty());
    }

    private static Projection project(Corpus corpus) {
        required(corpus.schemaVersion, "schemaVersion");
        Policy policy = required(corpus.policy, "policy");
        required(policy.maximumScenarioDeadlineMs, "maximumScenarioDeadlineMs");
        Definitions definitions = required(
                required(corpus.contractDefinitions, "contractDefinitions")
                        .get(required(policy.definitionsRef, "definitionsRef")),
                "referenced definitions");
        touchDefinitions(definitions);
        required(corpus.runtimeConfigurations, "runtimeConfigurations").values()
                .forEach(SharedSaturationWireCorpusTest::touchConfiguration);
        int requests = 0;
        int actions = 0;
        for (Scenario scenario : required(corpus.scenarios, "scenarios")) {
            required(scenario.id, "scenario.id"); required(scenario.kind, "scenario.kind");
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

    private static void touchDefinitions(Definitions value) {
        touchVocabulary(required(value.vocabulary, "definitions.vocabulary"));
        required(value.actorActionCompatibility, "actorActionCompatibility").forEach((actor, actions) -> {
            required(actor, "actor"); required(actions, "actor actions").forEach(Objects::requireNonNull);
        });
        required(value.handlerLifecycles, "handlerLifecycles").values().forEach(lifecycle -> {
            required(lifecycle.started, "handler.started"); required(lifecycle.cancelRequested, "handler.cancelRequested");
            required(lifecycle.terminal, "handler.terminal");
        });
        required(value.handlerBehaviorLifecycleRefs, "handlerBehaviorLifecycleRefs").values().forEach(SharedSaturationWireCorpusTest::touchStrings);
        required(value.callbackLifecycles, "callbackLifecycles").values().forEach(lifecycle -> {
            required(lifecycle.required, "callback.required"); required(lifecycle.attempted, "callback.attempted");
            required(lifecycle.delivered, "callback.delivered"); touchExpression(required(lifecycle.attempts, "callback.attempts"));
            required(lifecycle.terminal, "callback.terminal");
        });
        required(value.callbackBehaviorLifecycleRefs, "callbackBehaviorLifecycleRefs").values().forEach(SharedSaturationWireCorpusTest::touchStrings);
        required(value.wireOutcomes, "wireOutcomes").values().forEach(outcome -> {
            required(outcome.connectionOutcome, "outcome.connectionOutcome"); required(outcome.status, "outcome.status");
            required(outcome.requiredHeaders, "outcome.requiredHeaders"); required(outcome.body, "outcome.body presence");
        });
        CallbackRequestTemplate template = required(value.callbackRequestTemplate, "callbackRequestTemplate");
        required(template.method, "callback method"); touchExpression(required(template.url, "callback url"));
        required(template.headers, "callback headers").values().forEach(SharedSaturationWireCorpusTest::touchExpression);
        required(value.callbackEnvelopes, "callbackEnvelopes").values().forEach(envelope -> {
            required(envelope.emitsRequest, "envelope.emitsRequest"); required(envelope.payload, "envelope.payload presence");
        });
        required(value.sizeRelationOperators, "sizeRelationOperators").values().forEach(item -> required(item, "size operator"));
        required(required(value.finalCountersRule, "finalCountersRule").operator, "final counter operator");
        required(value.identityRules, "identityRules").forEach(SharedSaturationWireCorpusTest::touchRule);
        required(value.crossFieldRules, "crossFieldRules").forEach(SharedSaturationWireCorpusTest::touchRule);
        required(value.observationSets, "observationSets").values().forEach(SharedSaturationWireCorpusTest::touchStrings);
    }

    private static void touchVocabulary(Vocabulary value) {
        touchStrings(value.scenarioKinds); touchStrings(value.implementationOwners); touchStrings(value.requestRoles);
        touchStrings(value.sizeRelations); touchStrings(value.handlerBehaviors); touchStrings(value.callbackBehaviors);
        touchStrings(value.actors); touchStrings(value.actions); touchStrings(value.handlerTerminals);
        touchStrings(value.callbackTerminals); touchStrings(value.connectionOutcomes); touchStrings(value.observations);
        touchStrings(value.counterNames);
    }

    private static void touchRule(Rule value) {
        required(value.id, "rule.id"); required(value.scope, "rule.scope"); required(value.operator, "rule.operator");
        required(value.source, "rule.source");
    }

    private static void touchExpression(Expression value) {
        required(value.operator, "expression.operator");
    }

    private static void touchStrings(List<String> values) {
        required(values, "string list").forEach(Objects::requireNonNull);
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
        required(value.path, "request.path"); required(value.metadata, "request.metadata");
        Payload payload = required(value.payload, "request.payload");
        required(payload.inputBytes, "inputBytes"); required(payload.relationToInputLimit, "relationToInputLimit");
    }

    private static void touchBackend(Backend backend) {
        required(backend.handlers, "backend.handlers").forEach(handler -> {
            required(handler.requestId, "handler.requestId"); required(handler.behavior, "handler.behavior");
            required(handler.outputBytes, "handler.outputBytes"); required(handler.outputRelationToLimit, "handler.outputRelationToLimit");
        });
        required(backend.callbacks, "backend.callbacks").forEach(callback -> {
            required(callback.requestId, "callback.requestId"); required(callback.behavior, "callback.behavior");
        });
    }

    private static int touchHarness(Harness harness) {
        required(harness.barriers, "harness.barriers").forEach(barrier -> {
            required(barrier.id, "barrier.id"); required(barrier.initialState, "barrier.initialState");
        });
        required(harness.actions, "harness.actions").forEach(action -> {
            required(action.sequence, "action.sequence"); required(action.actor, "action.actor"); required(action.action, "action.action");
        });
        return harness.actions.size();
    }

    private static void touchExpected(Expected expected) {
        required(expected.responses, "expected.responses").forEach(response -> {
            required(response.requestId, "response.requestId"); required(response.outcomeRef, "response.outcomeRef");
            required(response.connectionOutcome, "response.connectionOutcome"); required(response.status, "response.status");
            required(response.requiredHeaders, "response.requiredHeaders");
        });
        required(expected.handlers, "expected.handlers").forEach(handler -> {
            required(handler.requestId, "handler.requestId"); required(handler.lifecycleRef, "handler.lifecycleRef");
            required(handler.started, "handler.started"); required(handler.cancelRequested, "handler.cancelRequested");
            required(handler.terminal, "handler.terminal");
        });
        required(expected.callbacks, "expected.callbacks").forEach(callback -> {
            required(callback.requestId, "callback.requestId"); required(callback.lifecycleRef, "callback.lifecycleRef");
            required(callback.envelopeRef, "callback.envelopeRef"); required(callback.required, "callback.required");
            required(callback.attempted, "callback.attempted"); required(callback.delivered, "callback.delivered");
            required(callback.attempts, "callback.attempts"); required(callback.terminal, "callback.terminal");
            required(callback.dispatchAttempts, "callback.dispatchAttempts");
            if (callback.requestProjection != null) {
                required(callback.requestProjection.method, "callback projection method");
                required(callback.requestProjection.url, "callback projection url");
                required(callback.requestProjection.headers, "callback projection headers");
                required(callback.requestProjection.payload, "callback projection payload");
            }
        });
        ExpectedIdentity identity = required(expected.identity, "expected.identity");
        required(identity.requestDispatchAttempts, "identity.requestDispatchAttempts");
        required(identity.runtimeRedispatchCount, "identity.runtimeRedispatchCount");
        required(expected.observationSetRef, "observationSetRef"); touchStrings(expected.observations);
        touchCounters(required(expected.finalCounters, "finalCounters"));
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
    private record Corpus(String schemaVersion, Map<String, Definitions> contractDefinitions, Policy policy, Map<String, RuntimeConfiguration> runtimeConfigurations, List<Scenario> scenarios, List<Mutation> mutationTests) {}
    private record Policy(Integer maximumScenarioDeadlineMs, String definitionsRef) {}
    private record Definitions(Vocabulary vocabulary, Map<String, List<String>> actorActionCompatibility, Map<String, HandlerLifecycle> handlerLifecycles, Map<String, List<String>> handlerBehaviorLifecycleRefs, Map<String, CallbackLifecycle> callbackLifecycles, Map<String, List<String>> callbackBehaviorLifecycleRefs, Map<String, WireOutcome> wireOutcomes, Map<String, CallbackEnvelope> callbackEnvelopes, CallbackRequestTemplate callbackRequestTemplate, Map<String, String> sizeRelationOperators, FinalRule finalCountersRule, List<Rule> identityRules, List<Rule> crossFieldRules, Map<String, List<String>> observationSets) {}
    private record HandlerLifecycle(Boolean started, Boolean cancelRequested, String terminal) {}
    private record CallbackLifecycle(Boolean required, Boolean attempted, Boolean delivered, Expression attempts, String terminal) {}
    private record WireOutcome(String connectionOutcome, Integer status, JsonNode body, Map<String, String> requiredHeaders) {}
    private record CallbackEnvelope(Boolean emitsRequest, JsonNode payload) {}
    private record CallbackRequestTemplate(String method, Expression url, Map<String, Expression> headers) {}
    private record Expression(String operator, JsonNode value, String path, String callbackUrlPath, String executionIdPath, String suffix, String field) {}
    private record FinalRule(String operator) {}
    private record Rule(String id, String scope, String operator, String source, String expected, Boolean ignoreNull, Integer increment, JsonNode value) {}
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
    private record Expected(List<ExpectedResponse> responses, List<ExpectedHandler> handlers, List<ExpectedCallback> callbacks, ExpectedIdentity identity, String observationSetRef, List<String> observations, Counters finalCounters) {}
    private record ExpectedResponse(String requestId, String outcomeRef, String connectionOutcome, Integer status, JsonNode body, Map<String, String> requiredHeaders) {}
    private record ExpectedHandler(String requestId, String lifecycleRef, Boolean started, Boolean cancelRequested, String terminal) {}
    private record ExpectedCallback(String requestId, String lifecycleRef, String envelopeRef, Boolean required, Boolean attempted, Boolean delivered, Integer attempts, String terminal, List<Integer> dispatchAttempts, CallbackRequestProjection requestProjection) {}
    private record CallbackRequestProjection(String method, String url, Map<String, String> headers, JsonNode payload) {}
    private record ExpectedIdentity(String executionId, List<Integer> requestDispatchAttempts, Integer runtimeRedispatchCount) {}
    private record Mutation(String id, String operation, String path, JsonNode value) {}
}
