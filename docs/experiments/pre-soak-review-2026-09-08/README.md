**Diagnostic evidence for the pre-soak review, 8 September 2026**

See [the review](../../control-plane-pre-soak-review-2026-09-08.md).

`Audit.java` contains eight diagnostic reproductions. They intentionally assert
the defective or insufficiently bounded behavior of revision `1d9e2f55`.
A successful run confirms the findings; it does **not** certify correctness.
After fixes, replace these checks with ordinary regression tests in the owning
modules, asserting the desired behavior.

The runner recompiles all current common, workload-metrics and control-plane
production sources, plus the specific sync-queue/provider collaborators used by
the harness. Dependency JARs come from a Gradle test classpath. No production
source is patched, no container or service is started, and no network is used
by the Java reproductions. Compiled classes are placed in a temporary directory
and removed on completion.

On the reviewed workspace the previous campaign's classpath file is available:

```bash
python3 docs/experiments/pre-soak-review-2026-09-08/run.py
```

Elsewhere, regenerate the dependency classpath with the repository's toolchain:

```bash
./gradlew -q :control-plane:printTestClasspath > /tmp/nanofaas-review-classpath
python3 docs/experiments/pre-soak-review-2026-09-08/run.py --classpath-file /tmp/nanofaas-review-classpath
```

Recorded environment: OpenJDK 25.0.4, Linux aarch64; Java harness heap cap 256 MiB.
The run output is in `results.txt`.

The backend and offload transport are controllable futures/sinks. The waiter
checks let a short caller time out before explicitly completing the backend;
they do not depend on a race between two sleeps. The archive/key check pauses
a terminal listener after the real archive/live-invalidation operations, before
the key listener, to expose that legal interleaving. The key-budget check
uses a barrier after the real `size()` read, before insertion. These two checks
prove reachable interleavings, not their probability under HTTP load.

The memory check constructs distinct payload strings. `uniquePayloadBytes` is
the sum of retained Latin-1 payload lengths, **not** a heap/RSS measurement.
Provider removal failure is injected through its runtime adapter; a recording
proxy verifies the skipped close operation. No Docker failure was induced.

The eight checks cover:

1. A successful long waiter followed by a timeout replay of the same execution.
2. An offloaded completion ignored after a shorter idempotent waiter times out.
3. Deep/wide payloads escaping the estimated outcome budget.
4. Concurrent claims exceeding `maxKeys`.
5. A replay becoming a new execution during archiving under outcome eviction.
6. Historical names retained by metrics and the replica snapshot after removal/invalidation.
7. Direct core admission exceeding the function's configured concurrency.
8. Failed container deprovision dropping its state without closing its proxy.

The former seventh check (direct dispatch with the sync queue disabled releasing
another dispatch's slot) needed the retired `SyncQueueInvocationEnqueuer`; it was
removed with the retired queues. Its property is covered by
`ExecutionCompletionHandlerTest.directCompletionDoesNotReleaseAnotherDispatchLease`.
