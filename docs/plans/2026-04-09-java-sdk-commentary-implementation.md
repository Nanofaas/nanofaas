# Java SDK Commentary Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Commentare l'SDK Java in modo sistematico, a livello di componente, così che la lettura del codice risponda alle domande dell'issue #65: chi usa il componente, perché esiste, quali dipendenze ha, quali assunzioni fa sull'ambiente, quando termina il suo ciclo di vita, e quale storia evolutiva riflette.

**Architecture:** Non si cambia il comportamento del runtime. Si aggiungono solo commenti di componente, Javadoc e README che spiegano il flusso reale già implementato in `function-sdk-java`. La priorità è documentare l'architettura Spring Boot runtime-to-callback, il contratto di invocazione, e le dipendenze operative tra `autoconfigure`, `runtime`, e i punti di ingresso pubblici. Ogni commento deve essere utile senza essere tautologico.

**Tech Stack:** Java 21, Spring Boot, Javadoc, Markdown, JUnit 5 per eventuali test di regressione sulla documentazione pubblica.

---

## Scope and commentary target

This plan targets the Java SDK module only:

- `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk`
- `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/autoconfigure`
- `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime`
- `function-sdk-java/README.md`

The commentary should answer the issue's six questions at component level:

- who calls/invokes the component
- why the component exists
- what it depends on
- what environment assumptions it makes
- when it dies / lifecycle boundaries
- what changed historically, if the code now differs from an older approach

Non-goals:

- no behavior changes
- no new public API
- no refactor of runtime flow
- no docs on methods that only restate the method name

Assumptions:

- `function-sdk-java` is the canonical Spring-based SDK.
- `function-sdk-python` already demonstrates the desired level of documentation density.
- `function-sdk-java-lite` is a separate module and only needs cross-reference comments if useful.

### Task 1: Add module-level README guidance for the Java SDK

**Files:**
- Modify: `function-sdk-java/README.md`

**Step 1: Capture the intended reader questions**

Document the module at a high level:

- what problem the SDK solves
- how a function author uses `@NanofaasFunction`
- how the runtime is launched by Spring Boot
- what env vars and headers it expects
- how the request lifecycle ends

**Step 2: Add one concrete handler example**

Use the actual package names and show the minimal Spring Boot app + handler pairing that mirrors the current module layout.

**Step 3: Add an operational section**

Describe the runtime contract in prose:

- `/invoke` executes the handler
- `/health` is for runtime checks
- `/metrics` exists for Prometheus scraping
- callbacks are posted to the control plane

**Step 4: Verify the README matches the code**

Check that every class or package named in the README exists in `function-sdk-java/src/main/java`.

**Step 5: Commit**

```bash
git add function-sdk-java/README.md
git commit -m "Document Java SDK usage and runtime contract"
```

### Task 2: Annotate the public SDK entry points

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/NanofaasFunction.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/FunctionContext.java`

**Step 1: Add component-level Javadoc**

Explain, at class level:

- `NanofaasFunction` exists to mark beans for handler discovery
- it is called by Spring component scanning, not by the user directly
- it depends on Spring's bean lifecycle
- it assumes the handler lives inside a Spring Boot application context

For `FunctionContext`, explain:

- it exists because handlers need request-scoped metadata without plumbing it through every signature
- it depends on runtime-populated MDC entries
- it assumes the runtime installed the trace and execution IDs before user code runs
- it ends with the request scope / thread-local scope, not with application shutdown

**Step 2: Document history where relevant**

If the Java SDK replaced a manual registration or non-Spring approach, say so in the comment. If not, do not invent history.

**Step 3: Keep comments architecture-focused**

Avoid comments like "Returns execution id". Prefer comments that explain why the method exists and what invariants it relies on.

**Step 4: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/NanofaasFunction.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/FunctionContext.java
git commit -m "Document Java SDK public entry points"
```

### Task 3: Document the runtime bootstrap and environment assumptions

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/autoconfigure/NanofaasAutoConfiguration.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/RuntimeSettings.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfig.java`

**Step 1: Add class-level comments to bootstrap classes**

Explain why auto-configuration exists:

- it wires the runtime into a Spring Boot app without explicit boilerplate
- it centralizes env-driven startup config
- it keeps the function author focused on the handler, not runtime plumbing

**Step 2: Document startup dependencies**

Document the environment contract:

- `EXECUTION_ID`
- `TRACE_ID`
- `CALLBACK_URL`
- `FUNCTION_HANDLER`

Explain which of these are required in one-shot mode and which can be injected by the control plane in warm mode.

**Step 3: Document HTTP client assumptions**

Explain why the runtime uses a dedicated HTTP client config:

- callback delivery depends on outbound HTTP
- connection and timeout behavior matter for function latency
- the runtime assumes callback delivery is part of the request lifecycle

**Step 4: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/autoconfigure/NanofaasAutoConfiguration.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/RuntimeSettings.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfig.java
git commit -m "Document Java SDK runtime bootstrap"
```

### Task 4: Document the invocation pipeline

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContextResolver.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerRegistry.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerExecutor.java`

**Step 1: Add a class Javadoc to `InvokeController`**

Describe the full request path:

- the control plane invokes `/invoke`
- the controller resolves execution and trace context
- it rejects missing execution IDs
- it tracks cold-start state
- it dispatches the handler
- it posts the callback result

**Step 2: Comment the resolver and registry**

Explain:

- the resolver exists to normalize header vs env precedence
- the registry exists to locate the single active handler bean
- the executor exists to enforce timeout and exception boundaries

**Step 3: Comment lifecycle assumptions**

Document that these classes assume:

- they run inside Spring MVC / Boot
- the runtime is initialized before the first invoke
- the handler is already discoverable in the application context

**Step 4: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContextResolver.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerRegistry.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerExecutor.java
git commit -m "Document Java SDK invocation pipeline"
```

### Task 5: Document callbacks, cold starts, and trace propagation

**Files:**
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/ColdStartTracker.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/TraceLoggingFilter.java`
- Modify: `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContext.java`

**Step 1: Explain why callbacks exist**

Document that callbacks are the bridge back to the control plane and that they are part of the request contract, not an optional background feature.

**Step 2: Explain cold-start behavior**

Document:

- who marks the first request arrival
- why cold-start tracking exists
- when cold-start metadata is emitted

**Step 3: Explain trace propagation**

Document header/MDC behavior:

- trace IDs are propagated into logging context
- execution IDs are carried alongside them
- the filter exists so logs from downstream code remain correlated

**Step 4: Explain the runtime context object**

Describe `InvocationRuntimeContext` as the normalized per-request bundle that keeps the rest of the pipeline from re-parsing headers or env values.

**Step 5: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackClient.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackDispatcher.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/ColdStartTracker.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/TraceLoggingFilter.java function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContext.java
git commit -m "Document Java SDK callback and tracing behavior"
```

### Task 6: Verify the documentation is complete enough to satisfy issue #65

**Files:**
- Modify: only if verification exposes a missing public component comment

**Step 1: Run a documentation coverage pass**

Check that every public class in `function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk` has a class-level Javadoc or README reference.

**Step 2: Check issue coverage**

Verify the resulting comments answer the issue's checklist:

- who invokes the component
- why it exists
- dependencies
- environment assumptions
- lifecycle
- historical context only where real

**Step 3: Add missing component comments**

If a public runtime component is still undocumented, add a class-level comment rather than a method comment.

**Step 4: Run tests**

Run:

```bash
./gradlew :function-sdk-java:test
```

Expected: PASS. This is a regression check only; the implementation should not change behavior.

**Step 5: Commit**

```bash
git add function-sdk-java/src/main/java/it/unimib/datai/nanofaas/sdk function-sdk-java/README.md
git commit -m "Complete Java SDK architecture commentary"
```

