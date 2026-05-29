# Java SDK Callback JSON Contract Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the Java SDK callback serialization fallback with a correct JSON contract that is native-image friendly, preserves structured function outputs, and keeps callback delivery independent from Spring `HttpMessageConverter` availability.

**Architecture:** Move JSON normalization to a dedicated runtime component before callback dispatch. The invoke path will convert handler output once into a Jackson `JsonNode`, return that JSON body to the caller, and send the same JSON-safe value in the callback payload. Callback transport will only serialize a DTO made of concrete JSON-safe fields, with Spring runtime hints for GraalVM native images. This removes the string-output fallback and makes the failure mode explicit: if a handler returns something that cannot be represented as JSON, the runtime returns a controlled 500 and sends a structured error callback.

**Tech Stack:** Java 21, Spring Boot, Jackson `ObjectMapper`/`JsonNode`, Spring AOT `RuntimeHintsRegistrar`, Gradle, JUnit 5, OkHttp `MockWebServer`, k6 E2E scenario.

---

## Non-Goals

- Do not change the public HTTP shape of callbacks: keep `{"success": ..., "output": ..., "error": ...}`.
- Do not add function-specific serializers for `word-stats` or `json-transform`.
- Do not keep the fallback that stringifies successful outputs.
- Do not refactor the control-plane sync queue/backpressure logic in this plan.
- Do not change Java Lite unless a test proves it has the same bug.

## Current Problem

The current local `main` contains a pragmatic fallback in:

- `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java`

It catches Jackson serialization failure for `InvocationResult` and sends `output` as `String.valueOf(output)`. This stops callback retries, but it degrades structured callback data and hides the real contract issue.

The clean fix is to remove `Object` from the callback transport boundary. The Java SDK should normalize handler output into `JsonNode` in one place, then callback serialization becomes deterministic in JVM and native images.

## Required Pre-Work

Run this work in a dedicated worktree.

Before editing each symbol, follow repository rules and run GitNexus impact analysis:

```bash
npx gitnexus analyze
```

Then use GitNexus impact for these symbols before touching them:

- `CallbackClient`
- `CallbackDispatcher`
- `InvokeController`
- `HttpClientConfig`

Expected: no `HIGH`/`CRITICAL` warning ignored. If GitNexus reports `HIGH`/`CRITICAL`, stop and report the blast radius before editing.

---

### Task 1: Add JSON-Safe Callback Payload DTO

**Files:**
- Create: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayload.java`
- Test: `function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java`

**Step 1: Write the failing test**

Add this test to `CallbackClientTest` before changing production code:

```java
@Test
void sendResult_sendsStructuredJsonNodeOutputWithoutMessageConverter() throws Exception {
    RestClient restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .messageConverters(converters -> converters.removeIf(converter ->
                    converter.getClass().getName().contains("MappingJackson2HttpMessageConverter")))
            .build();
    CallbackClient clientWithoutJacksonConverter = newCallbackClient(
            restClient,
            new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"));
    ObjectMapper mapper = new ObjectMapper();
    CallbackPayload payload = CallbackPayload.success(mapper.readTree("""
            {"wordCount":4,"topWords":[{"word":"the","count":1}]}
            """));
    server.enqueue(new MockResponse().setResponseCode(200));

    boolean ok = clientWithoutJacksonConverter.sendResult("exec-json", payload, "trace-42");

    assertTrue(ok);
    RecordedRequest req = server.takeRequest();
    String body = req.getBody().readUtf8();
    assertTrue(body.contains("\"success\":true"));
    assertTrue(body.contains("\"wordCount\":4"));
    assertTrue(body.contains("\"topWords\""));
    assertFalse(body.contains("NativeLikeOutput"));
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.CallbackClientTest.sendResult_sendsStructuredJsonNodeOutputWithoutMessageConverter
```

Expected: FAIL because `CallbackPayload` does not exist and `CallbackClient.sendResult(String, CallbackPayload, String)` does not exist.

**Step 3: Add minimal DTO**

Create `CallbackPayload.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;

public record CallbackPayload(
        boolean success,
        JsonNode output,
        ErrorInfo error
) {
    public static CallbackPayload success(JsonNode output) {
        return new CallbackPayload(true, output, null);
    }

    public static CallbackPayload error(String code, String message) {
        return new CallbackPayload(false, null, new ErrorInfo(code, message));
    }
}
```

**Step 4: Temporarily overload `CallbackClient` for the test**

In `CallbackClient.java`, add an overload without removing old behavior yet:

```java
public boolean sendResult(String executionId, CallbackPayload payload, String traceId) {
    String baseUrl = runtimeSettings.callbackUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
        log.warn("CALLBACK_URL not configured, skipping callback for execution {}", executionId);
        return false;
    }
    if (executionId == null || executionId.isBlank()) {
        log.warn("executionId is null or blank, skipping callback");
        return false;
    }

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        try {
            doSendPayload(executionId, payload, traceId);
            log.debug("Callback sent successfully for execution {} (attempt {})", executionId, attempt + 1);
            return true;
        } catch (RestClientException ex) {
            log.warn("Callback failed for execution {} (attempt {}): {}",
                    executionId, attempt + 1, ex.getMessage());
            if (isPermanentClientFailure(ex)) {
                log.error("Permanent callback failure for execution {} with status {}",
                        executionId, ((RestClientResponseException) ex).getStatusCode());
                return false;
            }
            if (attempt < MAX_RETRIES - 1) {
                try {
                    sleepBeforeRetry(attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Callback retry interrupted for execution {}", executionId);
                    return false;
                }
            }
        }
    }

    log.error("All {} callback attempts failed for execution {}", MAX_RETRIES, executionId);
    return false;
}

private void doSendPayload(String executionId, CallbackPayload payload, String traceId) {
    String effectiveTraceId = (traceId != null && !traceId.isBlank())
            ? traceId
            : runtimeSettings.traceId();
    String url = callbackUrl(executionId);

    RestClient.RequestBodySpec request = restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON);

    if (effectiveTraceId != null && !effectiveTraceId.isBlank()) {
        request.header("X-Trace-Id", effectiveTraceId);
    }

    request.body(serializePayload(payload))
            .retrieve()
            .toBodilessEntity();
}

private byte[] serializePayload(CallbackPayload payload) {
    try {
        return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException ex) {
        throw new RestClientException("Failed to serialize callback payload", ex);
    }
}
```

Keep the existing `InvocationResult` methods for now. They will be removed after dispatcher/controller migration.

**Step 5: Run test to verify it passes**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.CallbackClientTest.sendResult_sendsStructuredJsonNodeOutputWithoutMessageConverter
```

Expected: PASS.

**Step 6: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayload.java \
        function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java \
        function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java
git commit -m "Add JSON-safe Java callback payload"
```

---

### Task 2: Add Output JSON Normalizer

**Files:**
- Create: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/JsonOutputNormalizer.java`
- Create: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/OutputSerializationException.java`
- Test: `function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/JsonOutputNormalizerTest.java`

**Step 1: Write failing tests**

Create `JsonOutputNormalizerTest.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonOutputNormalizerTest {

    private final JsonOutputNormalizer normalizer = new JsonOutputNormalizer(new ObjectMapper());

    @Test
    void toJsonNode_preservesStructuredMapOutput() {
        JsonNode node = normalizer.toJsonNode(Map.of(
                "wordCount", 4,
                "topWords", List.of(Map.of("word", "the", "count", 1))
        ));

        assertEquals(4, node.get("wordCount").intValue());
        assertEquals("the", node.get("topWords").get(0).get("word").asText());
    }

    @Test
    void toJsonNode_preservesNullAsJsonNull() {
        JsonNode node = normalizer.toJsonNode(null);

        assertTrue(node.isNull());
    }

    @Test
    void toJsonNode_wrapsSerializationFailureWithClearException() {
        Object invalid = new Object() {
            public Object getSelf() {
                return this;
            }
        };

        OutputSerializationException ex = assertThrows(
                OutputSerializationException.class,
                () -> normalizer.toJsonNode(invalid)
        );
        assertTrue(ex.getMessage().contains("Function output is not JSON-serializable"));
    }
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.JsonOutputNormalizerTest
```

Expected: FAIL because `JsonOutputNormalizer` and `OutputSerializationException` do not exist.

**Step 3: Implement exception**

Create `OutputSerializationException.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

public class OutputSerializationException extends RuntimeException {
    public OutputSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**Step 4: Implement normalizer**

Create `JsonOutputNormalizer.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;

@Component
public class JsonOutputNormalizer {
    private final ObjectMapper objectMapper;

    public JsonOutputNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode toJsonNode(Object output) {
        if (output == null) {
            return NullNode.getInstance();
        }
        if (output instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            return objectMapper.valueToTree(output);
        } catch (IllegalArgumentException ex) {
            throw new OutputSerializationException(
                    "Function output is not JSON-serializable: " + output.getClass().getName(),
                    ex);
        }
    }
}
```

**Step 5: Run tests to verify they pass**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.JsonOutputNormalizerTest
```

Expected: PASS.

**Step 6: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/JsonOutputNormalizer.java \
        function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/OutputSerializationException.java \
        function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/JsonOutputNormalizerTest.java
git commit -m "Normalize Java function outputs to JSON"
```

---

### Task 3: Route Invoke Responses And Callbacks Through The Normalizer

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java`
- Modify: `function-sdk-java/src/test/java/it/unimib/datai/nanofaas/runtime/InvokeControllerTest.java`

**Step 1: Write failing controller test**

In `InvokeControllerTest.java`, add a test that verifies callback submission receives `CallbackPayload`, not raw `InvocationResult`, and the HTTP response remains structured.

Use the existing test style in that file. Add this exact assertion pattern around the existing successful invoke setup:

```java
@Test
void invoke_normalizesOutputOnceForResponseAndCallback() {
    when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

    ResponseEntity<Object> response = controller.invoke(
            new InvocationRequest(Map.of("text", "the quick brown fox"), Map.of()),
            "exec-normalized",
            "trace-normalized");

    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody() instanceof JsonNode);
    JsonNode body = (JsonNode) response.getBody();
    assertEquals(4, body.get("wordCount").asInt());

    ArgumentCaptor<CallbackPayload> payloadCaptor = ArgumentCaptor.forClass(CallbackPayload.class);
    verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
            .sendResult(eq("exec-normalized"), payloadCaptor.capture(), eq("trace-normalized"));
    assertTrue(payloadCaptor.getValue().success());
    assertEquals(4, payloadCaptor.getValue().output().get("wordCount").asInt());
}
```

If the test file does not expose `controller` directly, adapt to its fixture setup but keep the same assertions.

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.runtime.InvokeControllerTest.invoke_normalizesOutputOnceForResponseAndCallback
```

Expected: FAIL because `InvokeController` still submits `InvocationResult.success(output)` and returns raw `Object`.

**Step 3: Update `CallbackDispatcher`**

Change method signature in `CallbackDispatcher.java`:

```java
public boolean submit(String executionId, CallbackPayload payload, String traceId) {
    try {
        executor.execute(() -> callbackClient.sendResult(executionId, payload, traceId));
        return true;
    } catch (RejectedExecutionException ex) {
        log.warn("Dropping callback for execution {} because dispatcher queue is full", executionId);
        return false;
    }
}
```

Remove `InvocationResult` import from `CallbackDispatcher`.

**Step 4: Update `InvokeController` constructor**

Add `JsonOutputNormalizer` dependency:

```java
private final JsonOutputNormalizer outputNormalizer;

public InvokeController(
        CallbackDispatcher callbackDispatcher,
        HandlerRegistry handlerRegistry,
        InvocationRuntimeContextResolver runtimeContextResolver,
        ColdStartTracker coldStartTracker,
        HandlerExecutor handlerExecutor,
        JsonOutputNormalizer outputNormalizer) {
    this.callbackDispatcher = callbackDispatcher;
    this.handlerRegistry = handlerRegistry;
    this.runtimeContextResolver = runtimeContextResolver;
    this.coldStartTracker = coldStartTracker;
    this.handlerExecutor = handlerExecutor;
    this.outputNormalizer = outputNormalizer;
}
```

**Step 5: Update success path**

Replace:

```java
Object output = handlerExecutor.execute(handler, request);

callbackDispatcher.submit(
        effectiveExecutionId,
        InvocationResult.success(output),
        runtimeContext.traceId());
```

with:

```java
Object rawOutput = handlerExecutor.execute(handler, request);
JsonNode output = outputNormalizer.toJsonNode(rawOutput);

callbackDispatcher.submit(
        effectiveExecutionId,
        CallbackPayload.success(output),
        runtimeContext.traceId());
```

Return `output` from the HTTP response:

```java
return responseBuilder.body(output);
```

**Step 6: Update timeout and error paths**

Replace `InvocationResult.error(...)` with `CallbackPayload.error(...)`:

```java
callbackDispatcher.submit(
        effectiveExecutionId,
        CallbackPayload.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
        runtimeContext.traceId());
```

and:

```java
callbackDispatcher.submit(
        effectiveExecutionId,
        CallbackPayload.error("HANDLER_ERROR", errorMessage),
        runtimeContext.traceId());
```

**Step 7: Add explicit serialization failure path**

Add a catch block before the generic `Exception` catch:

```java
} catch (OutputSerializationException ex) {
    String errorMessage = ex.getMessage();
    log.error("Handler output serialization failed for execution {}: {}", effectiveExecutionId, errorMessage, ex);
    callbackDispatcher.submit(
            effectiveExecutionId,
            CallbackPayload.error("OUTPUT_SERIALIZATION_ERROR", errorMessage),
            runtimeContext.traceId());
    return ResponseEntity.status(500)
            .body(Map.of("error", errorMessage));
}
```

**Step 8: Run controller tests**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.runtime.InvokeControllerTest
```

Expected: PASS after updating existing mocks from `InvocationResult` to `CallbackPayload`.

**Step 9: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java \
        function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java \
        function-sdk-java/src/test/java/it/unimib/datai/nanofaas/runtime/InvokeControllerTest.java
git commit -m "Use normalized JSON for Java invoke callbacks"
```

---

### Task 4: Remove The Workaround From CallbackClient

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java`
- Modify: `function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java`

**Step 1: Write failing negative test**

Replace the current fallback test with a test proving the workaround is gone:

```java
@Test
void sendResult_doesNotStringifySuccessfulOutputs() throws Exception {
    RestClient restClient = RestClient.builder()
            .baseUrl(server.url("/").toString())
            .build();
    ObjectMapper mapper = new ObjectMapper();
    CallbackClient strictClient = new CallbackClient(
            restClient,
            new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"),
            mapper);
    CallbackPayload payload = CallbackPayload.success(mapper.readTree("""
            {"nested":{"value":"kept"}}
            """));
    server.enqueue(new MockResponse().setResponseCode(200));

    assertTrue(strictClient.sendResult("exec-strict", payload, "trace-42"));

    String body = server.takeRequest().getBody().readUtf8();
    assertTrue(body.contains("\"nested\":{\"value\":\"kept\"}"));
    assertFalse(body.contains("NativeLikeOutput"));
}
```

**Step 2: Run test**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.CallbackClientTest.sendResult_doesNotStringifySuccessfulOutputs
```

Expected: PASS once `CallbackPayload` is used. If it still references the old fallback helper, clean it up.

**Step 3: Remove old `InvocationResult` sendResult overloads**

In `CallbackClient.java`, remove:

```java
public boolean sendResult(String executionId, InvocationResult result)
public boolean sendResult(String executionId, InvocationResult result, String traceId)
private void doSendResult(String executionId, InvocationResult result, String traceId)
private byte[] serializeResult(InvocationResult result)
private static Map<String, Object> fallbackPayload(InvocationResult result)
```

Keep only:

```java
public boolean sendResult(String executionId, CallbackPayload payload, String traceId)
private void doSendPayload(String executionId, CallbackPayload payload, String traceId)
private byte[] serializePayload(CallbackPayload payload)
```

Remove imports:

```java
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import java.util.LinkedHashMap;
import java.util.Map;
```

**Step 4: Update tests**

Replace all test calls like:

```java
client.sendResult("exec-1", InvocationResult.success("hello"), "trace-42")
```

with:

```java
client.sendResult("exec-1", CallbackPayload.success(new ObjectMapper().valueToTree("hello")), "trace-42")
```

Prefer a private helper in `CallbackClientTest`:

```java
private static CallbackPayload successPayload(Object value) {
    return CallbackPayload.success(new ObjectMapper().valueToTree(value));
}

private static CallbackPayload errorPayload(String code, String message) {
    return CallbackPayload.error(code, message);
}
```

**Step 5: Run full SDK tests**

Run:

```bash
./gradlew :function-sdk-java:test
```

Expected: PASS.

**Step 6: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java \
        function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClientTest.java
git commit -m "Remove Java callback string fallback"
```

---

### Task 5: Add Runtime Hints For Native Image Callback DTOs

**Files:**
- Create: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/NanofaasRuntimeHints.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfig.java`
- Test: `function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/NanofaasRuntimeHintsTest.java`

**Step 1: Write failing test**

Create `NanofaasRuntimeHintsTest.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;

import static org.junit.jupiter.api.Assertions.*;

class NanofaasRuntimeHintsTest {

    @Test
    void registersCallbackPayloadAndErrorInfoForJacksonBinding() {
        RuntimeHints hints = new RuntimeHints();

        new NanofaasRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertNotNull(hints.reflection().getTypeHint(CallbackPayload.class));
        assertNotNull(hints.reflection().getTypeHint(ErrorInfo.class));
        assertTrue(hints.reflection().getTypeHint(CallbackPayload.class)
                .getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.NanofaasRuntimeHintsTest
```

Expected: FAIL because `NanofaasRuntimeHints` does not exist.

**Step 3: Implement runtime hints**

Create `NanofaasRuntimeHints.java`:

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class NanofaasRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(
                CallbackPayload.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS
        );
        hints.reflection().registerType(
                ErrorInfo.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS
        );
    }
}
```

**Step 4: Import hints**

In `HttpClientConfig.java`, add:

```java
import org.springframework.context.annotation.ImportRuntimeHints;
```

Annotate the class:

```java
@Configuration
@ImportRuntimeHints(NanofaasRuntimeHints.class)
public class HttpClientConfig {
```

**Step 5: Run hints test**

Run:

```bash
./gradlew :function-sdk-java:test --tests it.unimib.datai.nanofaas.sdk.runtime.NanofaasRuntimeHintsTest
```

Expected: PASS.

**Step 6: Run AOT tests for Java examples**

Run:

```bash
./gradlew :examples:java:word-stats:test :examples:java:json-transform:test
```

Expected: PASS, including `processTestAot` tasks.

**Step 7: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/NanofaasRuntimeHints.java \
        function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfig.java \
        function-sdk-java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/NanofaasRuntimeHintsTest.java
git commit -m "Register Java SDK callback runtime hints"
```

---

### Task 6: Update Control-Plane Contract Tests If Needed

**Files:**
- Inspect: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`
- Inspect: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java`
- Test: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java`

**Step 1: Run existing callback/completion tests**

Run:

```bash
./gradlew :control-plane:test --tests '*InvocationControllerTest*' --tests '*ExecutionCompletion*'
```

Expected: PASS. If no matching `ExecutionCompletion` tests exist, Gradle may only run invocation tests.

**Step 2: Add a regression test only if current tests do not cover structured output**

If no test posts a callback payload with object output, add one near existing completion tests:

```java
@Test
void completeExecution_acceptsStructuredJsonObjectOutput() {
    webTestClient.post()
            .uri("/v1/internal/executions/exec-structured:complete")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                    {
                      "success": true,
                      "output": {"wordCount": 4, "topWords": [{"word":"the","count":1}]},
                      "error": null
                    }
                    """)
            .exchange()
            .expectStatus().is2xxSuccessful();
}
```

Adjust URI/class names to match the actual test fixture.

**Step 3: Run the test**

Run:

```bash
./gradlew :control-plane:test --tests '*InvocationControllerTest*'
```

Expected: PASS.

**Step 4: Commit if a test was added**

If no files changed, skip commit.

If a test was added:

```bash
git add control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java
git commit -m "Verify structured callback completion payloads"
```

---

### Task 7: End-To-End Verification With Two-VM Load Test

**Files:**
- No code changes expected.
- Inspect logs on live VMs if available.

**Step 1: Run unit and module regression suite**

Run:

```bash
./gradlew :function-sdk-java:test :examples:java:word-stats:test :examples:java:json-transform:test
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: all PASS.

**Step 2: Run `two-vm-loadtest` from TUI or CLI**

Preferred CLI command if available:

```bash
./scripts/controlplane.sh e2e run two-vm-loadtest --no-cleanup-vm
```

If the CLI command shape differs, use the TUI menu:

```bash
./scripts/controlplane.sh tui
```

Then select:

```text
Main / E2E Scenarios / two-vm-loadtest
```

Expected:

- Step `Run k6 from loadgen VM` passes.
- Step `Capture Prometheus query snapshots` runs.
- Step `Write two-VM loadtest report` runs.

**Step 3: Inspect callback logs**

Run:

```bash
multipass exec nanofaas-e2e -- bash -lc \
  'sudo k3s kubectl logs -n nanofaas-e2e deploy/fn-word-stats-java --since=10m --all-containers=true | grep -Ei "Callback failed|Failed to serialize|All .* callback" || true'
```

Expected: no output.

**Step 4: Inspect k6 summary**

Run:

```bash
multipass exec nanofaas-e2e-loadgen -- bash -lc 'python3 - <<'"'"'PY'"'"'
import json
p="/home/ubuntu/two-vm-loadtest/results/k6-summary.json"
d=json.load(open(p))
print(json.dumps(d["metrics"]["http_req_failed"], indent=2))
print(json.dumps(d["metrics"]["checks"], indent=2))
PY'
```

Expected:

- `http_req_failed.value` below `0.15`
- `checks.value` close to `1.0`

**Step 5: Commit only if verification required code changes**

No commit expected in this task unless a small test/doc correction was necessary.

---

### Task 8: Final Cleanup And Documentation

**Files:**
- Modify: `docs/plans/2026-05-14-java-sdk-callback-json-contract.md` only if execution notes are needed.
- Optional modify: `docs/` runtime SDK documentation if a Java SDK docs page already exists.

**Step 1: Run full relevant checks**

Run:

```bash
./gradlew :function-sdk-java:test :examples:java:word-stats:test :examples:java:json-transform:test
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
```

Expected: all PASS.

**Step 2: Run GitNexus pre-commit scope check**

Run:

```bash
npx gitnexus analyze
```

Then use:

```text
gitnexus_detect_changes(scope: "all")
```

Expected: changed symbols are limited to Java SDK callback/runtime normalization, tests, and optional docs.

**Step 3: Remove accidental generated files**

If `npx gitnexus analyze` modifies `AGENTS.md` or `CLAUDE.md`, restore them unless those files were intentionally changed:

```bash
git restore AGENTS.md CLAUDE.md
```

**Step 4: Final commit**

If docs changed:

```bash
git add docs/path/to/changed-doc.md
git commit -m "Document Java callback JSON contract"
```

**Step 5: Final status**

Run:

```bash
git status --short
git log --oneline -5
```

Expected:

- Working tree clean except known unrelated untracked plan files if still present.
- Latest commits correspond to this plan.

---

## Design Checks

- **Single Responsibility:** `JsonOutputNormalizer` owns output normalization; `CallbackClient` owns transport; `InvokeController` owns request orchestration.
- **Open/Closed:** Future output normalization rules can be added inside `JsonOutputNormalizer` without changing transport.
- **Liskov:** Existing handlers still return `Object`; runtime accepts JSON-compatible values and fails explicitly for non-JSON values.
- **Interface Segregation:** No new user-facing handler interface is required.
- **Dependency Inversion:** Runtime components depend on `ObjectMapper` abstraction, not concrete ad hoc string conversion.
- **DRY:** One normalization path feeds both immediate HTTP response and callback payload.
- **YAGNI:** No broad control-plane queue redesign; this plan only fixes callback JSON correctness.

## Rollback Strategy

If E2E still fails after this plan:

1. Do not reintroduce string fallback.
2. Inspect function pod logs for `OutputSerializationException`.
3. If output normalization fails for a custom class, either make the handler return JSON-compatible `Map`/`List`/primitive/`JsonNode`, or add a separate plan for explicit function output type registration via annotation and runtime hints.

