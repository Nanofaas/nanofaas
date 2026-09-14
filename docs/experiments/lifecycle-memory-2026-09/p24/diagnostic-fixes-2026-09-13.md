# P24 diagnostic follow-up: cancellation retention

Two independent retention mechanisms were reproduced and corrected in the working
tree. This is a diagnostic result, not closure of P24 or a replacement for the
90-minute paired soak. The original soak images have not been rebuilt with these fixes.

Raw evidence and diagnostic drivers are in `/tmp/nanofaas-p24-diagnostics/` on the
experiment host. Preserve that directory with the campaign archive: it contains heap
dumps, V8 snapshots, JFR recordings, GC logs, NMT output, test logs and k6 summaries.
`fix-identities.txt` records the original and corrected diagnostic jar hashes and
the compiled JavaScript runtime hash.

## JavaScript: a completed handler retained its abort listener

The original Node 20.20.2 function image was used by image ID
`35416ac7df14f76035e11cff88370bfa2e4a4a611ee7987cb33227dd32d6653e`.
It ran with a 1536 MiB Docker limit, two CPUs and a loopback-published inspector.
The corrected run mounted only the rebuilt SDK `runtime.js` into that same image.

`startHandler` attaches a once-only listener to an `AbortSignal.any` composite.
Normal success and handler failure clear the timeout but never abort the signal,
so the listener never removes itself. The V8 snapshot's strong-reference path is:

```text
global -> AbortController context -> gcPersistentSignals
       -> composite EventTarget -> abort Listener -> closure context
       -> timer, controller, promise and invocation payload
```

The original direct load served 24,001 requests without errors or dropped
iterations. Its post-GC snapshot contained 25,655 Timeout objects and 25,637
Listener objects. The snapshot also includes some requests from the subsequent
Java diagnostic; its object counts are not an exact per-request allocation ratio.
The retained population, including copies of the request text, is recorded in
`js-direct-diff.json` and `js-retainer-paths.json`.

The fix removes the runtime's named abort listener when the handler task settles.
It does not synthesize an abort after success or remove listeners belonging to
user handlers. Timeout/cancellation semantics and physical handler ownership stay
unchanged.

The new regression tests failed before the fix with one remaining listener on
both success and failure. After the fix all 82 JavaScript SDK tests passed.
The fixed direct snapshot has three Timeout objects and no Listener objects;
post-GC heapUsed was approximately 6.6 MB, versus 120.5 MB in the original capture.
The fixed callback run served 24,001 requests, with zero HTTP errors, dropped
iterations or callback failures. Its snapshot has five Timeout objects, 37 Promise
objects and no Listener objects. Active handler, retained input/output and pending
callback gauges are zero after the load.

The direct fixed run had three non-200 responses and is not a fully qualified load
comparison. The callback run is the fully successful fixed load. A preliminary
callback experiment whose diagnostic receiver failed to start was stopped and is
excluded; its `js-callback-*` artifacts are retained only for traceability.

## Java: cancelling the future did not remove delayed submission

The control-plane candidate jar was extracted from image `4d9cfd1ba061`.
Diagnostic processes used JDK 25.0.4, SerialGC, `-Xms256m -Xmx512m`, two active
processors, tier-1 compilation, JFR profile recording, GC logs and NMT summary.
They invoked the function through EXTERNAL HTTP. This isolates execution/cache
retention; it does not exercise the managed container proxy or reproduce the
historical soak's unrestricted heap sizing.

JFR identifies the allocation path:

```text
Caffeine Pacer.schedule -> SystemScheduler.schedule
  -> CompletableFuture.delayedExecutor -> DelayScheduler scheduled task
Caffeine Pacer.cancel -> CompletableFuture.cancel -> CancellationException
```

Caffeine's system scheduler returns the CompletableFuture for the eventual work,
not the actual queued delay. Cancelling that future leaves the delayed submission
and cancellation exception reachable until the deadline. With the 30-minute
maxLifetime, repeated brief invocations accumulate this historical state.

After the first load, 20,945 ScheduledForkJoinTask objects and 20,945
CancellationException objects survived both GC and drain. JFR and class histograms
identify those populations; this report does not claim a MAT dominator analysis of
the Java HPROF files.

A subsequent original-code run served 12,000 requests with zero errors or dropped
iterations. ScheduledForkJoinTask count rose from 20,945 to 32,936: another 11,991
retained delayed submissions. The historical baseline `e35405ee` did not configure
the system scheduler. Commit `f38b3922` introduced it to ensure abandoned executions
expire without further traffic; removing expiry outright would restore that defect.

Production now injects one Spring-owned ScheduledThreadPoolExecutor with
remove-on-cancel enabled, via `Scheduler.forScheduledExecutorService`. Cancellation
operates on the actual ScheduledFuture with no interruption of work already running.
The timer still submits maintenance to Caffeine's executor. Spring shuts down and
drains the timer queue. The same scheduler physically evicts expired outcomes during
idle periods. Direct-construction compatibility overloads retain their historical
system scheduler; no production `new ExecutionStore(...)` call was found. The owned
scheduler guarantee applies to the Spring-wired store.

Before the Java change, the physical outcome-expiry regression failed with one
retained outcome; the new scheduler bean was absent. Autonomous abandoned-execution
expiry already passed. After the change, targeted store/lifecycle/architecture/wiring
tests pass, including 10,000 cancelled timers leaving an empty queue and context
shutdown terminating the owner. The minimal `modules=none` profile also passes its
focused expiry and wiring tests.

The corrected repeat served 12,001 requests with zero errors or dropped iterations.
After GC there are no ScheduledForkJoinTask instances or Java CancellationException
instances from this path. An earlier corrected checkpoint after 35 seconds of drain
has zero execution outcomes and in-flight records, with 32.3 MB of live heap versus
34.5 MB before load. The empty DelayScheduler backing array is not a retained task.

The first original Java run had HTTP errors, and the first fixed run dropped seven
generator iterations. They remain useful diagnostic captures, but only the explicitly
identified repeats above are fully successful load runs. No latency-equivalence claim
is made, and the corrected jar must still undergo the long packaged soak.

## Remaining verification issue

The full core suite under `container-deployment-provider,async-queue` ran 828 tests:
two failed and five were skipped. Both failures are in
`ScannedContextDeploymentWiringTest`: a container provider is present but managed
orchestration is absent and immediate readiness is selected. The original,
unmodified candidate jar reproduces the missing orchestration in its condition
report (`original-conditions.log`), so this is not introduced by the expiry fix.
It remains open and prevents claiming the complete profile suite is green.

GitNexus pre-edit impact for `startHandler` was LOW (three affected symbols).
ExecutionStore impact was CRITICAL (27 affected symbols), explicitly reported before
editing. The available index is three commits behind; the post-change diff analysis
does not establish coverage of new untracked classes. Source references, targeted
tests and runtime evidence supplement it. `git diff --check` passed.

Rebuild and freeze both corrected images, resolve the container wiring failure,
verify actual resource limits, and repeat the relevant long soak before closing P24.

## Wiring verification issue resolved (2026-09-13)

This update supersedes the open status of the two wiring failures recorded above.
The initial 828-test run remains historical evidence, not the final result.

`UnmanagedDeploymentDefaultsAutoConfiguration` had default precedence while its
managed predecessor had lowest precedence. The explicit `after` dependency could
pull the managed configuration ahead of an optional provider during dependency
sorting. Its provider condition then failed and the unmanaged fallback was selected.
This was also reproduced with the original, unmodified candidate jar; it was not
introduced by the execution-expiry scheduler fix.

The fallback now also declares `@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)`,
retaining its existing `@AutoConfigureAfter` dependency. This preserves the intended
provider, managed orchestration, unmanaged fallback order without coupling the core
to optional provider class names.

Added `UnmanagedProviderOrderingTest` to reproduce provider registration through
real auto-configuration ordering rather than pre-registering a provider with
`withBean`. Before the fix, this regression and both original
`ScannedContextDeploymentWiringTest` failures were red. After the fix:

- Focused container/async wiring, ownership and execution-expiry tests passed.
- Full control-plane suite with `container-deployment-provider,async-queue`: 829 tests, 0 failures, 0 errors, 5 skipped.
- Focused ordering, scanned-context wiring and minimal-profile ownership tests with `controlPlaneModules=none` passed.
- The same focused tests with `k8s-deployment-provider,async-queue` passed.

Evidence under `/tmp/nanofaas-p24-diagnostics/`: `wiring-red.log`,
`wiring-green.log`, `wiring-core-suite.log`, `wiring-core-suite-counts.json`,
`wiring-none.log`, and `wiring-k8s.log`.

The wiring issue is resolved by these checks. Rebuilding and freezing the changed
images and rerunning the long soak remain necessary before closing P24; unit and
context tests are not a substitute for that memory validation.
