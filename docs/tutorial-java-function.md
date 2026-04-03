# Tutorial: Writing a Java Function for nanofaas

This tutorial walks you through creating, building, running, and invoking a
Java function on nanofaas from scratch. You will build a simple `greet`
function that returns a personalised greeting.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java (SDKMAN recommended) | 21 |
| Gradle (wrapper included) | — |
| Docker (or compatible runtime) | any recent |
| nanofaas (control plane + runtime running) | — |

Start the platform locally before you begin:

```bash
scripts/control-plane-build.sh run --profile core   # API on http://localhost:8080
./gradlew :function-runtime:bootRun # runtime on http://localhost:8081
```

---

## Step 1 — Understand how Java functions work

A nanofaas Java function is a Spring Boot application that depends on
`function-sdk-java`. The SDK wires up the HTTP runtime automatically.
All you write is a single class that implements `FunctionHandler`:

```java
public interface FunctionHandler {
    Object handle(InvocationRequest request);
}
```

`InvocationRequest` carries two fields:

| Field | Type | Description |
|---|---|---|
| `input` | `Object` | The JSON body sent by the caller (deserialized to `Map`, `List`, `String`, …) |
| `metadata` | `Map<String,String>` | Optional caller-supplied metadata |

Whatever you return from `handle()` is serialized back to the caller as JSON.

---

## Step 2 — Create the project structure

```
examples/java/greet/
├── build.gradle
├── Dockerfile
└── src/
    └── main/
        └── java/
            └── it/unimib/datai/nanofaas/examples/greet/
                ├── GreetApplication.java
                └── GreetHandler.java
```

You can copy the `word-stats` example as a starting point:

```bash
cp -r examples/java/word-stats examples/java/greet
```

Then adapt the files as shown in the steps below.

---

## Step 3 — Write the handler

**`src/main/java/…/greet/GreetHandler.java`**

```java
package it.unimib.datai.nanofaas.examples.greet;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.FunctionContext;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.slf4j.Logger;

import java.util.Map;

@NanofaasFunction                         // (1) marks this class as the handler bean
public class GreetHandler implements FunctionHandler {

    private static final Logger log = FunctionContext.getLogger(GreetHandler.class);

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(InvocationRequest request) {
        log.info("greet invoked, executionId={}", FunctionContext.getExecutionId());

        // (2) cast the generic input to a typed Map
        Map<String, Object> input = (Map<String, Object>) request.input();
        String name = (String) input.getOrDefault("name", "world");

        // (3) return any serialisable value — Map, List, String, …
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
```

Key points:

1. `@NanofaasFunction` is a composite annotation (`@Component` + marker). It
   tells the SDK to register this class as the function handler. Only one
   `@NanofaasFunction` bean may exist per application unless you set the
   `FUNCTION_HANDLER` environment variable to the bean name.
2. `request.input()` is typed as `Object` because nanofaas accepts any valid
   JSON. Cast it to `Map<String,Object>` for object payloads.
3. Return any value that Jackson can serialise — `Map`, `List`, a record, a
   plain `String`, etc.

---

## Step 4 — Write the application entry point

**`src/main/java/…/greet/GreetApplication.java`**

```java
package it.unimib.datai.nanofaas.examples.greet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GreetApplication {
    public static void main(String[] args) {
        SpringApplication.run(GreetApplication.class, args);
    }
}
```

This is standard Spring Boot. The SDK auto-configuration
(`NanofaasAutoConfiguration`) registers the HTTP invoke endpoint and the
`FunctionHandler` resolver automatically.

---

## Step 5 — Configure the build

**`build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot' version "${springBootVersion}"
    id 'io.spring.dependency-management' version "${springDependencyManagementVersion}"
    id 'java'
}

dependencies {
    implementation project(':function-sdk-java')

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}

tasks.named('test') {
    useJUnitPlatform()
}

bootJar {
    archiveFileName = 'greet.jar'
}
```

Register the new subproject in `settings.gradle` (root of the repo):

```groovy
include 'examples:java:greet'
```

---

## Step 6 — Write a unit test

Handler logic is plain Java — test it without starting Spring:

```java
package it.unimib.datai.nanofaas.examples.greet;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetHandlerTest {

    private final GreetHandler handler = new GreetHandler();

    @Test
    void greetWithName() {
        var req = new InvocationRequest(Map.of("name", "Alice"), null);
        var result = (Map<?, ?>) handler.handle(req);
        assertEquals("Hello, Alice!", result.get("greeting"));
    }

    @Test
    void greetDefaultName() {
        var req = new InvocationRequest(Map.of(), null);
        var result = (Map<?, ?>) handler.handle(req);
        assertEquals("Hello, world!", result.get("greeting"));
    }
}
```

Run it:

```bash
./gradlew :examples:java:greet:test
```

---

## Step 7 — Build the JAR

```bash
./gradlew :examples:java:greet:bootJar
# output: examples/java/greet/build/libs/greet.jar
```

---

## Step 8 — Build a container image

**`Dockerfile`** (JVM-only, good for local development):

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/greet.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Build:

```bash
docker build -t nanofaas/greet:local examples/java/greet/
```

> **Tip — production images**: Use Spring Boot Buildpacks for smaller, native
> images without writing a Dockerfile:
>
> ```bash
> ./gradlew :examples:java:greet:bootBuildImage \
>   -PfunctionImage=nanofaas/greet:buildpack
> ```

---

## Step 9 — Test the function in isolation

Run your container:

```bash
docker run --rm -p 8082:8080 nanofaas/greet:local
```

Invoke it directly (bypassing the control plane):

```bash
curl -s -X POST http://localhost:8082/invoke \
  -H 'Content-Type: application/json' \
  -H 'X-Execution-Id: test-001' \
  -d '{"input": {"name": "Alice"}, "metadata": {}}'
```

Expected response:

```json
{"greeting":"Hello, Alice!"}
```

---

## Step 10 — Register and invoke via the control plane

With the control plane running on `http://localhost:8080`, register the
function so it appears in the registry:

```bash
curl -s -X POST http://localhost:8080/v1/functions \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "greet",
    "image": "nanofaas/greet:local",
    "timeoutMs": 5000,
    "concurrency": 2,
    "executionMode": "LOCAL"
  }'
```

> `executionMode: LOCAL` runs the handler in-process inside the control plane
> (useful for local development when Docker networking makes container-to-container
> calls awkward). Switch to `DEPLOYMENT` when running on Kubernetes.

Invoke it:

```bash
curl -s -X POST http://localhost:8080/v1/functions/greet:invoke \
  -H 'Content-Type: application/json' \
  -d '{"input": {"name": "Alice"}}'
```

Expected response:

```json
{"greeting":"Hello, Alice!"}
```

List registered functions to verify:

```bash
curl -s http://localhost:8080/v1/functions | python3 -m json.tool
```

---

## Step 11 — Invoke asynchronously (optional)

If the `async-queue` module is enabled, you can enqueue the call and poll for
the result:

```bash
# Enqueue
EXEC_ID=$(curl -s -X POST http://localhost:8080/v1/functions/greet:enqueue \
  -H 'Content-Type: application/json' \
  -d '{"input": {"name": "Bob"}}' | python3 -c "import sys,json; print(json.load(sys.stdin)['executionId'])")

# Poll result
curl -s "http://localhost:8080/v1/executions/$EXEC_ID"
```

---

## Step 12 — Access execution context inside the handler

`FunctionContext` exposes per-request identifiers injected by the runtime:

```java
// inside handle():
String execId  = FunctionContext.getExecutionId(); // X-Execution-Id header value
String traceId = FunctionContext.getTraceId();     // X-Trace-Id header value (nullable)
Logger log     = FunctionContext.getLogger(GreetHandler.class); // MDC-aware logger
```

These are populated automatically by the SDK's `TraceLoggingFilter`. You do
not need to extract headers yourself.

---

## Step 13 — Error handling

Return a domain error as a plain map with an `"error"` key:

```java
if (name.isBlank()) {
    return Map.of("error", "Field 'name' must not be blank");
}
```

The runtime maps unhandled exceptions to HTTP 500 with body
`{"error": "<message>"}`.  Use `422` semantics for intentional user-facing
errors by throwing a dedicated exception type if you add one; otherwise, the
simple map pattern above is idiomatic in the existing examples.

---

## What's next

- Explore the existing examples in `examples/java/` (`word-stats`,
  `json-transform`) for more realistic handler patterns.
- Deploy to Kubernetes: see `docs/k8s.md` and `docs/quickstart.md`.
- Try the Go SDK: `function-sdk-go/README.md`.
- Run a full E2E load test: `docs/e2e-tutorial.md`.
