# Retry Backoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Space invocation retries, honor upstream `Retry-After`, and prevent transient admission failures from exhausting all attempts immediately.

**Architecture:** Carry an optional retry instant through `DispatchResult` and compute backoff once in `AttemptCoordinator`. Keep delayed queued attempts in one engine-owned `TreeSet`, using the existing pending store and lifecycle. The direct profile uses its existing bounded executor plus one bounded timer owner; no new dependencies or public API fields.

**Tech Stack:** Java 25, Spring Boot, Reactor/WebClient, JUnit 5, Mockito, AssertJ, existing MockWebServer, Gradle, Helm, NanoLab.

**Spec:** [2026-09-24-retry-backoff-design.md](../specs/2026-09-24-retry-backoff-design.md)

## Global Constraints

- Java 25; 4-space indentation; use the existing `it.unimib.datai.nanofaas` packages in these files.
- Python 3.12 or newer for any measurement script changes.
- Default `maxRetries: 3`: one initial attempt and at most three additional attempts. `maxRetries: 0` means no retry.
- `nanofaas.retry.initial-backoff`: `100ms`; positive finite duration.
- `nanofaas.retry.max-backoff`: `2s`; finite, at least `initial-backoff`.
- The configured cap applies to the locally generated backoff only.
- Promote at most 64 tickets per pass, then allow selection.
- Preserve the existing 10,000-ticket switch cap and internal 50ms rebuild guard.
- No more than two live strategy indexes; five performance repetitions; 5% steady p99/throughput and 10% CPU-per-completion/post-GC heap regression limits.
- Switch pause p99 at most 100ms, maximum 250ms; preparation at most 2000ms; backlog sizes 0/100/1,000/10,000; 1,000-switch soak.
- No record monitor, lifecycle callback, provider request, or lease acquisition runs under the engine gate.
- A caller's waiter timeout ends only that waiter's wait.
- Offloaded invocations retain their existing terminal policy, without local redispatch.
- No new retryability taxonomy, SDK worker tuning, general readiness gate, dynamic configuration API, or per-function backoff fields.
- Use existing stores/listeners, JDK collections and timer primitives. Do not introduce a scheduler framework, generic job manager, or policy interface with one implementation.

## Review Focus

1. A saturated direct executor rejects a job **after** its timer was accepted: finish once with the original failure and release retained input (Tasks 4–5).
2. Administrative expiry races retry publication: a notification before insertion must not leave a delayed orphan until a far-future due time (Tasks 3–5).
3. A clock moves backward after eligibility, or a hint is `Instant.MAX`: neither scheduler dispatches early, overflows, or spins (Tasks 1–4).
4. An HTTP-date contains a comma and the header may have multiple values: do not split a valid date into fake duplicate hints; conflicting hints use local backoff (Task 2).
5. A switch occurs while a ticket becomes due or its promotion throws: preserve one reservation and one eligibility location, including switch rollback (Task 3).

---

## Execution notes and concrete design refinements

This is one feature across the transport, coordinator, and scheduler; splitting it into separately
shippable subsystem plans would leave one admission profile with incompatible retry semantics.
Tasks 1–4 build and test support without activating the policy. Task 5 activates it for every profile.

The source review found three details that the spec leaves implicit:

- **Late refusal needs a callback.** `resetForRetry` clears `lastError`, so the direct adapter cannot
  recover the original failure from the record when its executor later rejects work. Make the retry
  port `boolean enqueue(InvocationTask task, Instant notBefore, Runnable onRejected)`.
  `onRejected` captures the coordinator's existing `PendingRetry`; queued adapters accept it but
  use their existing lifecycle for later queue expiry/removal. This extra parameter implements the
  specified refusal behavior without an outcome registry, new error code, or synthetic attempt.
- **Ready ordering means the existing strategy's add order.** Promotion retains the original ticket
  sequence but calls the existing `SchedulingIndex.add`. It does not change the deque strategies
  to sorted collections. A switch retains its existing sequence-ordered rebuild contract; it may
  consequently change relative order, as it already can today. Eligibility and exactly-once dispatch
  must be invariant across the switch, not identical cross-strategy scheduling order.
- **Worker stop differs from owner disposal.** `SchedulerEngine.close()` remains restartable and
  retains pending work. Add `dispose()` for Spring bean destruction: stop, prohibit new admission,
  remove retained work, and invoke cleanup outside the gate. Do not turn ordinary stop/start into
  cancellation of every queued invocation.

The plan changes only documentation until an execution method is selected. The workspace contains
unrelated staged and unstaged work: never use `git add .`, reset it, or switch its branch in place.

## File map

Paths below are exact; task references to these IDs inherit their full paths.

| ID | Action and path | Responsibility |
| --- | --- | --- |
| POLICY | Create `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/RetryBackoff.java` | Pure, bounded backoff calculation; no Spring or scheduler ownership. |
| POLICY_TEST | Create `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/RetryBackoffTest.java` | Deterministic duration/overflow tests. |
| PROPS | Create `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/RetryProperties.java` | Bind the two startup durations. |
| PROPS_TEST | Create `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/RetryPropertiesTest.java` | Spring binding/default/error tests. |
| RESULT | Modify `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/DispatchResult.java` | Optional transport retry instant. |
| HTTP | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java` | Parse retry hints after the function-envelope branch. |
| HTTP_TEST | Modify `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java` | Header/envelope tests using the existing HTTP fixture. |
| ENGINE | Modify `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/SchedulerEngine.java` | Delayed membership, wake-up, switching, retirement, disposal. |
| STORE | Read `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/PendingWorkStore.java` | Existing authoritative reservations; do not duplicate them. |
| ENGINE_TEST | Create `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulerEngineBackoffTest.java` | Delayed eligibility, expiry, wake-up and disposal. |
| SWITCH_TEST | Modify `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulerEngineSwitchTest.java` | Delayed/ready switch races and rollback. |
| PORT | Modify `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane/service/RetryScheduler.java` | Explicit timing and late rejection contract. |
| QUEUED | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/EngineInvocationEnqueuer.java` | Carry timing while retaining first-admission behavior. |
| SYNC | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/EngineSyncQueueGateway.java` | Carry timing through existing sync admission and queue deadlines. |
| DIRECT | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutorBackedInvocationEnqueuer.java` | Bound and own direct retry timers. |
| DIRECT_CONFIG | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueuerAutoConfiguration.java` | Compose the timer, existing executor and cleanup hooks. |
| DIRECT_TEST | Modify `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutorBackedInvocationEnqueuerTest.java` | Timer/executor rejection and cleanup races. |
| COORD | Modify `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/AttemptCoordinator.java` | Compute timing once and own terminal refusal. |
| COORD_TEST | Modify `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/AttemptCoordinatorTest.java` | Retry timing, identity, late refusal, physical ownership. |
| FACADE | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java` | Inject policy and forward timing/callback through the metered adapter. |
| ENGINE_CONFIG | Modify `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SchedulerConfiguration.java` | Register existing execution-gone notification and engine disposal. |
| E2E_TEST | Create `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/RetryBackoffIntegrationTest.java` | Real adapters, coordinator and strategies, controlled time. |
| YAML | Modify `platform/control-plane/src/main/resources/application.yml` | Document defaults. |
| DOC | Modify `docs/control-plane.md` | Operator semantics and configuration. |
| ADR | Modify `docs/architecture/0002-manual-scheduler-switching.md` | Delayed ownership and lock discipline. |
| HELM_DOC | Modify `deploy/helm/nanofaas/values.yaml` | Show existing `controlPlane.extraEnv` overrides; no redundant chart knobs. |
| API | Modify `openapi/core.yaml` | Retry timing prose only; schema and response envelopes stay compatible. |
| BENCH | Modify `docs/experiments/scheduler-switching-2026-09/SchedulerSwitchBenchmark.java` | Mixed ready/delayed benchmark workload. |
| REPORT | Create `docs/experiments/retry-backoff-2026-09/RESULTS.md` | Reproducible validation and comparison evidence. |

Existing test doubles implementing `RetryScheduler`, including nested `CountingEnqueuer` classes,
need mechanical signature migration in Task 4. Inventory them with current GitNexus context and
compile every selected profile; do not guess a closed list from the stale index.

### Task 0: Establish the execution baseline and resolve graph impact

**Files:** Read the spec, `AGENTS.md`, ADR 0001/0002 and all files above; record evidence in REPORT.

**Interfaces:** Consumes the current checkout; produces an isolated, reproducible implementation
baseline and authoritative upstream impact results. No production code changes.

- [ ] **Step 1: Isolate at execution time using `superpowers:using-git-worktrees`.** Preserve the
  existing dirty workspace. A clean HEAD worktree does not contain its uncommitted changes: record
  that fact and bring only this spec/plan and any demonstrated prerequisites into the isolated
  branch. Never copy every dirty change or call the clean worktree an exact replica of this baseline.

```bash
git status --short
git rev-parse HEAD
git diff --stat
git diff --cached --stat
```

- [ ] **Step 2: Refresh GitNexus and inspect the exact affected symbols.** The previous refresh
  failed, the stored index is 33 commits behind, and class-level scheduler impact was ambiguous
  with a `CRITICAL` candidate. This is a pre-code gate, not a low-risk result.

```bash
node .gitnexus/run.cjs analyze --index-only
node .gitnexus/run.cjs query 'retry publication scheduling notBefore terminal removal' --repo .
node .gitnexus/run.cjs context RetryScheduler --repo .
node .gitnexus/run.cjs impact publishRetry --direction upstream --repo .
node .gitnexus/run.cjs impact track --file platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/SchedulerEngine.java --direction upstream --repo .
```

Use the returned UIDs for ambiguous names. Repeat impact for each symbol before its task edits it;
report callers, flows, and HIGH/CRITICAL risks. UNKNOWN requires source corroboration and resolution
of the graph limitation, not an assumption that an empty caller list means unused. The installed
CLI is available under `/home/michele/.npm/_npx/e46929201c1128dd/node_modules/gitnexus/dist/cli/index.js`
on the author's machine if the wrapper unnecessarily tries to download it; verify availability on
the execution host. Do not repeatedly invoke a failing network bootstrap.

- [ ] **Step 3: Run the focused baseline and record pre-existing failures.**

```bash
./gradlew :execution-runtime:test --tests '*AttemptCoordinatorTest' --tests '*SchedulerEngine*Test'
./gradlew :control-plane:test --tests '*ExternalDispatcher*Test' --tests '*ExecutorBackedInvocationEnqueuerTest' --tests '*ExecutionCompletionRetryPublicationTest'
```

Expected: passing baseline, or a recorded reproducible failure resolved before attributing a new
failure to backoff. Infrastructure failures are not acceptable RED tests.

**Commit rule for every task below:** inspect and stage only the named task files, run
`node .gitnexus/run.cjs detect-changes --scope all --repo .`, resolve incomplete/truncated results,
then commit. Never include unrelated staged files. No task is complete solely because compilation
passes; record the named behavioral tests.

### Task 1: Implement the pure backoff policy and bind durations

**Files:** Create POLICY, POLICY_TEST, PROPS, PROPS_TEST. Production activation belongs to Task 5.

**Interfaces:**

```java
public RetryBackoff(Duration initial, Duration maximum, DoubleSupplier random);
public Instant notBefore(int failedAttempt, Instant observedAt, Instant upstreamHint);
public record RetryProperties(Duration initialBackoff, Duration maxBackoff) {}
```

`failedAttempt` is one-based and equals the next retry ordinal. The caller supplies record time;
the policy does not acquire another clock. `upstreamHint` may be null.

- [ ] **Step 1: Add deterministic RED tests in POLICY_TEST.**

```java
@Test
void honorsHintBeyondLocalCapAndCapsHugeAttemptOrdinals() {
    Instant now = Instant.parse("2026-09-24T12:00:00Z");
    RetryBackoff low = new RetryBackoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.0);
    assertThat(low.notBefore(1, now, null)).isEqualTo(now.plusMillis(50));
    assertThat(low.notBefore(2, now, null)).isEqualTo(now.plusMillis(100));
    assertThat(low.notBefore(3, now, null)).isEqualTo(now.plusMillis(200));
    assertThat(low.notBefore(Integer.MAX_VALUE, now, null)).isEqualTo(now.plusSeconds(1));
    assertThat(low.notBefore(1, now, now.plusSeconds(30))).isEqualTo(now.plusSeconds(30));
    assertThat(low.notBefore(1, Instant.MAX, null)).isEqualTo(Instant.MAX);
}
```

Add parameterized invalid-duration cases `(ZERO, 2s)`, `(-1ns, 2s)`, `(2s, 1s)`;
reject failed attempt 0. Check a 1ns initial duration stays positive, a past hint uses local backoff,
`Math.nextDown(1.0)` stays within the upper bound, and `Duration.ofSeconds(Long.MAX_VALUE)` never
overflows through `toNanos()`. Do not reject valid large positive durations merely for convenience.

- [ ] **Step 2: Run RED.**

```bash
./gradlew :execution-runtime:test --tests '*RetryBackoffTest'
```

Expected: missing policy class/method, then the specific numeric assertions during implementation.

- [ ] **Step 3: Implement bounded exponential growth and overflow-safe jitter.** Use `Duration`
  arithmetic, never an unchecked bit shift by an arbitrary attempt count. Cap doubling before
  multiplying; once at the cap, stop the loop. Compute jitter from the duration half-range in
  seconds plus nanos so a duration larger than `Long.MAX_VALUE` nanoseconds remains valid.

```java
Duration base = initial;
for (int n = 1; n < failedAttempt && base.compareTo(maximum) < 0; n++) {
    base = base.compareTo(maximum.dividedBy(2)) > 0 ? maximum : base.multipliedBy(2);
}
Duration lower = base.dividedBy(2);
Duration width = base.minus(lower);
double sample = random.getAsDouble();
if (!(sample >= 0.0 && sample < 1.0)) throw new IllegalArgumentException("random outside [0,1)");
double seconds = width.getSeconds() * sample;
long whole = (long) seconds;
long nanos = (long) ((seconds - whole) * 1_000_000_000L + width.getNano() * sample);
Duration extra = Duration.ofSeconds(whole, nanos);
if (extra.compareTo(width) > 0) extra = width;
Duration delay = lower.plus(extra);
if (delay.isZero()) delay = Duration.ofNanos(1);
Instant local;
try { local = observedAt.plus(delay); }
catch (java.time.DateTimeException | ArithmeticException overflow) { local = Instant.MAX; }
return upstreamHint != null && upstreamHint.isAfter(local) ? upstreamHint : local;
```

The constructor validates positive durations and their ordering; `notBefore` validates the ordinal
and non-null observed time. Keep all of this in POLICY, not a generic duration utility package.

- [ ] **Step 4: Bind defaults and test Spring binding.** PROPS uses
  `@ConfigurationProperties("nanofaas.retry")` and constructor defaults for absent values.
  Validate through the same policy constructor, avoiding two competing validation rules.

```java
public RetryProperties {
    initialBackoff = initialBackoff == null ? Duration.ofMillis(100) : initialBackoff;
    maxBackoff = maxBackoff == null ? Duration.ofSeconds(2) : maxBackoff;
    new RetryBackoff(initialBackoff, maxBackoff, () -> 0.0);
}
```

Use the existing `ApplicationContextRunner` pattern with a test configuration annotated
`@EnableConfigurationProperties(RetryProperties.class)`. Assert default 100ms/2s, overrides
`250ms`/`3s`, and startup failure for zero/negative/reversed/unparseable values. The application
already has `@ConfigurationPropertiesScan`; no new production configuration class is needed.

- [ ] **Step 5: Run GREEN and commit.**

```bash
./gradlew :execution-runtime:test --tests '*RetryBackoffTest'
./gradlew :control-plane:test --tests '*RetryPropertiesTest'
git diff --check
```

Expected: all policy and binding assertions pass. Commit after the global graph check:
`Add bounded retry backoff policy`.

### Task 2: Preserve upstream retry timing without changing response envelopes

**Files:** Modify RESULT, HTTP, HTTP_TEST.

**Interfaces:**

```java
public record DispatchResult(InvocationResult result, boolean coldStart,
                             Long initDurationMs, Instant retryNotBefore) {
    public DispatchResult(InvocationResult result, boolean coldStart, Long initDurationMs) {
        this(result, coldStart, initDurationMs, null);
    }
    public static DispatchResult warm(InvocationResult result) {
        return new DispatchResult(result, false, null);
    }
}
// Package-private seam in ExternalDispatcher; production constructor uses Clock.systemUTC().
ExternalDispatcher(WebClient client, Clock clock);
static Instant parseRetryAfter(List<String> values, Instant receivedAt);
```

- [ ] **Step 1: Add parser RED tests with fixed time, then execute HTTP_TEST.**

```java
@Test
void retryAfterDatesAndDuplicatesAreUnambiguous() {
    Instant now = Instant.parse("2026-09-24T12:00:00Z");
    assertEquals(now.plusSeconds(1), ExternalDispatcher.parseRetryAfter(List.of("1"), now));
    assertEquals(now.plusSeconds(1), ExternalDispatcher.parseRetryAfter(
            List.of("Thu, 24 Sep 2026 12:00:01 GMT"), now));
    assertEquals(now.plusSeconds(1), ExternalDispatcher.parseRetryAfter(List.of("1", "1"), now));
    assertNull(ExternalDispatcher.parseRetryAfter(List.of("1", "2"), now));
    assertNull(ExternalDispatcher.parseRetryAfter(List.of("1, 2"), now));
    assertNull(ExternalDispatcher.parseRetryAfter(List.of("-1"), now));
    assertEquals(Instant.MAX, ExternalDispatcher.parseRetryAfter(
            List.of("999999999999999999999999999999"), now));
}
```

```bash
./gradlew :control-plane:test --tests '*ExternalDispatcherTest'
```

Expected RED: new method/accessor absent. Add empty, whitespace, zero, `+1`, decimal, invalid date,
past date, and `Instant.MAX` cases. Treat identical trimmed duplicate values as one; reject
conflicting values. Do not comma-split: the canonical HTTP date contains a comma.

- [ ] **Step 2: Implement parsing inside HTTP.** Recognize ASCII digits only; parse with `Long.parseLong`
  and saturate on numeric/instant overflow. Otherwise use the already available Spring
  `HttpHeaders.getFirstDate("Retry-After")` parser on a temporary headers object, which handles HTTP
  date syntax. Invalid values return null; past dates can return the past instant, since POLICY
  takes the maximum. No separate parser library or parser framework.

```java
if (value.chars().allMatch(c -> c >= '0' && c <= '9')) {
    try { return receivedAt.plusSeconds(Long.parseLong(value)); }
    catch (NumberFormatException | java.time.DateTimeException | ArithmeticException overflow) {
        return Instant.MAX;
    }
}
HttpHeaders date = new HttpHeaders();
date.set("Retry-After", value);
try {
    long epochMillis = date.getFirstDate("Retry-After");
    return epochMillis < 0 ? null : Instant.ofEpochMilli(epochMillis);
}
catch (IllegalArgumentException | java.time.DateTimeException invalid) { return null; }
```

Reject an empty trimmed string before the digit test. Tests must verify the date API's supported
formats against the actual installed Spring version, including legacy HTTP-date forms; do not
use a permissive locale-dependent date parser.

- [ ] **Step 3: Capture the hint in the unmarked 429/503 branch.** Read it at header receipt after
  valid function-envelope handling. Preserve it on error-body decode failure using an inner
  `onErrorResume`; outer transport timeout/failure handling remains no-hint.

```java
int status = response.statusCode().value();
Instant retryAt = status == 429 || status == 503
        ? parseRetryAfter(responseHeaders.get("Retry-After"), clock.instant()) : null;
return response.bodyToMono(String.class)
        .defaultIfEmpty(response.statusCode().toString())
        .map(message -> new DispatchResult(InvocationResult.error("EXTERNAL_ERROR", message),
                isCold, initMs, retryAt))
        .onErrorResume(failure -> Mono.just(new DispatchResult(
                InvocationResult.error("EXTERNAL_ERROR", failure.getMessage()), isCold, initMs, retryAt)));
```

- [ ] **Step 4: Add adapter-level cases using the existing MockWebServer test construction.**
  For each row create a fixed-clock dispatcher, enqueue the indicated response, dispatch one
  existing-style `InvocationTask`, and assert the result; include cold-start metadata preservation.

| Response | success | retryNotBefore |
| --- | --- | --- |
| unmarked 429 + `Retry-After: 1` | false | fixed now + 1s |
| unmarked 503 + date | false | parsed date |
| unmarked 500 + `Retry-After: 1` | false | null |
| marked 429 or 503 + `Retry-After: 1` | true | null; public envelope unchanged |
| 429 with oversized/erroring body decoder | false | hint preserved |
| refused connection | false | null |

Minimal added HTTP assertion, using the local `task` constructed by the current test pattern:

```java
server.enqueue(new MockResponse().setResponseCode(429)
        .addHeader("Retry-After", "1").setBody("busy"));
var dispatcher = new ExternalDispatcher(WebClient.create(), Clock.fixed(now, ZoneOffset.UTC));
DispatchResult result = dispatcher.dispatch(task).get(5, TimeUnit.SECONDS);
assertFalse(result.result().success());
assertEquals("EXTERNAL_ERROR", result.result().error().code());
assertEquals(now.plusSeconds(1), result.retryNotBefore());
```

- [ ] **Step 5: Run GREEN and commit.**

```bash
./gradlew :control-plane:test --tests '*ExternalDispatcher*Test'
./gradlew :execution-runtime:test --tests '*AttemptCoordinatorTest'
```

Expected: existing envelope/timeout/cold-start tests and new hints pass. Commit:
`Preserve upstream retry timing in dispatch results`.

### Task 3: Make the engine own delayed eligibility through its full lifecycle

**Files:** Modify ENGINE, ENGINE_CONFIG, SWITCH_TEST; create ENGINE_TEST. Read STORE; preserve
its existing reservation model. Extend existing module strategy tests with real-engine cases:
`platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/PerFunctionSchedulingStrategyTest.java`
and `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/SharedQueueSchedulingStrategyTest.java`.

**Interfaces:** Existing `enqueue`, `tick`, `remove`, `removeAllFor`, `switchTo`, `signal`, `close`;
new `public void removeExecution(String executionId)` and `public void dispose()`.
Private helpers: `indexEligible(SchedulingTicket, Instant)`, `promoteDue(Instant)` returning a
promotion count, and `removeEligibility(SchedulingTicket)`. No new scheduling SPI.

- [ ] **Step 1: Add RED membership test, reusing the mock-engine setup from SchedulerEngineDispatchTest.**

```java
@Test
void futureTicketNeverEntersReadyIndexAtAdmission() {
    SchedulingTicket future = new SchedulingTicket(new TicketId("later", 2), GENERATION,
            1, NOW, NOW.plusSeconds(1), null);
    assertThat(engine.enqueue(new PendingEntry(future, task))).isTrue();
    verify(index, never()).add(future);
    assertThat(store.reservedCount()).isEqualTo(1);
    engine.tick();
    verify(dispatch, never()).submit(any());
}
```

ENGINE_TEST declares the same mock fields and setup shown in the existing test, but replace the
fixed clock with `AtomicReference<Instant>` plus a mocked `Clock.instant()` answer. Its `index.select`
must only return tickets actually recorded by `index.add`, otherwise the fake defeats this test.
Use a local `LinkedHashMap<TicketId, SchedulingTicket>` in the fixture to model membership, not
a production accessor for the delayed set. Assert dispatches and reservations, not private fields.

```bash
./gradlew :execution-runtime:test --tests '*SchedulerEngineBackoffTest'
```

Expected RED: the current engine immediately calls `index.add(future)`.

- [ ] **Step 2: Add the delayed set and route every eligibility mutation through the helpers.**

```java
private final TreeSet<SchedulingTicket> delayed = new TreeSet<>(
        Comparator.comparing(SchedulingTicket::notBefore)
                .thenComparingLong(SchedulingTicket::sequence)
                .thenComparing(t -> t.id().executionId())
                .thenComparingInt(t -> t.id().attempt()));

private void indexEligible(SchedulingTicket ticket, Instant now) {
    if (ticket.notBefore().isAfter(now)) delayed.add(ticket);
    else active.index().add(ticket);
}

private void removeEligibility(SchedulingTicket ticket) {
    if (!delayed.remove(ticket)) active.index().remove(ticket.id());
}

private int promoteDue(Instant now) {
    int promoted = 0;
    while (promoted < 64 && !delayed.isEmpty() && !delayed.first().notBefore().isAfter(now)) {
        SchedulingTicket ticket = delayed.first();
        if (ticket.queueDeadline() != null && !ticket.queueDeadline().isAfter(now)) break;
        active.index().add(ticket);
        delayed.remove(ticket);
        promoted++;
    }
    return promoted;
}
```

These helpers execute under `gate`. Add-before-remove during promotion ensures a thrown add
does not lose the delayed ticket (the existing index add contract must not partially add then
throw). On initial enqueue, roll back the store reservation and eligibility if insertion fails;
the caller still owns rejection cleanup. Keep deadline tracking separate and idempotent.
An expired delayed head left by the 64-entry reap limit is left for the next expiry pass, never
promoted into dispatch. The next-due-deadline check below makes that pass immediate.

Update `enqueue`, `retire`, `reapExpired`, `requeue`, and `abortClaim`. In same-epoch abort, a
backward-clock move can make a previously selected ticket future-dated: remove it from the ready
index and route it into delayed instead of blindly calling `defer`. Preserve claim and submitting
semantics, cancelRequests, physical leases, and all lifecycle calls outside the gate.

- [ ] **Step 3: Integrate promotion and deadline-aware parking.** Reap expired tickets, promote at
  most 64, then select. If a due deadline or due promotion remains, return a zero wait budget;
  otherwise take the minimum of the existing safety interval and the next deadline/due instant.
  Avoid `Duration.toMillis()` overflow for `Instant.MAX`: compare with `now.plusMillis(safety)`
  first and convert only the smaller interval. Round a positive sub-millisecond wait up to 1ms.

```java
private static long boundWait(long safetyMs, Instant now, Instant event) {
    if (!event.isAfter(now)) return 0;
    Instant safetyEnd;
    try { safetyEnd = now.plusMillis(safetyMs); }
    catch (java.time.DateTimeException overflow) { safetyEnd = Instant.MAX; }
    if (!event.isBefore(safetyEnd)) return safetyMs;
    Duration wait = Duration.between(now, event);
    return Math.max(1, wait.toMillis() + (wait.getNano() % 1_000_000 == 0 ? 0 : 1));
}
```

Keep wakeSequence logic intact. Promotion counts as progress but does not signal a new user
admission, increment retry metrics, or reserve again. A backwards wall-clock change after
selection also requires a `notBefore` recheck before commit; release any provisional lease and
requeue instead of dispatching a now-future ticket.

- [ ] **Step 4: Make switching and retirement membership-aware.** Before taking/sorting
  `snapshotPending`, reject if `store.reservedCount() > MAX_SWITCH_REBUILD_TICKETS` to bound the
  snapshot itself. The candidate loop still checks elapsed time and counts all visited pending
  entries; call `candidate.add` only when `!delayed.contains(ticket)`. Do not reclassify delayed
  entries based on a new wall-clock observation inside rebuild. Failed preparation clears only
  the candidate; the active and delayed indexes remain intact.

`removeExecution` scans the bounded store under the gate and removes matching execution IDs,
remembering cancellation for submitting entries, then delivers removal outside the gate like
`removeAllFor`. This scan is terminal cleanup, not a dispatch/idle scan. Register once:

```java
queueLifecycle.onExecutionGone((functionName, executionId) -> engine.removeExecution(executionId));
```

Place the registration in ENGINE_CONFIG composition, supplying `QueueLifecycle` there; its
listener is additive. A publication racing an earlier terminal notification needs post-insertion
record revalidation in Task 4 as well. Use `dispose` as the engine bean's destroy method; leave
`SchedulerLifecycleAdapter.stop()` calling `close()`. Disposal marks a permanent flag under the
gate, refuses admission/start, removes all pending entries, and delivers cleanup outside the gate;
submitting entries retain the existing cancellation-on-requeue discipline.

- [ ] **Step 5: Add the following behavioral cases, with controlled clock advances and barriers.**
  Parameterize removal cases and reuse the existing switch fixture instead of introducing a
  shared test harness package.

```java
// With future enqueued above; clockNow is the fixture's AtomicReference<Instant>.
clockNow.set(NOW.plusMillis(999));
engine.tick();
verify(dispatch, never()).submit(any());
clockNow.set(NOW.plusSeconds(1));
engine.tick();
verify(dispatch, times(1)).submit(any());
assertThat(store.reservedCount()).isZero();
```

| Case | Observable assertion |
| --- | --- |
| delayed first, ready second, same function; repeat other function | second dispatches before first under both real strategies |
| 65 simultaneously due tickets | first pass promotes at most 64 and dispatches one; following passes drain all exactly once |
| deadline equals notBefore | expired callback once, no submit |
| 65 expired delayed tickets at once | bounded expiry passes remove all; none bypasses expiry through promotion |
| future/Instant.MAX plus earlier new insertion | earlier event wakes using sequence; no overflow |
| advance clock backward during tryAcquire | no early submit; lease released; ticket remains reserved |
| remove, removeAllFor, removeExecution repeated | removal callback once and reservations zero |
| close/start versus dispose/start | retained work resumes after close; disposal releases/refuses |
| switch success, refusal, and switch during claim | no duplicate/missing ticket; no early dispatch; unchanged due instant |
| index.add throws during promotion | reservation remains; retry pass can promote after removing the injected failure |

For park ordering use the existing worker-test latch pattern and a bounded completion wait;
verify the calculated wait through package-private `idleBudgetMs()` if needed. Do not assert
correctness by sleeping 1s and seeing whether a dispatch happened.

- [ ] **Step 6: Run GREEN and commit.**

```bash
./gradlew :execution-runtime:test --tests '*SchedulerEngine*Test' --tests '*PendingWorkStoreTest'
./gradlew :control-plane-modules:async-queue:test --tests '*PerFunctionSchedulingStrategyTest'
./gradlew :control-plane-modules:sync-queue:test --tests '*SharedQueueSchedulingStrategyTest'
```

Expected: membership, ownership and existing switch/deadline tests pass. Commit:
`Schedule delayed tickets in the engine`.

### Task 4: Carry timing through every retry adapter, including bounded direct timers

**Files:** Modify PORT, QUEUED, SYNC, DIRECT, DIRECT_CONFIG, DIRECT_TEST, FACADE, COORD;
update discovered retry test doubles. Extend
`platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/EngineSyncQueueGatewaySettlementTest.java`.

**Interfaces:**

```java
@FunctionalInterface
public interface RetryScheduler {
    boolean enqueue(InvocationTask task, Instant notBefore, Runnable onRejected);
    static RetryScheduler unavailable() { return (task, due, rejected) -> false; }
}
// Initial admission remains the existing InvocationEnqueuer.enqueue(InvocationTask).
// Internal sync overload added alongside existing public admission methods:
public boolean enqueue(InvocationTask task, Instant notBefore);
```

`false`/a thrown publication error means the caller owns terminal cleanup; the scheduler must not
also invoke `onRejected`. After `true`, any later executor/capacity refusal invokes `onRejected`
at most once. It may run before `enqueue` returns true, so coordinator revalidation must already
be valid. Terminal removal cancels ownership without overwriting an already terminal result.

- [ ] **Step 1: Add failing queued-adapter tests.** Capture the `PendingEntry` passed into a mocked
  engine. For a future retry assert `ticket.notBefore == due`, `ticket.enqueuedAt == clock.instant`,
  unchanged per-function cap and sync queue deadline, and no fallback into the other profile.

```java
ArgumentCaptor<PendingEntry> admitted = ArgumentCaptor.forClass(PendingEntry.class);
Instant due = now.plusSeconds(1);
assertThat(enqueuer.enqueue(task, due, () -> fail("unexpected rejection"))).isTrue();
verify(engine).enqueue(admitted.capture(), eq(task.functionSpec().queueSize()));
assertThat(admitted.getValue().ticket().notBefore()).isEqualTo(due);
assertThat(admitted.getValue().ticket().enqueuedAt()).isEqualTo(now);
```

Construct `enqueuer`, mocked providers/engine, spec/task and fixed clock using the real constructor
in QUEUED; use the existing SYNC settlement test's setup for sync admission. `enqueue(task)` must
still use the injected admission clock for its due instant. Run those named tests: expected RED
is the missing timed overload or discarded hint.

- [ ] **Step 2: Migrate the port atomically, keeping the coordinator immediate until Task 5.**
  Temporarily pass `executionRecord.now()` as due time, but supply the final rejection callback
  now. The callback invokes the existing exhausted-publication conclusion, not `completeExecution`
  (which would consume another retry). Wire FACADE's wrapper through the existing metric helper:

```java
InvocationEnqueueSupport.publishOrThrow(
        queued -> delegate.enqueue(queued, notBefore, onRejected), metrics, task, false);
```

In COORD, build `Runnable onRejected` around `concludeExhaustedRetry(record, pending)` and
`publishFinalCompletion(record, completion)`; release `pending.task().releaseQueuedInput()` on
that refusal. Reuse existing attempt/terminal revalidation. No pending-result map.

QUEUED/SYNC pass the requested instant into `SchedulingTicket` and retain their existing admission
logic. Add `ExecutionStore` to QUEUED's composition for a record check before and after publication,
outside the engine gate: absent/terminal/wrong-attempt records refuse; a post-insertion terminal
race calls `engine.remove(ticketId)`. A terminal event after the recheck is handled by Task 3's
listener. Use the same check around the SYNC delegation. Do not hold a record monitor while
publishing to the engine.
Compare the record's captured generation with the current active generation as well, so a retry
cannot silently attach to a replacement registration. Keep nullable-generation compatibility
confined to legacy unit-test construction; production retry identity comes from the admitted record.

- [ ] **Step 3: Add a direct timer RED test with a captured timer runnable.** Mock
  `ScheduledExecutorService`, capture `schedule(Runnable,long,TimeUnit)`, return a mocked future,
  and use a mocked `ExecutorService` to capture `execute`. Inject a fixed clock. No timer sleeps.

```java
AtomicInteger rejected = new AtomicInteger();
assertThat(enqueuer.enqueue(task, now.plusSeconds(1), rejected::incrementAndGet)).isTrue();
verify(capacity, never()).tryAcquireLease(anyString(), anyInt());
verify(executor, never()).execute(any());
// Capture schedule's Runnable with ArgumentCaptor<Runnable>, then trigger it at due time.
clockNow.set(now.plusSeconds(1));
timerCommand.getValue().run();
verify(executor).execute(workerCommand.capture());
workerCommand.getValue().run();
verify(dispatch).dispatch(any());
assertThat(rejected).hasValue(0);
```

The fixture creates a real store/record and active generation, stubs lease acquisition, and supplies
the constructor below. A second test makes `executor.execute` throw when the captured timer runs:
assert rejected equals 1, no dispatch, and queued input released once by the supplied rejection
callback. Run DIRECT_TEST: expected RED is the absent timer constructor/timing behavior.

- [ ] **Step 4: Implement direct timing in DIRECT using one private job type.**

```java
ExecutorBackedInvocationEnqueuer(InvocationDispatch dispatch,
        FunctionCapacityRegistry capacity, ExecutorService executor,
        ScheduledExecutorService timer, int maxOutstanding, Clock clock,
        ExecutionStore executions);
```

Use one synchronized owner lock, a `Map<TicketId, RetryJob>`, and a private static `RetryJob`
holding task, due instant, generation, rejection callback, timer future and ownership state.
The map size is the admission bound; do not add a redundant semaphore. Capture the generation
from the live record/active registry and revalidate outside the owner lock before dispatch.
No record monitor, dispatch call, or rejection callback executes while holding the owner lock.

| Transition | Required action |
| --- | --- |
| enqueue -> waiting | reject if closed/duplicate/full/stale; reserve map entry before scheduling |
| waiting -> timer fired | recheck absolute due time; reschedule if clock went backward |
| timer fired -> executor queued | submit without a capacity lease; retain the map reservation |
| worker -> dispatch | recheck due time, record attempt/terminal, generation; acquire lease outside owner lock; transfer ownership once |
| executor full / no capacity after acceptance | detach job, clear references, call onRejected outside lock once |
| synchronous dispatch failure before ownership transfer | release acquired lease and invoke onRejected once |
| terminal/removal/shutdown before transfer | cancel timer, detach payload/callback references, release queued input once; no dispatch |
| concurrent cancellation after lease acquired | release lease unless dispatch ownership already transferred |

The worker rechecks time because a backward jump can occur after timer submission. For enormous
delays, schedule finite chunks using `min(remaining, 1 day)` and recheck the instant at each wake;
do not convert an arbitrary duration to nanos. Timer callbacks only submit/rearm; they never run
the handler. Install a scheduled future under the lock, and cancel it if the job retired before
installation. Timer firing inline or before `schedule` returns must not create double submission.
If a canceled job's executor runnable remains physically queued, clear its task/callback fields
so it retains only a small inert job shell until consumed. Keep an in-progress submission's slot
reserved through executor acceptance/rejection to prevent cancellations racing into unbounded
queued shells. The executor itself stays bounded.

DIRECT_CONFIG composes one timer and the existing executor only when this fallback bean exists:

```java
ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon(true).name("nanofaas-core-retry-timer").factory());
timer.setRemoveOnCancelPolicy(true);
timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
int maxOutstanding = RETRY_POOL_MAX_SIZE + RETRY_POOL_QUEUE_CAPACITY; // 8 + 256 = 264
```

Register the existing `ExecutionStore.onExecutionGone` once to cancel the matching execution's
jobs. Implement `FunctionRegistrationListener` on DIRECT so `onRemove` cancels matching jobs;
re-registration must not give old-generation jobs permission to run. `shutdown()` first prevents
new jobs, then cancels pending jobs/clears references and invokes their rejection callbacks when
still live, then shuts down timer and executor. Never cancel an already transferred physical lease.
Assign queued-input cleanup to one path: live refusal delegates it to `onRejected`; cancellation
of an already terminal record releases it locally. Clear the job's captured callback afterward,
since that callback itself retains `PendingRetry` and the task. Worker submission must first
atomically verify the job is still registered; a duplicated timer runnable cannot enqueue twice.

- [ ] **Step 5: Add rejection, bound and race tests.**

```java
@Test
void lateRejectionDoesNotReenterRetryPolicy() {
    AtomicInteger rejects = new AtomicInteger();
    assertThat(enqueuer.enqueue(task, now.plusSeconds(1), rejects::incrementAndGet)).isTrue();
    doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
    clockNow.set(now.plusSeconds(1));
    timerCommand.getValue().run();
    timerCommand.getValue().run(); // duplicate timer delivery must be inert
    assertThat(rejects).hasValue(1);
    verify(dispatch, never()).dispatch(any());
}
```

Use that fixture for cap 1 (second job refuses, cancellation restores admission), timer scheduling
failure (false, no callback), inline timer firing, capacity refusal, generation replacement,
terminal-before-insert, terminal-after-insert, shutdown while installing the future, backward time,
and far-future hints. Drive due and executor stages separately to prove no lease is held while
waiting. Update old tests that assumed capacity acquisition at enqueue; preserve their rejection
coverage with the new ownership boundary.

- [ ] **Step 6: Run GREEN across the port migration and commit.**

```bash
./gradlew :execution-runtime:test --tests '*AttemptCoordinatorTest'
./gradlew :control-plane:test --tests '*ExecutorBackedInvocationEnqueuerTest' --tests '*EngineSyncQueueGatewaySettlementTest' --tests '*ExecutionCompletion*Test' --tests '*InvocationService*Retry*Test'
./gradlew -PcontrolPlaneModules=none :control-plane:compileTestJava
./gradlew -PcontrolPlaneModules=async-queue,sync-queue :control-plane:compileTestJava
```

Expected: adapters honor injected due instants; all port consumers compile; no production backoff
is activated yet. Commit: `Carry retry timing through queued and direct admission`.

### Task 5: Activate the policy and prove the end-to-end retry lifecycle

**Files:** Modify COORD, COORD_TEST, FACADE; create E2E_TEST. Extend existing
`ExecutionCompletionHandlerTimingTest.java`, `ExecutionCompletionRetryPublicationTest.java`,
`ExecutionCompletionHandlerRetryMetricsRegressionTest.java`, and
`ExecutionCompletionHandlerAdministrativeExpiryTest.java` in
`platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/`.

**Interfaces:** Add `RetryBackoff` to COORD's production constructor; retain the five-argument
constructor delegating to 100ms/2s defaults with `ThreadLocalRandom.current().nextDouble()`.
FACADE's Spring constructor consumes `RetryProperties`; retain existing direct-construction
overloads with default properties. `PendingRetry` gains `Instant notBefore`.

- [ ] **Step 1: Add a coordinator RED test with an actual execution record and clock.**

```java
@Test
void retryHintIsCalculatedOnceAndStaleCompletionCannotReplaceIt() {
    Instant now = Instant.parse("2026-09-24T12:00:00Z");
    var spec = new FunctionSpec("fn", "image", null, null, null,
            30000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
    var task = new InvocationTask("e1", "fn", spec, new InvocationRequest("in", null),
            null, null, now, 1, InvocationKind.SYNC);
    var record = new ExecutionRecord("e1", task, new TimeSource(() -> now, () -> 0L));
    var store = new ExecutionStore();
    store.put(record);
    var dueTimes = new ArrayList<Instant>();
    RetryScheduler retry = (next, due, rejected) -> {
        assertThat(Thread.holdsLock(record)).isFalse();
        assertThat(next.attempt()).isEqualTo(2);
        dueTimes.add(due);
        return true;
    };
    var coordinator = new AttemptCoordinator(store, new FunctionCapacityRegistry(), retry,
            mock(AttemptTransport.class), mock(AttemptObserver.class),
            new RetryBackoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.0));
    record.markRunning();
    coordinator.completeExecution("e1", new DispatchResult(
            InvocationResult.error("EXTERNAL_ERROR", "busy"), false, null, now.plusSeconds(1)), 1);
    coordinator.completeExecution("e1", new DispatchResult(
            InvocationResult.error("EXTERNAL_ERROR", "stale"), false, null, now.plusSeconds(30)), 1);
    assertThat(dueTimes).containsExactly(now.plusSeconds(1));
}
```

```bash
./gradlew :execution-runtime:test --tests '*AttemptCoordinatorTest'
```

Expected RED: missing constructor or immediate time instead of now + 1s.

- [ ] **Step 2: Compute timing before reset, store it in PendingRetry, and publish it once.**

```java
Instant due = backoff.notBefore(currentTask.attempt(), executionRecord.now(),
        dispatchResult.retryNotBefore());
```

Pass `due` through `prepareRetry` into `PendingRetry`. Task 4's publication now uses
`pending.notBefore()` instead of `executionRecord.now()`. Do not recompute on callback races,
enqueue, promotion, requeue or switch. Add a debug log with execution ID, failed/next attempt,
due time, and whether the hint raised the locally selected due time; avoid a second random draw
for logging. The policy can return that diagnostic through a local comparison only if needed;
do not add a public result type just for a log. Logging `hintPresent` is sufficient if it cannot
truthfully distinguish which maximum won.

FACADE constructs the pure policy from PROPS and forwards the new signature through its metered
wrapper. Existing retry decision counters remain before publication. No extra counters on timer
wake or promotion. Task 4's late-rejection callback uses `NO_ATTEMPT` terminal measurement through
the existing exhausted-publication completion path.

- [ ] **Step 3: Add coordinator lifecycle assertions to the same fixture.** Capture the third
  port argument in `AtomicReference<Runnable>`; after a failure, run it twice and assert one
  terminal result with the original error, one retry-decision metric, and no second transport
  submission. Repeat with administrative expiry winning first: expiry stays authoritative.

```java
Runnable refusal = capturedRefusal.get();
refusal.run();
refusal.run();
assertThat(record.completion().join().error().code()).isEqualTo("EXTERNAL_ERROR");
verify(observer, times(1)).retried(any());
verify(transport, never()).submit(any());
```

Use existing observer/transport method signatures from `AttemptObserver`/`AttemptTransport`;
the method above is `submit` on the current transport interface. Add maxRetries 0 and 3 cases,
simultaneous callback/HTTP completion using a barrier, publication false/throw, and the existing
non-cooperative physical-drain assertions with a waiting retry. A timer waiting must not release
the old attempt's still-active physical lease.

- [ ] **Step 4: Build one controlled integration fixture in E2E_TEST.** Compose real HTTP,
  coordinator, metered facade/adapters and either real strategy; for direct admission inject
  captured timer/worker stages as in Task 4. Use a local AtomicReference clock, `TimeSource`,
  `PendingWorkStore`, `FunctionCapacityRegistry`, and a mock `EngineDispatch` delegating submit
  to the real coordinator. Stub lease acquisition with the real registry and explicitly configure
  a second MockWebServer response before releasing the due time. Keep this fixture in this test
  file; no reusable test framework. Optional modules are runtime dependencies of control-plane,
  so instantiate their public strategy classes reflectively in this integration test rather than
  adding compile dependencies to product code:

```java
SchedulingStrategy strategy = (SchedulingStrategy) Class.forName(strategyClassName)
        .getConstructor().newInstance();
```

Use class names `it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy` and
`it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy`. Parameterize only
strategies present in that profile; a no-module run must execute the direct case. Report the
profile/cases actually exercised so missing optional modules cannot turn the whole suite into skips.

```java
server.enqueue(new MockResponse().setResponseCode(429)
        .addHeader("Retry-After", "1").setBody("busy"));
server.enqueue(new MockResponse().setResponseCode(200)
        .addHeader("Content-Type", "application/json").setBody("\"ok\""));
// Dispatch initial task through the composed fixture, then wait on the captured retry publication.
assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeader("X-Dispatch-Attempt")).isEqualTo("1");
assertThat(publishedDue.get()).isEqualTo(now.plusSeconds(1));
clockNow.set(now.plusMillis(999));
engine.tick();
assertThat(server.getRequestCount()).isEqualTo(1);
clockNow.set(now.plusSeconds(1));
engine.tick();
RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS);
assertThat(second.getHeader("X-Dispatch-Attempt")).isEqualTo("2");
assertThat(second.getHeader("X-Execution-Id")).isEqualTo(task.executionId());
assertThat(record.completion().get(5, TimeUnit.SECONDS).success()).isTrue();
```

The captured retry-publication barrier, not `getRequestCount` alone, proves the first response has
been handled before the 999ms assertion. Cover both strategies and direct timing. Add table-driven
cases for connection failure then recovery, persistent failure (four attempts maximum),
function-marked 429 (one successful envelope), sync queue deadline shorter than the hint,
administrative max-lifetime expiry, and short waiter timeout with a later successful replay.
Use existing lifecycle tests' store ticker/expiry setup; never age a record by sleeping.

- [ ] **Step 5: Run GREEN and commit.**

```bash
./gradlew :execution-runtime:test --tests '*RetryBackoffTest' --tests '*AttemptCoordinatorTest' --tests '*SchedulerEngine*Test'
./gradlew :control-plane:test --tests '*RetryBackoffIntegrationTest' --tests '*ExecutionCompletion*Test' --tests '*InvocationService*Retry*Test'
```

Expected: no dispatch during the upstream forbidden interval, successful recovery when budgets
permit, unchanged terminal identity and physical accounting. Commit:
`Apply retry backoff across invocation profiles`.

### Task 6: Document configuration and validate the shipped profiles

**Files:** Modify YAML, DOC, ADR, HELM_DOC, API; update existing
`scripts/tests/test_helm_chart.py` only if chart rendering behavior changes.

**Interfaces:** Startup environment variables `NANOFAAS_RETRY_INITIAL_BACKOFF` and
`NANOFAAS_RETRY_MAX_BACKOFF`; existing `controlPlane.extraEnv` already renders arbitrary entries.

- [ ] **Step 1: Add defaults and operator examples.**

```yaml
nanofaas:
  retry:
    initial-backoff: 100ms
    max-backoff: 2s
```

Merge into the existing `nanofaas` mapping, without a duplicate key. In DOC and HELM_DOC show:

```yaml
controlPlane:
  extraEnv:
    - name: NANOFAAS_RETRY_INITIAL_BACKOFF
      value: "100ms"
    - name: NANOFAAS_RETRY_MAX_BACKOFF
      value: "2s"
```

Keep the chart's default env list as currently defined; add the example as a comment/documented
override, not another copy of the defaults in the deployment template. Document jitter ranges,
the uncapped upstream minimum, queue waiting, direct bound 264, and startup validation. Explain
that a 1s hint can exceed a short caller budget without canceling the shared execution.

- [ ] **Step 2: Update architecture/API prose at its existing retry description.** Exact content:

> Retries use capped exponential backoff with jitter. Unmarked upstream 429 and 503 responses may
> extend that delay through Retry-After. Function-selected status responses are returned as function
> results. Queue deadlines and maximum execution lifetime can end an invocation before another
> attempt is eligible; a caller's waiter timeout does not cancel the shared execution.

ADR additionally describes delayed-set ownership, promotion, disposal versus restart, and the
unchanged record/gate lock order. No new OpenAPI property or response schema is introduced.

- [ ] **Step 3: Run the relevant suites in each actual composition.**

```bash
./gradlew :execution-runtime:test :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test
./gradlew -PcontrolPlaneModules=none :control-plane:test --tests '*Retry*Test' --tests '*ExecutionCompletion*Test'
./gradlew -PcontrolPlaneModules=async-queue :control-plane:test --tests '*Retry*Test' --tests '*ExecutionCompletion*Test'
./gradlew -PcontrolPlaneModules=sync-queue :control-plane:test --tests '*Retry*Test' --tests '*ExecutionCompletion*Test'
./gradlew -PcontrolPlaneModules=async-queue,sync-queue :control-plane:test --tests '*Retry*Test' --tests '*ExecutionCompletion*Test'
./gradlew :control-plane:test --tests '*ExternalDispatcher*Test'
./gradlew :sdks:java:test --tests '*InvokeController*Test'
./gradlew :sdks:java-lite:test --tests '*InvokeHandler*Test'
helm template retry-backoff deploy/helm/nanofaas --set 'controlPlane.extraEnv[0].name=NANOFAAS_RETRY_INITIAL_BACKOFF' --set-string 'controlPlane.extraEnv[0].value=250ms'
```

Verify exactly one `RetryScheduler` bean per composition, no fallback timer in queued profiles,
and no public async admission in the direct profile. If a filtered SDK class pattern matches no
tests, enumerate its actual tests and select the existing envelope test explicitly; never report
a no-tests run as validation. Build the control-plane artifact to validate generated OpenAPI and
configuration processing:

```bash
./gradlew :control-plane:bootJar
git diff --check
```

- [ ] **Step 4: Commit after the graph gate.** Commit:
  `Document retry timing and validate admission profiles`.

### Task 7: Measure scheduler cost and replay the incident through NanoLab

**Files:** Modify BENCH; create REPORT and raw evidence under
`docs/experiments/retry-backoff-2026-09/raw/`. Reuse the existing benchmark runner, summarizer,
budget JSON, and NanoLab lifecycle scenario. Do not add infrastructure provisioning to NanoFaaS.

**Interfaces:** Existing runner CLI plus a new benchmark option `--delayed-percent=0|50|100`
(default 0). Preserve existing output records and add that percentage to run metadata.

- [ ] **Step 1: Add a deterministic mixed-workload correctness check to the benchmark's admission
  construction before collecting timings.** For each backlog size, assign half of tickets a
  future eligibility instant under `--delayed-percent=50`; keep deadlines after their due times.
  The 100% case isolates switch preparation with only delayed work. Retain the original workload
  when the option is zero; changing the baseline silently invalidates comparison.

```java
boolean delayedTicket = (sequence % 100) < delayedPercent;
Instant due = delayedTicket ? enqueuedAt.plusSeconds(1) : enqueuedAt;
SchedulingTicket ticket = new SchedulingTicket(id, generation, sequence, enqueuedAt, due, queueDeadline);
```

Use the benchmark's existing identifiers/clock and add an assertion after controlled promotion:
every admitted ticket is completed or explicitly terminal, none duplicated, zero remaining
reservations after drain. If a benchmark path cannot steer time, keep the correctness assertion
in ENGINE_TEST and measure real waiting separately from useful throughput.

- [ ] **Step 2: Run baseline and candidate on the same quiet host with the same JDK/JVM settings.**
  Record SHAs, dirty state, build classpath, hardware and image/configuration identifiers. Do not
  overwrite the historical campaign's baseline. Use new labels for this campaign:

```bash
docs/experiments/scheduler-switching-2026-09/run.sh --label=retry-backoff-steady --repetitions=5 --delayed-percent=0
docs/experiments/scheduler-switching-2026-09/run.sh --label=retry-backoff-mixed --repetitions=5 --delayed-percent=50
docs/experiments/scheduler-switching-2026-09/run.sh --label=retry-backoff-delayed --repetitions=5 --delayed-percent=100
python3 docs/experiments/scheduler-switching-2026-09/summarize.py docs/experiments/scheduler-switching-2026-09/raw/retry-backoff-steady.jsonl
```

Confirm the runner's current accepted flags before execution and extend only the new percentage
option. The benchmark must include all four backlog sizes and 1,000 switches. Compare normal
success-path latency/throughput/CPU/heap to the unchanged baseline; report intentional retry wait
separately. Enforce every budget in Global Constraints. If a budget fails, profile that concrete
regression rather than relaxing the frozen threshold or adding another scheduler abstraction.

- [ ] **Step 3: Execute live validation in the existing NanoLab checkout/environment.** Follow
  `docs/experiments/scheduler-switching-2026-09/NANOLAB.md` for environment reuse and image selection.
  Run from NanoLab with `NANOFAAS_ROOT` pointing at the implementation checkout:

```bash
nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
```

Use the scenario's existing invocation/load facilities for a 300-call burst at client concurrency
8, once immediately after registration, once after ready endpoints are observed, and once warm.
Record function queue/concurrency/timeout/maxRetries settings and use a waiter budget over 1s;
repeat baseline and candidate with the same configuration. If that external scenario lacks the
burst facility, add its scenario step in the NanoLab repository under its own repository rules,
not a provisioner here. Do not claim the lifecycle scenario alone exercised the burst.

For each invocation retain execution ID, attempt number, dispatch timestamp, outcome and
completion timestamp. Also retain image digest, pod readiness/EndpointSlice timestamps, callback
limits, correlated control-plane/runtime logs, and before/after retry/error/success counter deltas.
Check actual dispatch deltas against upstream hints. Separate connection refusal before endpoints
from callback saturation after readiness. The acceptance criterion is correct delay and recovery
within available budgets, not an unconditional 300/300 promise for arbitrary cold-start duration.

- [ ] **Step 4: Write REPORT with commands, measurements and limitations; run final graph review.**

```bash
node .gitnexus/run.cjs detect-changes --scope all --repo .
node .gitnexus/run.cjs detect-changes --scope compare --base-ref main --repo .
git diff --check
```

The compare-to-main result may include prior work from #208; also review against the recorded
Task 0 baseline to attribute this feature's changes. Truncated/partial graph output is not a pass.
If the NanoLab environment is unavailable, record the exact missing prerequisite and leave this
step unchecked; unit tests cannot substitute for a live-reproduction claim. Commit task files and
bounded evidence only: `Validate retry backoff under saturation and scheduler switching`.

## Coverage and handoff

| Spec sections | Owning tasks |
| --- | --- |
| 1–3: incident, compatibility, scope | 0, 5, 7 |
| 4: policy and defaults | 1, 5, 6 |
| 5: HTTP hints and function envelopes | 2, 5 |
| 6: publication, deadlines, ownership | 3, 4, 5 |
| 7: delayed index and switching | 3, 7 |
| 8: direct timing and bounded retention | 4, 5 |
| 9: metrics and diagnosis | 4, 5, 7 |
| 10: deterministic/live/performance validation | 1–7 |
| 11: graph, documentation and configuration | 0, 6, 7 |

The author must check before handoff: every new signature is defined above; all five Review Focus
items have an owning test; all spec sections map to tasks; no implementation claim is inferred
from writing this plan. Execution begins only after plan review and selection of an execution
method, as required by `superpowers:writing-plans`.
