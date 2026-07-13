# Reproducible Load-Test Baseline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Produce correct, self-describing, comparable load-test artifacts across every supported provider.

**Architecture:** Reuse the existing workflow events, k6 summary, and Prometheus snapshot. Extend only the existing progress sink and load-test workflow, and remove the duplicate Helm scrape source.

**Tech Stack:** Python 3.13, pytest, Pydantic, standard-library JSON/subprocess, Helm, Prometheus, k6.

---

### Task 1: Correct Prometheus collection

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/loadtest.py`
- Modify: `tools/workflow-tasks/tests/workflows/test_loadtest.py`
- Modify: `deploy/helm/nanofaas/templates/control-plane-deployment.yaml`

1. Add failing tests asserting function-filtered Micrometer queries for counters, timers, CPU, and heap memory.
2. Run the focused tests and confirm the old query set fails them.
3. Implement the smallest query-builder function and use it from the control-plane plan.
4. Run the focused tests and render the Helm chart, verifying the control-plane Pod no longer has scrape annotations and the Service still does.

### Task 2: Persist autoscaling and metric summaries

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/autoscaling.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/loadtest.py`
- Modify: `tools/workflow-tasks/tests/loadtest/test_autoscaling.py`
- Modify: `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py`
- Modify: `tools/workflow-tasks/tests/workflows/test_loadtest.py`

1. Add failing tests for retaining the verified autoscaling result and writing `summary.json` from representative k6 and Prometheus input.
2. Run the focused tests and verify the missing result/summary behavior fails.
3. Add a result property to the verifier and a single summary-writing task using only `json` and basic statistics.
4. Insert the task after metric capture and run all `workflow-tasks` tests.

### Task 3: Persist reproducibility metadata and task durations

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/progress.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Modify: `tools/controlplane/tests/cli/test_progress.py`
- Modify: `tools/controlplane/tests/cli/test_command_surface.py`

1. Add failing tests for task records and passed/failed `run-metadata.json` output.
2. Run focused tests and confirm the fields are absent.
3. Retain task records in `ConsoleProgressSink` and write metadata from the existing `run` command in `finally`.
4. Run all control-plane tool tests.

### Task 4: Provider-neutral and end-to-end verification

**Files:**
- Update documentation only if actual behavior differs from the design.

1. Run both complete Python test suites.
2. Run Helm lint/template validation.
3. Run load-test plans for Multipass, Azure, and Proxmox without provisioning.
4. Run GitNexus change detection and review all affected flows.
5. Run the real Multipass two-VM E2E and inspect `summary.json` and `run-metadata.json`.
6. Commit and push only after every available verification passes; document Azure/Proxmox live validation as deferred.
