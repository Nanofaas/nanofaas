# P24 Minimal Lifecycle Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans` to
> implement this plan task by task.

**Goal:** Add a hierarchical `soak` metrics profile with nine fixed-cardinality
owner gauges, and make NanoLab require only role-applicable populations during a
candidate diagnostic soak.

**Architecture:** The reusable NanoLab workflow remains single-version. Historical
comparison arms use `advanced`; a candidate-only diagnostic run uses `soak`.
NanoFaaS registers the new gauges conditionally and reads existing owner state;
NanoLab replaces its global population union with a fixed role-specific matrix.

**Tech Stack:** Java 25, Spring Boot, Micrometer, JUnit 5, Python, pytest.

**Spec:** `docs/superpowers/specs/2026-09-13-p24-minimal-lifecycle-metrics-design.md`

## Global constraints

- Do not modify or rebuild historical revision `e35405ee`.
- Do not add dynamic metric labels.
- Do not add JavaScript SDK metrics.
- Do not add per-invocation accounting solely for observability.
- `basic < advanced < soak`; `soak` enables all advanced histograms.
- P24 RSS acceptance remains post-drain RSS less than or equal to the same run's
  baseline RSS, with zero positive tolerance.
- Run GitNexus impact before editing every named production symbol.
- Preserve unrelated local modifications in both repositories.
- Do not commit unless the user explicitly authorizes it.

---

### Task 1: Replace NanoLab's global population union

**Repository:** `/home/michele/Documenti/nanolab`

**Files:**

- Modify: `packages/nanolab/src/nanolab/tasks/soak/prerequisites.py`
- Modify: `packages/nanolab/src/nanolab/tasks/soak/prerequisite_runtime.py`
- Modify: `packages/nanolab/tests/soak/test_prerequisites.py`
- Modify: `packages/nanolab/tests/soak/test_prerequisite_runtime.py`

**Interfaces:**

- Produce `_required_populations(images, coverage, metrics_profile)` returning
  exact populations per frozen role and profile.
- Preserve `metric_series` parsing as an optional diagnostic capability.
- Remove physical handler-count evidence from idempotent-replay qualification.

- [ ] **Step 1: Run graph impact before editing**

```bash
node .gitnexus/run.cjs impact "_inputs" --direction upstream --repo .
node .gitnexus/run.cjs impact "LiveProfileSession" --direction upstream --repo .
```

If NanoLab has no usable index, record that result and confirm callers with:

```bash
rg -n "_inputs\(|LiveProfileSession|physical_executions|metric_series" packages/nanolab
```

- [ ] **Step 2: Write failing role-matrix tests**

In `test_prerequisites.py`, first assert that `metrics_profile="advanced"`
requires no candidate-only owner populations. Process RSS, PSS, cgroup, and JVM
metrics remain mandatory in the collector contract and are not duplicated in
prerequisite settlement.

Then replace the fixture that gives every role the same four populations with
this expected contract for `metrics_profile="soak"`:

```python
expected = {
    "control-plane": {
        "execution_records",
        "outcomes",
        "idempotency_entries",
        "logical_executions",
        "canonical_input_bytes",
        "physical_input_copy_bytes",
        "waiters",
        "expiry_queue_depth",
        "pending_acquisitions",
        "replica_snapshots",
    },
    "java": {
        "live_executions",
        "callbacks",
        "callback_bytes",
    },
    "javascript": {
        "live_executions",
        "input_bytes",
        "output_bytes",
        "callbacks",
        "callback_bytes",
        "serialized_callback_bytes",
    },
}
```

Add assertions that `timers`, `pending_http`, `physical_executions`, and
`metric_series` are absent from mandatory settlement. Assert that removing one
required population from its applicable role makes validation inconclusive, while
omitting an inapplicable population remains valid.

- [ ] **Step 3: Verify RED**

```bash
uv run pytest packages/nanolab/tests/soak/test_prerequisites.py -q
```

Expected: the new matrix test fails because `_inputs()` still applies
`_BASE_POPULATIONS` and `_EXTRA_POPULATIONS` to every role.

- [ ] **Step 4: Implement the fixed role matrix**

Replace `_BASE_POPULATIONS` and `_EXTRA_POPULATIONS` with immutable role maps.
Add a helper with this contract:

```python
def _required_populations(
    images: dict[str, str],
    coverage: frozenset[str],
    metrics_profile: str,
) -> dict[str, frozenset[str]]:
    ...
```

The helper must accept only `advanced` and `soak`. For `advanced`, return empty
settlement requirements for every role: historical comparison relies on the
collector's common process metrics and must not depend on candidate-only owner
instrumentation. For `soak`, use the role matrix above and reject unknown role
names rather than silently assigning all populations. Add `idempotency_entries`
only to `control-plane` when replay coverage is selected, and add
`retired_owners` only to `control-plane` when function churn is selected.
Callback populations belong only to SDK roles.

Change `_inputs()` to read the frozen `metrics_profile` and require each role's
exact applicable subset. Additional explicit diagnostic populations remain
valid, but missing applicable `soak` populations fail closed. An `advanced`
historical input remains valid without owner settlement policies; it cannot
silently opt into `soak` based on purpose or coverage.

- [ ] **Step 5: Remove physical execution count from replay qualification**

In `_behavior("idempotent-replay", ...)`, retain same non-empty execution identity
and expected output assertions, but remove `equal("physical_executions", 1)`.

In `LiveProfileSession.preflight()`, remove the unconditional
`physical_executions` lookup. In `_replay()`, remove before/after counter scrapes
and return only execution IDs and outputs. Keep `_selectors()` support and its
semantic validation so the already-built capability is not discarded.

Update runtime tests so replay starts traffic without a handler-start binding and
still requires identical execution IDs. Keep the low-level test proving that
`runtime_invocations_total` cannot be claimed as a physical handler counter.

- [ ] **Step 6: Verify GREEN**

```bash
uv run pytest packages/nanolab/tests/soak/test_prerequisites.py packages/nanolab/tests/soak/test_prerequisite_runtime.py -q
```

Expected: both files pass; `advanced` requires no candidate-only owner metrics,
`soak` requires the exact role matrix, and no test requires global timers,
generic pending HTTP, absolute metric-series zero, or a replay handler-start
counter.

---

### Task 2: Add the control-plane `SOAK` profile

**Repository:** `/home/michele/Documenti/nanofaas`

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/MetricsProfileConfiguration.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/MetricsProfileConfigurationTest.java`

**Interfaces:**

- Accept `nanofaas.metrics.profile=basic|advanced|soak` case-insensitively.
- Enable advanced histogram configuration for both `ADVANCED` and `SOAK`.
- Publish `nanofaas_metrics_profile_info{profile="soak"} 1`.

- [ ] **Step 1: Run graph impact**

```bash
node .gitnexus/run.cjs impact "MetricsProfileConfiguration" --direction upstream --repo .
```

- [ ] **Step 2: Write failing profile tests**

Add tests equivalent to:

```java
assertThat(configuration.metricsProfile("soak"))
        .isEqualTo(MetricsProfileConfiguration.MetricsProfile.SOAK);
assertThat(configuration.metricsProfile("SoAk"))
        .isEqualTo(MetricsProfileConfiguration.MetricsProfile.SOAK);
```

Apply the returned filter to `function_latency_ms` and assert that `SOAK` receives
the same histogram configuration as `ADVANCED`. Register the profile info gauge
in a `SimpleMeterRegistry` and assert its `profile=soak` tag.

- [ ] **Step 3: Verify RED**

```bash
./gradlew :control-plane:test --tests '*MetricsProfileConfigurationTest'
```

Expected: `soak` parsing fails because the enum currently contains only `BASIC`
and `ADVANCED`.

- [ ] **Step 4: Implement the hierarchy**

Add `SOAK` to `MetricsProfile`. Change the error message to list all three values.
Replace the advanced histogram condition with:

```java
profile != MetricsProfile.BASIC
```

Do not classify the ownership gauges in `ADVANCED_METRICS`; their registration is
controlled by the soak-only configuration in Task 3.

- [ ] **Step 5: Verify GREEN**

```bash
./gradlew :control-plane:test --tests '*MetricsProfileConfigurationTest'
```

Expected: all profile parsing, filtering, histogram, and profile-info tests pass.

---

### Task 3: Publish six soak-only control-plane owner gauges

**Repository:** `/home/michele/Documenti/nanofaas`

**Files:**

- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/SoakMetricsConfiguration.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/SoakMetricsConfigurationTest.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/capacity/FunctionCapacityRegistry.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/capacity/FunctionCapacityRegistryTest.java`

**Interfaces:**

- Add `public int retiredGenerationCount()` to `FunctionCapacityRegistry`.
- Register exactly six unlabeled gauges when profile property is `soak`.
- Register none of those gauges for absent, `basic`, or `advanced` profile values.

- [ ] **Step 1: Run graph impact and report risk**

```bash
node .gitnexus/run.cjs impact "FunctionCapacityRegistry" --direction upstream --repo .
node .gitnexus/run.cjs impact "InvocationCapacity" --direction upstream --repo .
node .gitnexus/run.cjs impact "WaiterCapacity" --direction upstream --repo .
```

Stop and report any HIGH or CRITICAL result before editing. Treat UNKNOWN as
unresolved and confirm it with `rg` callers.

- [ ] **Step 2: Write failing retired-generation tests**

Extend `FunctionCapacityRegistryTest` with deterministic cases asserting:

```java
assertThat(registry.retiredGenerationCount()).isZero();
```

Then hold a dispatch lease, remove its function, assert count `1`, close the
lease, and assert count `0`. Add a re-registration case where an old generation
drains while a new generation is active and still produces `1 -> 0`.

- [ ] **Step 3: Write failing registration tests**

In `SoakMetricsConfigurationTest`, create real owner objects and a
`ScheduledThreadPoolExecutor`. Bind the configuration to a
`SimpleMeterRegistry`, mutate each owner, and assert these names report the real
values:

```text
invocation_execution_reservations
invocation_canonical_input_bytes
invocation_physical_input_copy_bytes
execution_waiters_retained
execution_expiry_queue_depth
function_capacity_retired_generations
```

Use separate `ApplicationContextRunner` assertions proving the names are absent
under `basic` and `advanced`, and present under `soak`.

- [ ] **Step 4: Verify RED**

```bash
./gradlew :control-plane:test --tests '*FunctionCapacityRegistryTest' --tests '*SoakMetricsConfigurationTest'
```

Expected: compilation fails because the observer and configuration do not exist.

- [ ] **Step 5: Implement the minimum observers**

Implement `retiredGenerationCount()` as a scrape-time observation of actual
retained registry state: lock each current entry, count an inactive current
generation plus `entry.draining.size()`, unlock, and sum. Do not add invocation
path counters or labels.

Create `SoakMetricsConfiguration` with:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nanofaas.metrics.profile", havingValue = "soak")
class SoakMetricsConfiguration { ... }
```

Register gauges directly against `InvocationCapacity`, `WaiterCapacity`,
`FunctionCapacityRegistry`, and the qualified `executionExpiryExecutor`. The
expiry gauge reads only `executor.getQueue().size()` so it measures the concrete
cancelled-deadline retention previously reproduced.

- [ ] **Step 6: Verify GREEN**

```bash
./gradlew :control-plane:test --tests '*FunctionCapacityRegistryTest' --tests '*SoakMetricsConfigurationTest' --tests '*ExecutionExpiryOwnershipTest'
```

Expected: owner values and conditional registration pass, including expiry queue
return to zero.

---

### Task 4: Publish three soak-only Java SDK owner gauges

**Repository:** `/home/michele/Documenti/nanofaas`

**Files:**

- Create: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/RuntimeSoakMetrics.java`
- Create: `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/RuntimeSoakMetricsTest.java`
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerExecutor.java`
- Modify: `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/HandlerExecutorCancellationTest.java`

**Interfaces:**

- Add package-private `int activeHandlerCount()` to `HandlerExecutor`.
- Register `runtime_active_handlers`, `runtime_pending_callbacks`, and
  `runtime_pending_callback_bytes` only under profile `soak`.

- [ ] **Step 1: Run graph impact**

```bash
node .gitnexus/run.cjs impact "HandlerExecutor" --direction upstream --repo .
node .gitnexus/run.cjs impact "CallbackDispatcher" --direction upstream --repo .
```

Stop on HIGH or CRITICAL and resolve UNKNOWN with text callers.

- [ ] **Step 2: Write failing ownership tests**

Extend the cancellation test with a handler that ignores interruption until a
latch is released. Assert that `activeHandlerCount()` is `1` after the request
times out and only becomes `0` after the physical task exits.

In `RuntimeSoakMetricsTest`, reserve one callback and assert the registry exposes:

```text
runtime_pending_callbacks = 1
runtime_pending_callback_bytes = configured reservation bytes
```

Close the reservation and assert both return to zero. Assert that the three
metric names are absent without `nanofaas.metrics.profile=soak`.

- [ ] **Step 3: Verify RED**

```bash
./gradlew :sdks:java:test --tests '*HandlerExecutorCancellationTest' --tests '*RuntimeSoakMetricsTest'
```

Expected: compilation fails because `activeHandlerCount` and
`RuntimeSoakMetrics` do not exist.

- [ ] **Step 4: Implement without new hot-path accounting**

Store constructor `maxConcurrent` in `HandlerExecutor` and derive:

```java
int activeHandlerCount() {
    return maxConcurrent - admission.availablePermits();
}
```

Create `RuntimeSoakMetrics` as a `@Component` guarded by
`@ConditionalOnProperty(name="nanofaas.metrics.profile", havingValue="soak")`.
Register gauges against the handler and existing package-private callback getters.
Do not change callback admission, handler execution, payload serialization, or
JavaScript code.

- [ ] **Step 5: Verify GREEN**

```bash
./gradlew :sdks:java:test --tests '*HandlerExecutorCancellationTest' --tests '*RuntimeSoakMetricsTest' --tests '*CallbackDispatcherLimitsTest'
```

Expected: all selected tests pass and a timed-out physical handler remains
observable until actual release.

---

### Task 5: Bind the candidate NanoLab soak profile

**Repository:** `/home/michele/Documenti/nanolab`

**Files:**

- Modify: `packages/nanolab/src/nanolab/tasks/soak/prerequisite_runtime.py`
- Modify: `packages/nanolab/src/nanolab/tasks/soak/runtime.py`
- Modify: `packages/nanolab/scenarios-v2/memory-soak-sync-container.yaml`
- Modify: `packages/nanolab/tests/soak/test_prerequisite_runtime.py`
- Modify: the existing soak runtime/configuration test selected by `rg -n "RuntimeOptions|memory-soak-sync-container" packages/nanolab/tests`

**Interfaces:**

- Preserve the `metrics_profile` value already consumed by Task 1 and freeze it
  as `advanced` or `soak` in prerequisite inputs.
- Pass `NANOFAAS_METRICS_PROFILE=soak` to current candidate control-plane and Java
  function containers.
- Bind exact metric names to exact applicable populations.

- [ ] **Step 1: Write failing profile and binding tests**

Assert that the P24 candidate preset selects `soak`, the effective immutable
manifest records `soak`, and the launched container environments receive
`NANOFAAS_METRICS_PROFILE=soak`.

Set `_DEFAULT_METRICS` to the following expected bindings in the test:

```python
{
    "control-plane": {
        "execution_records": ("execution_in_flight_records",),
        "outcomes": ("execution_store_size",),
        "idempotency_entries": ("idempotency_keys_held",),
        "logical_executions": ("invocation_execution_reservations",),
        "canonical_input_bytes": ("invocation_canonical_input_bytes",),
        "physical_input_copy_bytes": ("invocation_physical_input_copy_bytes",),
        "waiters": ("execution_waiters_retained",),
        "expiry_queue_depth": ("execution_expiry_queue_depth",),
        "pending_acquisitions": ("nanofaas_http_pool_pending_acquisitions",),
        "replica_snapshots": ("replica_snapshot_entries",),
        "retired_owners": ("function_capacity_retired_generations",),
    },
    "java": {
        "live_executions": ("runtime_active_handlers",),
        "callbacks": ("runtime_pending_callbacks",),
        "callback_bytes": ("runtime_pending_callback_bytes",),
    },
    "javascript": {
        "live_executions": ("runtime_active_handlers",),
        "input_bytes": ("runtime_input_bytes",),
        "output_bytes": ("runtime_output_bytes",),
        "callbacks": ("runtime_pending_callbacks",),
        "callback_bytes": ("runtime_pending_callback_bytes",),
        "serialized_callback_bytes": ("runtime_serialized_callback_bytes",),
    },
}
```

- [ ] **Step 2: Verify RED**

```bash
uv run pytest packages/nanolab/tests/soak/test_prerequisite_runtime.py -q
```

Expected: bindings and effective profile are absent.

- [ ] **Step 3: Implement candidate-only `soak` selection**

Wire the frozen `metrics_profile` value validated by Task 1 through runtime
preparation and deployment. The current candidate scenario selects `soak`.
Historical invocations explicitly select `advanced`; never infer `soak` from
P24 purpose alone.

Update `_DEFAULT_METRICS` with the exact table above. Require `retired_owners`
only for function-name-churn coverage. Preserve explicit selector overrides.

Wire the selected profile into each launched process that supports it. A historical
control plane using `advanced` must not be asked for any soak-only binding.

- [ ] **Step 4: Verify GREEN**

```bash
uv run pytest packages/nanolab/tests/soak/test_prerequisites.py packages/nanolab/tests/soak/test_prerequisite_runtime.py -q
```

Expected: the candidate profile requires only its role matrix and historical
`advanced` mode does not require soak-only metrics.

---

### Task 6: Update the metric catalog and run final gates

**Repository:** `/home/michele/Documenti/nanofaas`

**Files:**

- Modify: `docs/observability.md`
- Modify: `docs/experiments/lifecycle-memory-2026-09/p24/README.md`

**Interfaces:**

- Document the exact profile hierarchy and all nine metric semantics.
- Separate historical comparison status from candidate soak qualification.

- [ ] **Step 1: Update documentation**

Add a profile table showing `basic`, `advanced`, and `soak`. Add one row for each
new metric with unit, owner, release event, and cardinality `1`. State that these
metrics are absent unless `soak` is selected.

Correct the P24 README so it does not describe the reusable workflow as a
comparison workflow and does not require candidate-only metrics from `e35405ee`.

- [ ] **Step 2: Run focused repository tests**

```bash
./gradlew :control-plane:test :sdks:java:test
```

Expected: both modules pass.

- [ ] **Step 3: Run focused NanoLab tests**

```bash
uv run pytest packages/nanolab/tests/soak -q
```

Expected: all soak tests pass.

- [ ] **Step 4: Analyze graph changes before any authorized commit**

```bash
node .gitnexus/run.cjs detect-changes --scope all --repo .
```

Re-run if output is partial or truncated. Report HIGH or CRITICAL risk rather than
waiving it. Run the equivalent NanoLab command if that checkout has a usable
index; otherwise record the unavailable index and use the focused caller search
from Task 1.

- [ ] **Step 5: Inspect non-test final gates**

```bash
git diff --check
```

Expected: no whitespace errors. Do not build images or launch the long soak in
this implementation plan; those are the next operational step after the code and
workflow gates pass.
