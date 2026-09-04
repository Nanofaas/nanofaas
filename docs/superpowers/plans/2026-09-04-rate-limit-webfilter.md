# Rate-Limit WebFilter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the control plane's rate-limit check out of `InvocationService` and into a `WebFilter` that runs before HTTP body decode, so a rejected request never pays for JSON deserialization or (on `:enqueue`) a `boundedElastic` thread handoff.

**Architecture:** A new `@Component` implementing Spring WebFlux's `WebFilter`, scoped to exactly the two invocation path suffixes (`:invoke`, `:enqueue`) under `/v1/functions/`, consulting the existing `RateLimiter` bean and short-circuiting with a drained-body 429 before the request reaches the controller. The old check (`InvocationService.enforceRateLimit()`, called from `invokeSyncReactive` and `invokeAsync`) and its two `onErrorResume(RateLimitException.class, ...)` handlers in `InvocationController` are then dead and are removed, along with `RateLimitException` itself.

**Tech Stack:** Java 25, Spring WebFlux (reactive, non-blocking), JUnit 5, Mockito, AssertJ, `reactor-test`, Spring's `spring-test` mock web module (`MockServerWebExchange`, `MockServerHttpRequest`).

**Spec:** `docs/plans/2026-09-04-overload-path-fixes.md`, Part I §1 ("Rate limit in un `WebFilter`, prima del body") — the plan argues from that section; read it alongside this plan for the *why* (the 0.06%/0.4%-of-a-core sizing, the connection-keep-alive risk, the 429-vs-400/404 ordering tradeoff).

## Global Constraints

- The filter matches **only** `POST /v1/functions/{name}:invoke` and `POST /v1/functions/{name}:enqueue` — not `/v1/functions/**` broadly. Registration, listing and deletion are not rate-limited today and must not become so as a side effect.
- The filter must drain the request body before completing a 429 response, not leave it unread — an unread body risks reactor-netty closing the connection instead of reusing it in keep-alive (spec, Esperimento C "Rischio da escludere").
- No behavior change to `RateLimiter` itself (`platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/RateLimiter.java`) — only who calls `.allow()`.
- Accept the ordering change as scoped: after this change, under overload, a malformed payload or an unknown function name on a rate-limited request returns 429 instead of 400/404 (the filter now runs before `@Valid` and before the function lookup). This is intrinsic to the change, not a bug to work around.
- 4-space indentation, `com.nanofaas`... — actually this codebase's package root is `it.unimib.datai.nanofaas` (verified in source; `CLAUDE.md`'s `com.nanofaas` is stale for this module) — match the existing package root exactly in every new file.
- Test module: `:control-plane` (Gradle). Run tests with `./gradlew :control-plane:test --tests "<TestClass>"` for a single class, `./gradlew :control-plane:test` for the whole module, `./gradlew test --no-parallel` for everything.

---

### Task 1: `RateLimitWebFilter`

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilter.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilterTest.java`

**Interfaces:**
- Consumes: `RateLimiter.allow(): boolean` (`it.unimib.datai.nanofaas.controlplane.service.RateLimiter`, unchanged, already a `@Component`).
- Produces: `RateLimitWebFilter`, a `@Component` implementing `org.springframework.web.server.WebFilter`, constructor `RateLimitWebFilter(RateLimiter rateLimiter)`. Auto-registered by Spring Boot's component scan (the app's `@SpringBootApplication` root is `it.unimib.datai.nanofaas.controlplane`, which covers `.api`) — no explicit `@Bean` wiring needed. Task 2 depends on this class existing and being wired, but does not call it directly from any other Java code.

- [ ] **Step 1: Write the failing test**

```java
package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitWebFilterTest {

    @Test
    void refusesAnInvokeRequestWithoutCallingTheChain() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body("{}"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void refusesAnEnqueueRequestTheSameWay() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:enqueue").body("{}"));
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void letsTheRequestThroughWhenUnderTheLimit() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo:invoke").body("{}"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    void ignoresPathsOutsideTheTwoInvocationSuffixesEvenWhenTheLimitIsExhausted() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(0);
        RateLimitWebFilter filter = new RateLimitWebFilter(rateLimiter);
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/functions/echo").build());
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests "it.unimib.datai.nanofaas.controlplane.api.RateLimitWebFilterTest"`
Expected: compilation failure — `RateLimitWebFilter` does not exist yet (`cannot find symbol`).

- [ ] **Step 3: Write the filter**

```java
package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects an invocation before its body is read. {@code RateLimiter.allow()} used to
 * run inside {@code InvocationService}, after HTTP decode, JSON deserialization and
 * controller dispatch - every refusal paid for all of that first, exactly when the
 * platform has the least to spend. Scoped to the two invocation suffixes only:
 * registration, listing and deletion were never rate-limited and stay that way here.
 */
@Component
public class RateLimitWebFilter implements WebFilter {

    private final RateLimiter rateLimiter;

    public RateLimitWebFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isInvocationPath(exchange) || rateLimiter.allow()) {
            return chain.filter(exchange);
        }
        // Drain the body instead of leaving it unread: reactor-netty otherwise has no
        // signal that the request is fully consumed, and can close the connection
        // instead of reusing it in keep-alive - a cost that would dwarf what a cheap
        // refusal saves.
        return exchange.getRequest().getBody()
                .doOnNext(DataBufferUtils::release)
                .then(Mono.defer(() -> {
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                }));
    }

    private static boolean isInvocationPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/v1/functions/") && (path.endsWith(":invoke") || path.endsWith(":enqueue"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests "it.unimib.datai.nanofaas.controlplane.api.RateLimitWebFilterTest"`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilter.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilterTest.java
git commit -m "feat: rate-limit invocations in a WebFilter, before body decode"
```

---

### Task 2: Retire the old rate-limit check

The filter from Task 1 now enforces the same `RateLimiter` on every request that reaches the app server. This task removes the now-redundant check from `InvocationService`/`InvocationController`, and follows the compile errors that removal produces through five test files that construct `InvocationService` directly. This is a single task rather than several because Java's compiler does not allow a half-migrated state: removing the constructor parameter and fixing every caller must land together to compile at all.

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java`
- Delete: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/RateLimitException.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/RejectionExceptionsTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceRetryTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceRetryQueueFullTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceEarlyRefusalTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationPathAccountingTest.java`

**Interfaces:**
- Consumes: `RateLimitWebFilter` from Task 1 (indirectly — it is what makes this removal behavior-preserving at the HTTP layer; no direct Java reference).
- Produces: `InvocationService`'s canonical (`@Autowired`) constructor drops from 9 params to 8: `(FunctionService, InvocationEnqueuer, ExecutionStore, Metrics, ExecutionCompletionHandler, InvocationExecutionFactory, InvocationResponseMapper, ReactiveInvocationCoordinator)` — same order as before, `RateLimiter` removed from its position (4th). Its convenience constructor drops from 8 params to 7: `(FunctionService, InvocationEnqueuer, ExecutionStore, IdempotencyStore, Metrics, SyncQueueGateway, ExecutionCompletionHandler)` — `RateLimiter` removed from its position (5th). Nothing outside this task's file list constructs `InvocationService` directly (verified below in Step 1).

- [ ] **Step 1: Confirm the full blast radius before editing**

```bash
grep -rln "new InvocationService(" platform/control-plane/src platform/modules --include=*.java
grep -rln "RateLimitException" --include=*.java platform/
```
Expected: the first command lists exactly the five test files above (plus none in `platform/modules`); the second lists exactly `InvocationService.java`, `InvocationController.java`, `RateLimitException.java`, `InvocationControllerTest.java`, `RejectionExceptionsTest.java`. If either list differs from this, stop and re-scope the task — a new caller has appeared since this plan was written.

- [ ] **Step 2: Remove the check from `InvocationService.java`**

Remove the field (currently line 34):
```java
    private final RateLimiter rateLimiter;
```

In the 7-arg convenience constructor, remove the parameter and the corresponding argument in its `this(...)` delegation:
```java
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler) {
        this(
                functionService,
                enqueuer,
                executionStore,
                rateLimiter,
                metrics,
                completionHandler,
                new InvocationExecutionFactory(executionStore, idempotencyStore, metrics),
                new InvocationResponseMapper(),
                new ReactiveInvocationCoordinator(enqueuer, metrics, syncQueueGateway, null, completionHandler, new InvocationResponseMapper())
        );
    }
```
becomes:
```java
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             IdempotencyStore idempotencyStore,
                             Metrics metrics,
                             @Autowired(required = false) @Nullable SyncQueueGateway syncQueueGateway,
                             ExecutionCompletionHandler completionHandler) {
        this(
                functionService,
                enqueuer,
                executionStore,
                metrics,
                completionHandler,
                new InvocationExecutionFactory(executionStore, idempotencyStore, metrics),
                new InvocationResponseMapper(),
                new ReactiveInvocationCoordinator(enqueuer, metrics, syncQueueGateway, null, completionHandler, new InvocationResponseMapper())
        );
    }
```

In the `@Autowired` canonical constructor, remove the parameter and its field assignment:
```java
    @Autowired
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             RateLimiter rateLimiter,
                             Metrics metrics,
                             ExecutionCompletionHandler completionHandler,
                             InvocationExecutionFactory executionFactory,
                             InvocationResponseMapper responseMapper,
                             ReactiveInvocationCoordinator reactiveCoordinator) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.completionHandler = completionHandler;
        this.executionFactory = executionFactory;
        this.responseMapper = responseMapper;
        this.reactiveCoordinator = reactiveCoordinator;
    }
```
becomes:
```java
    @Autowired
    public InvocationService(FunctionService functionService,
                             @Nullable InvocationEnqueuer enqueuer,
                             ExecutionStore executionStore,
                             Metrics metrics,
                             ExecutionCompletionHandler completionHandler,
                             InvocationExecutionFactory executionFactory,
                             InvocationResponseMapper responseMapper,
                             ReactiveInvocationCoordinator reactiveCoordinator) {
        this.functionService = functionService;
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.executionStore = executionStore;
        this.metrics = metrics;
        this.completionHandler = completionHandler;
        this.executionFactory = executionFactory;
        this.responseMapper = responseMapper;
        this.reactiveCoordinator = reactiveCoordinator;
    }
```

Remove the call site inside `invokeSyncReactive`'s `Mono.fromCallable`:
```java
        Mono<Prepared> prepared = Mono.fromCallable(() -> {
            enforceRateLimit();
            FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
```
becomes:
```java
        Mono<Prepared> prepared = Mono.fromCallable(() -> {
            FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
```

Remove the call site inside `invokeAsync`:
```java
    public InvocationResponse invokeAsync(String functionName,
                                          InvocationRequest request,
                                          String idempotencyKey,
                                          String traceId) {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
```
becomes:
```java
    public InvocationResponse invokeAsync(String functionName,
                                          InvocationRequest request,
                                          String idempotencyKey,
                                          String traceId) {
        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
```

Remove the now-unused private method entirely:
```java
    private void enforceRateLimit() {
        if (!rateLimiter.allow()) {
            throw new RateLimitException();
        }
    }

```

`RateLimiter` and `RateLimitException` are in the same package as `InvocationService` (`it.unimib.datai.nanofaas.controlplane.service`), so there is no import line to remove for either.

- [ ] **Step 3: Remove the dead exception handling from `InvocationController.java`**

Remove the import (currently line 15):
```java
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
```

In `invokeSync`, remove:
```java
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
```
(the surrounding chain — `SyncQueueRejectedException`, `QueueFullException`, `OffloadFailedException` handlers — is unchanged).

In `invokeAsync`, remove:
```java
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
```
(the surrounding chain — `FunctionNotFoundException`, `AsyncQueueUnavailableException`, `QueueFullException` handlers — is unchanged).

- [ ] **Step 4: Delete `RateLimitException.java`**

```bash
git rm platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/RateLimitException.java
```

- [ ] **Step 5: Fix `RejectionExceptionsTest.java`**

Remove the import:
```java
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
```

Remove the now-invalid assertion inside `rejectionsCarryNoStackTrace()`:
```java
        assertThat(new RateLimitException().getStackTrace()).isEmpty();
```
leaving:
```java
    @Test
    void rejectionsCarryNoStackTrace() {
        assertThat(new QueueFullException().getStackTrace()).isEmpty();
        assertThat(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 1).getStackTrace()).isEmpty();
    }
```

- [ ] **Step 6: Fix `InvocationControllerTest.java`**

Remove the import:
```java
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
```

Remove both tests entirely — they exercised a path (`RateLimitException` escaping `InvocationService`) that no longer exists once Step 2 lands:
```java
    @Test
    void invokeSync_rateLimited_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenThrow(new RateLimitException());

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeSync_rateLimitedFromMono_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new RateLimitException()));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

```
(This is a `@WebFluxTest` slice around `InvocationController` alone, with `InvocationService` mocked — it never exercised `RateLimitWebFilter`, so no replacement assertion belongs here. End-to-end 429 coverage through the real filter is `ControlPlaneApiTest.issue006_rateLimitAndRouting`, unchanged by this task — see Step 9.)

- [ ] **Step 7: Fix the five `InvocationService` constructor call sites**

`InvocationServiceRetryTest.java` — remove the field, its initialization, and the constructor argument:
```java
    private RateLimiter rateLimiter;
    private InvocationService invocationService;
```
becomes:
```java
    private InvocationService invocationService;
```
```java
        rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
```
becomes:
```java
        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
```
```java
        invocationService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                rateLimiter,
                metrics,
                syncQueueGateway,
                completionHandler
        );
```
becomes:
```java
        invocationService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                metrics,
                syncQueueGateway,
                completionHandler
        );
```

`InvocationServiceDispatchTest.java` has four call sites. First, the `setUp()` local variable and its use:
```java
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        completionHandler = new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                rateLimiter,
                metrics,
                syncQueueGateway,
                completionHandler
        );
```
becomes:
```java
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();

        completionHandler = new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                idempotencyStore,
                metrics,
                syncQueueGateway,
                completionHandler
        );
```
Then three further inline `new RateLimiter(),` arguments in this same file (locate each with `grep -n "new RateLimiter()" platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java` — three hits after the `setUp()` edit above), each inside its own `new InvocationService(...)` call — delete that one `new RateLimiter(),` line from each:
```java
        InvocationService invocationServiceWithoutSyncQueue = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                new IdempotencyStore(),
                new RateLimiter(),
                metrics,
                null,
                handler
        );
```
becomes:
```java
        InvocationService invocationServiceWithoutSyncQueue = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                new IdempotencyStore(),
                metrics,
                null,
                handler
        );
```
```java
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                staleStore,
                new RateLimiter(),
                metrics,
                syncQueueGateway,
                completionHandler
        );
```
becomes:
```java
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                executionStore,
                staleStore,
                metrics,
                syncQueueGateway,
                completionHandler
        );
```
```java
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                blockedStore,
                staleStore,
                new RateLimiter(),
                metrics,
                syncQueueGateway,
                new ExecutionCompletionHandler(blockedStore, enqueuer, dispatcherRouter, metrics)
        );
```
becomes:
```java
        InvocationService racingService = new InvocationService(
                functionService,
                enqueuer,
                blockedStore,
                staleStore,
                metrics,
                syncQueueGateway,
                new ExecutionCompletionHandler(blockedStore, enqueuer, dispatcherRouter, metrics)
        );
```

`InvocationServiceRetryQueueFullTest.java`:
```java
    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private RateLimiter rateLimiter;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, idempotencyStore,
                rateLimiter, metrics, syncQueueGateway, completionHandler
        );
```
becomes:
```java
    private ExecutionStore executionStore;
    private IdempotencyStore idempotencyStore;
    private InvocationService invocationService;

    @BeforeEach
    void setUp() {
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();

        ExecutionCompletionHandler completionHandler = new ExecutionCompletionHandler(
                executionStore, enqueuer, dispatcherRouter, metrics);

        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, idempotencyStore,
                metrics, syncQueueGateway, completionHandler
        );
```

`InvocationServiceEarlyRefusalTest.java`:
```java
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, idempotencyStore, rateLimiter,
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics));
```
becomes:
```java
        executionStore = new ExecutionStore();
        idempotencyStore = new IdempotencyStore();
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, idempotencyStore,
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics));
```

`InvocationPathAccountingTest.java`:
```java
        registry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(registry);
        ExecutionStore executionStore = new ExecutionStore();
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, new IdempotencyStore(), rateLimiter,
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics));
```
becomes:
```java
        registry = new SimpleMeterRegistry();
        Metrics metrics = new Metrics(registry);
        ExecutionStore executionStore = new ExecutionStore();
        when(enqueuer.enabled()).thenReturn(true);
        when(syncQueueGateway.enabled()).thenReturn(false);
        invocationService = new InvocationService(
                functionService, enqueuer, executionStore, new IdempotencyStore(),
                metrics, syncQueueGateway,
                new ExecutionCompletionHandler(executionStore, enqueuer, dispatcherRouter, metrics));
```

None of these five files import `RateLimiter` explicitly (it is same-package), so there is no import line to remove in any of them.

- [ ] **Step 8: Compile and run the touched module**

Run: `./gradlew :control-plane:compileTestJava`
Expected: BUILD SUCCESSFUL — this confirms every call site was found and fixed; a leftover `RateLimiter`/`RateLimitException` reference fails here first, before any test runs.

Run: `./gradlew :control-plane:test`
Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Step 9: Confirm the end-to-end behavior is unchanged**

`ControlPlaneApiTest.issue006_rateLimitAndRouting` (`platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/ControlPlaneApiTest.java:72-89`) boots a real `RANDOM_PORT` server, drives the real `RateLimiter` bean via `@Autowired`, and asserts a real HTTP 429 through the full stack — it needed no edit in this task, and its continuing to pass is exactly the proof that the filter now provides the guarantee the service used to. Run it explicitly and read the result, don't just trust the full-suite summary:

Run: `./gradlew :control-plane:test --tests "it.unimib.datai.nanofaas.controlplane.ControlPlaneApiTest.issue006_rateLimitAndRouting"`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/RejectionExceptionsTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceRetryTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceRetryQueueFullTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceEarlyRefusalTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationPathAccountingTest.java
git add -u platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/RateLimitException.java
git commit -m "refactor: retire the pre-filter rate-limit check and RateLimitException"
```

---

### Task 3: Full regression and spec status update

**Files:**
- Modify: `docs/plans/2026-09-04-overload-path-fixes.md`

**Interfaces:**
- Consumes: nothing new — this task only runs the existing suite and updates the spec's own status marker for Part I §1.
- Produces: nothing new consumed by later work.

- [ ] **Step 1: Run the whole control-plane module**

Run: `./gradlew :control-plane:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full multi-module suite, matching CLAUDE.md's documented command**

Run: `./gradlew test --no-parallel`
Expected: BUILD SUCCESSFUL. If a module outside `control-plane` fails, check first whether it is a pre-existing failure unrelated to this change (`git stash` and re-run to compare) before treating it as caused by this plan.

- [ ] **Step 3: Update the spec's status for Part I §1**

In `docs/plans/2026-09-04-overload-path-fixes.md`, change:
```markdown
**Stato:** da fare, ma ridimensionato — vedi "Quanto vale" sotto.
```
(under `## 1. Rate limit in un \`WebFilter\`, prima del body`) to:
```markdown
**Stato:** fatto — `RateLimitWebFilter` (`platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilter.java`), scoped a `:invoke`/`:enqueue`, corpo drenato prima del 429. `RateLimitException` e il controllo in `InvocationService` sono stati rimossi. Vedi `docs/superpowers/plans/2026-09-04-rate-limit-webfilter.md`.
```

- [ ] **Step 4: Commit**

```bash
git add docs/plans/2026-09-04-overload-path-fixes.md
git commit -m "docs: mark the WebFilter rate-limit change as done in the overload-path plan"
```
