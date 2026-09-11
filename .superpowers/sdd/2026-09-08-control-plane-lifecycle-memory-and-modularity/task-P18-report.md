# Task P18 report — Java-lite executor/client ownership and shutdown

## Status and revision

- Status: implementation complete; first independent review fixes applied, final re-review pending.
- Base revision: `398387bac07ca04dc421b3d2944ea1e25b0a4d3d`.
- Branch: `control-plane-lifecycle-memory`.
- Scope: Java-lite runtime lifecycle, callback client/executor ownership, the Spring Java SDK
  callback `HttpClient` owner, focused tests, this report and the lifecycle campaign record.
- Preserved: both dirty overload-path files, all untracked `.claude/skills/gitnexus-*`
  directories, and the untracked control-plane replica-status test.

## Implementation

`NanofaasRuntime` now retains the server executor and callback client, owns a finite total
shutdown budget (five seconds by default, configurable with
`Builder.shutdownTimeout(Duration)`), uses an idempotent stop state and a completion latch, and
removes its shutdown hook. Stop first closes admission, then closes the listener, requests and
awaits physical handler cancellation, drains/cancels owned callback work, closes the owned callback
HTTP client, and finally terminates the owned server executor. Concurrent/repeated stop callers
wait on the same completion instead of running cleanup twice. Interrupt state is preserved.

`InvokeHandler` owns the callback executor it creates, but does not close an executor injected
through its test/internal constructor. Handler work is represented by a retained task/thread
handle; cancellation and timeout retain that handle until the virtual thread physically exits.
Its callback worker threads have the bounded-cardinality prefix
`nanofaas-lite-callback-`. Admission after stop begins returns the P16a canonical
`503 RUNTIME_STOPPING` response with `Retry-After: 1`.

`CallbackClient` implements `AutoCloseable`. A client created by its public constructor owns
and boundedly shuts down its JDK `HttpClient`; the existing injected-client constructor keeps
external ownership. Retry count, delays, callback URL construction, request headers and wire
payloads are unchanged.

Spring `HttpClientConfig` now publishes the JDK client as a conditional bean with explicit
`destroyMethod = "close"`, and injects it into the `RestClient` request factory. Therefore the
context closes the default client while an externally supplied `HttpClient` follows that bean's
own destroy contract. `HandlerExecutor` and `CallbackDispatcher` already had `@PreDestroy`
owners and were not changed.

## Ownership table

| Resource | Creation | Owner | Stop/failure path | Injected contract |
|---|---|---|---|---|
| Java-lite `HttpServer` listener/socket | `Builder.build` | `NanofaasRuntime` | listener stopped before executor/client disposal; partial construction stops a created server | not injectable |
| Java-lite server executor | default builder factory | `NanofaasRuntime` | graceful shutdown within shared deadline, then `shutdownNow`; bind/partial failure uses same cleanup | `Builder.serverExecutor` test seam remains open |
| Java-lite handler virtual threads/tasks | each accepted invocation | `InvokeHandler`, lifecycle called by runtime | retained physical handle, interrupt request and bounded drain; non-cooperative work is warned, not falsely reported killed | not injectable |
| Java-lite callback executor | `InvokeHandler` public constructor | `InvokeHandler` | graceful bounded drain, then cancellation | executor passed to injected constructor remains external |
| Java-lite callback JDK client | `CallbackClient` public constructor | `CallbackClient`, retained by runtime | bounded `shutdown` / await / `shutdownNow`; also closed on bind/partial build failure | injected constructor never closes the supplied client |
| Spring callback JDK client | `HttpClientConfig.callbackHttpClient` bean | Spring context | explicit bean destroy method | externally supplied bean suppresses the conditional default; external bean destroy metadata decides ownership |
| Spring handler/callback executors | existing components | existing `HandlerExecutor` / `CallbackDispatcher` | existing `@PreDestroy` methods | unchanged |

## TDD RED → GREEN evidence

1. Blocking `start` caller remained alive after `stop`: one test failed at
   `NanofaasRuntimeOwnershipTest:53`; completion latch made it GREEN.
2. Active handler was not interrupted/physically drained: one test failed at line 60; retained
   handler work made it GREEN.
3. Callback client had no close lifecycle: both ownership tests failed with
   `NoSuchMethodException`; owned-vs-injected close made both GREEN.
4. Bind-failure cleanup had no owned client factory seam: failed with
   `NoSuchMethodException`; builder factory plus failure cleanup made it GREEN.
5. Controlled owned/injected server executors had no ownership seam: both tests failed with
   `NoSuchMethodException`; retained executor ownership made them GREEN.
6. Injected callback executor was incorrectly closed: one assertion failed at
   `InvokeHandlerOwnershipTest:52`; explicit ownership made it GREEN.
7. Active callback worker lacked an attributable thread identity: assertion failed at line 55;
   controlled naming plus bounded drain made it GREEN.
8. Configurable bounded stop seam was absent: test failed with `NoSuchMethodException`;
   one total `Duration` budget made it GREEN.
9. Request after stop returned the generic handler error instead of the P16a stopping outcome:
   assertion failed at line 59; atomic admission fence and exact 503 response made it GREEN.
10. Executor-factory failure leaked the already created callback client: Awaitility timed out;
    common partial-build cleanup made it GREEN.
11. Spring configuration lost the locally created client owner:
    `NoSuchBeanDefinitionException`; an explicit closeable bean made owned and injected tests
    GREEN.
12. Interrupting the blocking `start` caller left the listener/executor alive: Awaitility timed
    out; routing this exit through `stop` made it GREEN while preserving interrupt status.
13. Independent review found that concurrent stop could complete between shutdown-hook registration
    and publication. A deterministic blocking hook registrar was RED before the seam existed;
    lifecycle serialization now guarantees one matching removal and the focused test is GREEN.
14. Independent review found that a failed shutdown-hook registration bypassed cleanup. A
    deterministic rejecting registrar was RED before the seam existed; startup failure now runs
    the normal owned-resource cleanup, preserves the original exception, closes the exact socket,
    client and executor, and the focused test is GREEN.

The acceptance tests use latches, executor state and finite futures. No sleep is used as ordering
proof. The one 50 ms negative await only proves that a deliberately non-cooperative handler has
not falsely been reported as physically exited after bounded stop; its ordering is established by
the handler-start latch and stop future.

## Verification

- Focused P18 classes:
  `./gradlew :sdks:java-lite:test :sdks:java:test --tests '*NanofaasRuntimeOwnershipTest' --tests '*InvokeHandlerOwnershipTest' --tests '*CallbackClientOwnershipTest' --tests '*HttpClientConfigOwnershipTest' --rerun-tasks --no-parallel --console=plain --offline`
  — BUILD SUCCESSFUL, 16 tasks; 18 focused tests (10 + 4 + 2 + 2), zero failures.
- Complete SDK suites:
  `./gradlew :sdks:java-lite:test :sdks:java:test --rerun-tasks --no-parallel --console=plain --offline`
  — initial BUILD SUCCESSFUL; Java-lite 37/37 and Java 94/94, zero failures/errors/skips.
- P16a shared corpus adapters:
  `./gradlew :sdks:java-lite:test :sdks:java:test --tests *SharedSaturationWireCorpusTest --rerun-tasks --no-parallel --console=plain --offline`
  — BUILD SUCCESSFUL, both adapters passed.
- Complete Java-lite/Java artifacts:
  `./gradlew :sdks:java-lite:build :sdks:java:build --rerun-tasks --no-parallel --console=plain --offline`
  — BUILD SUCCESSFUL in 13 s, 18/18 tasks executed.
- `git diff --check` — clean before report generation.

## Thread and socket evidence

`NanofaasRuntimeOwnershipTest` warms each runtime through a real `/health` request. It uses a
test-owned fixed executor with a named thread, proves that the runtime-owned instance terminates,
and proves that an injected instance is not shut down. Three independent build/start/health/stop
cycles verify the named worker is no longer alive and that a new connection to that exact bound
port fails after stop. Callback tests wait for the owned worker to enter a latch, observe executor
shutdown, release it, and prove the named callback thread exits. No assertion counts unrelated JVM
threads or sockets.

## GitNexus

The checkout index was refreshed at the current branch to 20,460 nodes, 57,984 edges, 885 clusters
and 758 flows. Exact upstream impacts:

- `NanofaasRuntime`: LOW, four consumers, one build flow.
- `start`: LOW lower-bound (one resolved test caller; unresolved receiver boundary recorded).
- `stop`: LOW exact, two callers.
- `Builder.build`: LOW lower-bound; text search confirmed the three lite example applications and
  tests omitted by receiver typing.
- `InvokeHandler.shutdownCallbacks`, `invokeWithTimeout`, `newCallbackExecutor`, and
  `handle`: LOW; the handle impact names the invoke flow.
- Java-lite `CallbackClient`: HIGH exact, 58 symbols, four modules, one `build` process. This was
  explicitly audited: production changes add ownership/close only; full callback suites and the
  P16a adapter protect retry, URL, headers and payload behavior.
- Spring `HttpClientConfig.restClient`: UNKNOWN because Spring bean injection has no caller edge.
  Text search resolves its real consumer through `CallbackClient` and all Spring callback tests;
  the full Java suite is GREEN.

Pre-stage all-scope detect-changes completed with 6 indexed files/37 symbols, 8 affected flows and
HIGH aggregate risk. The sixth indexed file is protected overload-path dirt and is excluded from
staging. Final staged detect-changes completed with exactly 10 P18 files/38 symbols, the same 8
affected flows and HIGH aggregate risk. Neither result reported a partial or truncated analysis;
the explicit high risk is retained rather than waived.

## First independent review and fixes

The first independent review reported two Important findings and no Critical/Minor findings.
Both concerned startup-hook ownership. Exact follow-up impacts were LOW for `stop`,
`removeShutdownHook` and the constructor. `start`, `Builder.build` and the lifecycle fields were
UNKNOWN/lower-bound and were resolved with exact text search to the Java-lite runtime, examples
and tests; no HIGH/CRITICAL impact was found. GitNexus was refreshed to 20,547 nodes, 58,269
edges and 766 flows before these edits.

The follow-up focused runtime test is GREEN with 12/12 tests. Fresh complete suites are GREEN
with Java-lite 39/39 and Java 94/94 tests. Both shared P16a Java adapters remain GREEN, and the
fresh artifact build executed 18/18 tasks with `BUILD SUCCESSFUL` in 12 seconds. The complete
pre-stage all-scope change gate reports 6 indexed files/29 symbols, zero affected processes and
LOW risk; this includes protected overload-path dirt and is neither partial nor truncated. The
follow-up staged gate reports exactly 4 P18 files/28 symbols, zero affected processes and LOW
risk, also complete and non-truncated.

The next independent re-review confirmed both original findings closed, then reported two new
Important findings and no Critical/Minor findings. First, the Spring type-wide missing-bean
condition made `RestClient` injection ambiguous when a host application exposed multiple
unrelated `HttpClient` beans. The callback client now has a dedicated conditional bean identity
and matching qualifier; a host can override that exact named bean while unrelated clients neither
suppress nor compete with it. Two-client and named-override tests were RED before this change and
are GREEN afterward. Second, the initial hook-race test ordered only entry into the stop lambda.
It now requires the stop thread to be observably `BLOCKED` on the lifecycle monitor before the
hook registrar is released, so the rejected implementation fails the forced interleaving. The
combined focused verification is GREEN with 16/16 tests (12 Java-lite lifecycle and 4 Spring
client-ownership tests). Fresh complete suites pass Java-lite 39/39 and Spring Java 96/96; both
P16a adapters remain GREEN, and the final artifact build executes 18/18 tasks with `BUILD
SUCCESSFUL` in 13 seconds. A further clean re-review remains required.
The second-fix all-scope GitNexus gate reports 7 files/13 symbols including protected user dirt;
the staged gate reports exactly 5 P18 files/12 symbols. Both report zero affected processes, LOW
risk, and neither is partial nor truncated.

The following independent review verified all four earlier findings closed, then found one
Important wire regression: a user handler throwing `RejectedExecutionException` collided with
the lifecycle admission signal and incorrectly received `503 RUNTIME_STOPPING` instead of the
pre-P18 handler-error path. A focused HTTP test was RED with 503. Lifecycle rejection now uses a
private `RuntimeStoppingException`, while user exceptions again produce callback/error handling
and HTTP 500; the handler and ownership tests are GREEN. No Critical/Minor findings were reported.
Fresh complete suites pass Java-lite 40/40 and Spring Java 96/96; both P16a adapters remain GREEN,
and the artifact build executes 18/18 tasks with `BUILD SUCCESSFUL` in 15 seconds.
The final-fix all-scope GitNexus gate reports 6 files/9 symbols including protected user dirt,
five affected invocation flows and MEDIUM risk. The staged gate reports exactly 4 P18 files/8
symbols with the same five flows and MEDIUM risk. Both are complete and non-truncated; the flow
risk is covered by the new handler-error regression plus the full Java-lite and corpus suites.

## Changed files

- `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntime.java`
- `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/callback/CallbackClient.java`
- `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java`
- `sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntimeOwnershipTest.java`
- `sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/callback/CallbackClientOwnershipTest.java`
- `sdks/java-lite/src/test/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandlerOwnershipTest.java`
- `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfig.java`
- `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/HttpClientConfigOwnershipTest.java`
- `docs/experiments/lifecycle-memory-2026-09/STATO.md`
- this report.

## Concerns / next step

- Java virtual-thread interruption is cooperative. A handler that ignores interruption can outlive
  the stop deadline; it remains retained by the physical-work owner until it exits and produces an
  explicit warning. No hard-kill claim is made.
- P16b still owns callback/input/output count and byte quotas and full runtime execution of the
  shared wire corpus. P18 supplies the Java-lite lifecycle primitives and Spring client owner only.
- A fresh independent re-review of the two first-review fixes is the remaining P18 closure gate.
