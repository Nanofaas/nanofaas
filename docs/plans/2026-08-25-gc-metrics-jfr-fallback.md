# GC Metrics JFR Fallback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Report finite, truthful GC metrics on HotSpot, native Serial, and native G1 without turning unavailable MXBean values into zero or presenting JFR VM operations as collections unless their equivalence is demonstrated.

**Architecture:** Keep MXBeans as the primary source and fix their Micrometer lifetime with one strongly held collector list. Use JFR `jdk.ExecuteVMOperation` only when MXBeans are unusable and only map verified one-to-one GC events to the existing metrics; otherwise expose the observed VM-operation data under separate names. All time fractions use the same observation window, starting when the selected source starts.

**Tech Stack:** Java 25, Spring Boot, Micrometer, JFR `RecordingStream`, GraalVM Native Image, JUnit 5, Gradle.

---

## Metric contract and decision gate

Preserve these existing Micrometer names and the `gc` tag:

- `jvm_gc_collection_count{gc=...}`: cumulative collections observed since the metric source started;
- `jvm_gc_collection_time{gc=...}`: cumulative collection seconds observed since the metric source started;
- `jvm_gc_time_fraction`: observed collection time divided by elapsed time since the metric source started.

Do not add a `source` tag to those metrics. Publish the active source separately as a one-valued gauge:

- `nanofaas_gc_metrics_source{source="mxbean|jfr|unavailable"} 1`.

For JFR, the existing `jvm_gc_*` metrics may be emitted only if Task 3 proves that the selected events represent non-overlapping GC collections with durations matching the pauses. If that proof fails, publish only truthful observations:

- `nanofaas_jfr_vm_operation_count{operation=...}`;
- `nanofaas_jfr_vm_operation_time{operation=...}` in seconds.

In that case set `nanofaas_gc_metrics_source{source="unavailable"} 1`; do not claim that the JVM GC collection counters are available.

---

### Task 1: Confirm impact and the native G1 runtime facts

**Files:**

- Inspect: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfiguration.java`
- Inspect: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfigurationTest.java`
- Inspect: `build.gradle`
- Inspect: `scripts/native-java-image.sh`

**Step 1: Run the required GitNexus analysis**

Check index freshness. Run `gitnexus_context` and upstream `gitnexus_impact` for every existing symbol that will be edited, including `GcMetricsConfiguration`, `gcCollectionMetrics`, `gcOverheadMetric`, and `value`. Report direct callers and risk. Stop before editing on HIGH or CRITICAL impact.

**Step 2: Capture the actual MXBean state on Oracle GraalVM G1**

Build with:

```bash
NATIVE_GC=G1 NATIVE_MONITORING=jfr ./scripts/native-java-image.sh control-plane
```

At startup record, for every `GarbageCollectorMXBean`, its name, collection count, and collection time. Resolve the current documentation conflict between “no MXBean” and “MXBean returns `-1`”. Keep the output as test evidence, not as a committed generated artifact.

**Step 3: Define source selection once at startup**

Select MXBean only when at least one collector reports both count and time as non-negative. Otherwise select JFR if available; otherwise select unavailable. Do not switch source while the process is running, because doing so would reset cumulative metrics.

**Step 4: Verify the native build contract**

Confirm that `-PnativeMonitoring=jfr` produces `--enable-monitoring=jfr` and that G1 selects Oracle GraalVM. Record the exact GraalVM version used by the acceptance test.

---

### Task 2: Fix MXBean metrics with the minimum code

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfiguration.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfigurationTest.java`

**Step 1: Write failing tests**

Add tests with fake MXBeans that verify:

- `-1` does not become `0.0`;
- collectors without both usable values are not registered as valid MXBean counters;
- the fraction uses `(current collection time - initial collection time) / elapsed observation time`;
- the gauge remains finite because its target is the Spring-owned configuration object, not a temporary list.

Use a package-private constructor accepting the collector list and time supplier only if required for deterministic tests. Do not create a general source abstraction.

**Step 2: Verify the tests fail**

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.config.GcMetricsConfigurationTest'
```

Expected: failures expose the current `-1 -> 0` conversion and old uptime calculation.

**Step 3: Implement the strong reference and observation baseline**

Store `List.copyOf(ManagementFactory.getGarbageCollectorMXBeans())` in a final field. Register `jvm_gc_time_fraction` against the configuration object, which Spring retains strongly. Record initial count/time and a monotonic start time; subtract those baselines when publishing values.

Remove `value(long)`. Do not add atomics on the MXBean path: MXBeans already own their counters.

**Step 4: Publish the explicit source metric**

Publish `nanofaas_gc_metrics_source{source="mxbean"} 1` only when the startup usability check succeeds. Otherwise defer registration to the selected JFR or unavailable path.

**Step 5: Verify the focused tests pass**

Run the command from Step 2. Expected: `BUILD SUCCESSFUL` and no `NaN` or false zero.

---

### Task 3: Prove or reject the JFR-to-GC mapping

**Files:**

- Inspect: `docs/performance/concurrency-and-runtime-analysis.md`
- Modify: `docs/performance/concurrency-and-runtime-analysis.md` with the result

**Step 1: Record native G1 under controlled allocation load**

Enable only `jdk.ExecuteVMOperation` in a short JFR recording. Capture each candidate event's operation name, start time, end time, and duration for:

- `G1 wrapper`;
- `Collect for allocation`;
- `Try init concurrent mark`.

Use the runtime's supported GC diagnostic output, when available, as the independent reference. Also inspect event intervals directly for nesting or overlap.

**Step 2: Apply the acceptance gate**

The mapping passes only if the evidence shows all of the following:

- each selected event corresponds to one completed GC collection or pause with defined semantics;
- no selected intervals overlap or double-count the same pause;
- summed durations match the independent GC pause total within documented timestamp precision;
- operation names are stable in two fresh runs on the supported GraalVM version.

**Step 3: Choose the metric path from evidence**

- If the gate passes, document the exact accepted operation set and permit Task 5 to populate `jvm_gc_*{gc="G1"}`.
- If the gate fails, do not map these events to `jvm_gc_collection_*`; Task 5 must use the separate `nanofaas_jfr_vm_operation_*` metrics.

This decision is part of the test result, not an implementation assumption.

---

### Task 4: Add deterministic JFR aggregation tests

**Files:**

- Create if needed: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/JfrGcMetrics.java`
- Create if needed: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/JfrGcMetricsTest.java`

**Step 1: Run GitNexus before editing**

Run context and upstream impact for every existing symbol touched by the integration. New symbols do not need upstream impact before creation.

**Step 2: Write one focused aggregator test**

Test `record(operation, duration)` and a snapshot against a supplied monotonic clock. Verify recognized operations, ignored operations, exact count and nanosecond sum, and a fraction bounded to `[0, 1]` using elapsed time since aggregator creation.

The accepted operation set must come from Task 3. Do not encode an unverified whitelist.

**Step 3: Verify failure**

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.config.JfrGcMetricsTest'
```

Expected: failure because the aggregator does not exist.

**Step 4: Implement the smallest aggregator**

Use one `AtomicLong` for count and one for total nanoseconds. `RecordingStream` has one event-consumer thread; atomics are only needed so Micrometer can read safely. Do not add an interface, factory, or dependency.

**Step 5: Verify the test passes**

Run the command from Step 3. Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Integrate the JFR fallback and lifecycle

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfiguration.java`
- Modify if created: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/JfrGcMetrics.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/GcMetricsConfigurationTest.java`

**Step 1: Add failing source-selection tests**

Verify the three startup outcomes: usable MXBean, unusable MXBean with JFR, and neither source available. Verify that only one source metric is present and no fabricated GC zero is published.

**Step 2: Start one JFR stream only for the JFR path**

Create one asynchronous `RecordingStream`, enable only `jdk.ExecuteVMOperation`, and close it through the Spring bean lifecycle. Catch the concrete unsupported-JFR startup failure, publish `source="unavailable"`, and log one diagnostic. Do not retry or switch sources dynamically.

**Step 3: Publish metrics according to Task 3**

- Passed gate: publish the existing `jvm_gc_*{gc="G1"}` metrics from JFR state.
- Failed gate: publish only `nanofaas_jfr_vm_operation_*` and mark the GC collection source unavailable.

Only calculate a JFR fraction if the accepted event set is non-overlapping; the failed gate forbids summing nested VM-operation durations.

**Step 4: Run configuration and aggregator tests**

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.config.*'
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Make G1 observability part of the build contract

**Files:**

- Modify: `build.gradle`
- Modify only if needed: `scripts/native-java-image.sh`

**Step 1: Add the minimal Gradle rule**

When `nativeGc=G1`, ensure `jfr` is present in `--enable-monitoring`, preserving any requested monitoring modes. This rule belongs in Gradle so direct Gradle builds and the helper script behave identically.

**Step 2: Verify build arguments**

Add or use the smallest existing Gradle configuration check to verify:

- G1 without `nativeMonitoring` enables JFR;
- G1 with another monitoring mode adds JFR once;
- Serial keeps its current behavior.

Do not modify the script if the Gradle rule covers all paths.

---

### Task 7: Native acceptance and complete verification

**Files:**

- Modify: `docs/performance/concurrency-and-runtime-analysis.md`
- Modify: `docs/experiments/baseline-2026-08/README.md`

**Step 1: Build and load-test native G1**

```bash
NATIVE_GC=G1 ./scripts/native-java-image.sh control-plane
```

Run the smallest existing allocation-heavy scenario and scrape metrics repeatedly.

**Step 2: Verify runtime truthfulness**

Assert that:

- the source reports `jfr` only if Task 3 passed;
- observed count and time increase under GC-producing load;
- every fraction is finite and within `[0, 1]`;
- no unavailable MXBean value becomes zero;
- JFR stream and external recording agree over the same start/end window;
- shutdown closes the stream cleanly.

If Task 3 failed, assert the separate VM-operation series instead and confirm that `jvm_gc_collection_*` is absent for G1.

**Step 3: Check monitoring overhead**

Compare one short run with and without JFR using the same image settings and load. Record throughput and resident-memory difference; do not add tuning or buffering unless this measurement exposes a material regression.

**Step 4: Run complete verification**

```bash
./gradlew :control-plane:test
git diff --check
git status --short
```

Expected: tests succeed, the diff is clean, and no generated recordings are present in the worktree.

**Step 5: Run GitNexus change detection**

Run `gitnexus_detect_changes({scope: "all"})`. Verify that every changed symbol and execution flow belongs to GC metrics, native monitoring configuration, tests, or the two documentation files. Re-run impact for any unexpected symbol.

Do not stage or commit automatically. If the user requests a commit, stage only the exact files shown by `git status --short`, re-run `gitnexus_detect_changes`, and use the imperative message `Report native G1 GC metrics via JFR`.
