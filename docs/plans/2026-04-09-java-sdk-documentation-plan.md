# Java Function SDK Documentation Plan

> **For Claude:** Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Bring the Java SDK documentation up to the same clarity level as the Python SDK by documenting the public API, runtime contract, and usage patterns in a way that is consistent with the current Spring Boot-based implementation.

**Architecture:** Keep the Java SDK as the canonical Spring-based runtime SDK. The work should describe the existing entry points rather than redesigning them: `@NanofaasFunction` for handler discovery, `FunctionContext` for request-scoped metadata access, and the runtime package under `it.unimib.datai.nanofaas.sdk.runtime` for invocation, health, metrics, callback dispatch, and cold-start behavior. Documentation should explain the request flow from control-plane headers to handler execution and callback emission.

**Tech Stack:** Markdown docs, Javadoc comments, Spring Boot runtime concepts, JUnit-based examples/tests where needed.

---

## Scope and documentation target

The documentation should cover the same concepts the Python SDK already exposes, adapted to Java idioms:

- handler registration via `@NanofaasFunction`
- execution context access via `FunctionContext`
- runtime startup via Spring Boot auto-configuration
- `/invoke`, `/health`, and `/metrics` runtime endpoints
- callback delivery and error handling behavior
- cold-start headers and trace propagation
- environment variables consumed by the runtime

Non-goals for this pass:

- redesigning the Java SDK API surface
- introducing a separate builder-style runtime
- changing runtime semantics beyond what the docs need to explain
- adding new public features without an implementation request

Assumptions:

- `function-sdk-java` is the primary Java SDK module.
- The existing `function-sdk-java-lite` module is separate and should only be referenced if its behavior is relevant.
- The repo should have a short, user-facing README for `function-sdk-java` if one does not already exist.

### Task 1: Document the public Java SDK surface

**Files:**
- Create or modify: `function-sdk-java/README.md`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/NanofaasFunction.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/FunctionContext.java`

**Step 1: Write the documentation outline**

The README should explain:

- what the SDK is for
- how it differs from `function-sdk-python`
- the handler programming model
- the runtime endpoints it provides
- the environment variables it reads
- a minimal handler example and a minimal Spring Boot app example

**Step 2: Add Javadoc to the public entry points**

Document:

- `@NanofaasFunction` as the handler discovery marker
- `FunctionContext#getExecutionId()` and `FunctionContext#getTraceId()`
- `FunctionContext#getLogger(Class<?>)`

Keep the comments concrete and behavior-focused. Mention MDC-backed metadata and the request-scoped nature of the values.

**Step 3: Verify the docs match the actual package names**

Check that the examples in the README use the current package names under `it.unimib.datai.nanofaas.sdk`.

**Step 4: Commit**

```bash
git add function-sdk-java/README.md function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/NanofaasFunction.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/FunctionContext.java
git commit -m "Document Java function SDK public API"
```

### Task 2: Document the runtime contract and lifecycle

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HealthController.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/MetricsController.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/ColdStartTracker.java`

**Step 1: Document the request flow**

Explain, in code comments or class-level Javadocs, the lifecycle:

- request enters `/invoke`
- runtime resolves execution and trace IDs
- handler is executed with timeout control
- result or error is sent back through the callback dispatcher
- cold-start headers are attached on the first invocation

**Step 2: Document operational endpoints**

Add concise Javadocs that state the purpose of:

- `/health` for readiness/liveness-style checks
- `/metrics` for Prometheus scraping
- callback dispatch behavior and failure handling

**Step 3: Clarify the public runtime semantics**

Document edge cases that users need to understand:

- missing execution ID is a bad request
- handler timeout maps to a 504 response
- handler exceptions map to a 500 response
- callback delivery failure should be visible in logs and tests

**Step 4: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HealthController.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/MetricsController.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/ColdStartTracker.java
git commit -m "Document Java SDK runtime contract"
```

### Task 3: Add examples and cross-links

**Files:**
- Modify: `docs/function-runtime.md`
- Modify: top-level `README.md` or the most relevant SDK overview document

**Step 1: Add a short Java SDK section**

Link the Java SDK docs from the broader repository docs so users can find:

- the Java SDK module
- the runtime API surface
- the example applications under `examples/java/`

**Step 2: Cross-link the Python SDK where useful**

Keep the comparison narrow and practical:

- Python is the lightweight runtime SDK
- Java is the Spring Boot SDK
- both share the same runtime contract concepts

**Step 3: Verify links and examples**

Make sure the docs point to real paths and currently existing examples.

**Step 4: Commit**

```bash
git add docs/function-runtime.md README.md
git commit -m "Cross-link Java SDK documentation"
```

### Task 4: Validation checklist

Before considering the documentation done:

- the README exists and is discoverable
- public SDK classes have useful Javadocs
- runtime behavior is described in terms users can act on
- examples compile against the documented package names
- docs do not promise API behavior that the code does not implement

