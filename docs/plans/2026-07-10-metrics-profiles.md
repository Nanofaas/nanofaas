# Prometheus Metrics Profiles Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Provide startup-selectable `basic` and `advanced` Prometheus metric profiles, with `basic` as the low-resource default.

**Architecture:** Add one Spring configuration that parses `nanofaas.metrics.profile`, installs a Micrometer `MeterFilter`, and publishes the active profile as an info gauge. Keep metrics needed by the autoscaler in both profiles; deny only diagnostic/high-cardinality meters in `basic`. Configure Prometheus histograms for function timers only in `advanced`, replacing client-side percentiles.

**Tech Stack:** Java 21, Spring Boot, Micrometer, Prometheus, JUnit 5, AssertJ.

---

### Task 1: Define profile behavior with tests

**Files:**
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/config/MetricsProfileConfigurationTest.java`

**Step 1: Write failing tests**

Test that `basic` preserves `function_dispatch_total`, rejects function latency and detailed controller meters, and keeps global sync-queue depth while rejecting its per-function series. Test that `advanced` accepts those meters and enables percentile histograms. Test parsing/defaults and the profile info gauge.

**Step 2: Verify RED**

Run: `./gradlew :control-plane:test --tests '*MetricsProfileConfigurationTest'`

Expected: compilation failure because `MetricsProfileConfiguration` does not exist.

### Task 2: Implement the minimal Micrometer configuration

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/MetricsProfileConfiguration.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/Metrics.java`
- Modify: `platform/control-plane/src/main/resources/application.yml`

**Step 1: Perform GitNexus impact analysis**

Analyze `Metrics.timer` before replacing client-side percentiles. New configuration symbols have no callers yet.

**Step 2: Add the minimal implementation**

Parse `basic|advanced` case-insensitively and fail startup for other values. In `basic`, deny only advanced custom meters while retaining autoscaler inputs and global queue signals. In `advanced`, configure histograms for function timers. Register `nanofaas_metrics_profile_info{profile=...} 1` and set `basic` in `application.yml`.

**Step 3: Verify GREEN**

Run: `./gradlew :control-plane:test --tests '*MetricsProfileConfigurationTest'`

Expected: PASS.

### Task 3: Document and verify integration

**Files:**
- Modify: `README.md`
- Modify: `openapi.yaml` only if the HTTP API changes (expected: no change).

**Step 1: Document both profiles**

Describe selection, defaults, metric membership, startup-only semantics, and Prometheus `histogram_quantile` usage in the existing Observability section.

**Step 2: Run focused and full tests**

Run: `./gradlew :control-plane:test :control-plane-modules:autoscaler:test :control-plane-modules:sync-queue:test`

Run: `./gradlew build -PcontrolPlaneModules=all`

Expected: both commands succeed.

**Step 3: Check scope and commit**

Run GitNexus change detection and inspect `git diff --check` plus `git status --short`. Commit only the metrics-profile files.
