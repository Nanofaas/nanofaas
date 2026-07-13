# Explicit VM Provisioning and CLI Progress Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add explicit `run --provision` support and visible per-task progress without expanding the six-command CLI.

**Architecture:** The control-plane tool adapts environment roles into existing `VmOrchestrator` operations. A console sink renders normalized workflow events; `workflow-tasks` remains independent of CLI/UI code.

**Tech Stack:** Python 3.11+, Typer, Pydantic, workflow-tasks, Multipass, Ansible, Rich.

---

### Task 1: Render normalized workflow progress

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli/progress.py`
- Test: `tools/controlplane/tests/cli/test_progress.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`

**Steps:**

1. Write a failing test feeding running/completed/failed events to a console sink and asserting task identifiers plus elapsed time.
2. Run the focused test and confirm the sink is missing.
3. Implement a minimal sink using Typer output and a monotonic clock.
4. Bind the sink around `workflow.run()` for the CLI run command.
5. Run focused and full control-plane tests.
6. Commit `Show workflow progress in CLI runs`.

### Task 2: Compose idempotent provisioning from environment roles

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/provisioning.py`
- Test: `tools/controlplane/tests/test_provisioning.py`
- Modify: `tools/controlplane/src/controlplane_tool/config/environment.py` if connection data needs extension.

**Steps:**

1. Write failing recording-orchestrator tests for Multipass stack, external stack, and dedicated load-generator roles.
2. Assert operation order: ensure running, base dependencies, optional k3s/registry or k6, repository sync.
3. Implement role-to-`VmRequest` conversion and the minimum direct calls to `VmOrchestrator`/Ansible.
4. Wrap each operation in `workflow_step` so the console sink reports provisioning progress.
5. Verify failures stop immediately and never tear down the VM.
6. Run focused tests and static checks.
7. Commit `Add explicit environment provisioning`.

### Task 3: Expose and verify run --provision

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Modify: `tools/controlplane/tests/cli/test_command_surface.py`
- Modify: `tools/controlplane/README.md`
- Modify: `docs/e2e-tutorial.md`

**Steps:**

1. Write a failing CLI test proving `--provision` runs before scenario execution and is rejected for local environments.
2. Add the option only to `run`; keep `plan` side-effect free.
3. Document first-run and subsequent-run commands for Multipass and external SSH.
4. Run both Python suites and quality gates.
5. Execute the real Multipass `validate-k8s.yaml --provision` E2E and verify cleanup.
6. Run GitNexus change detection, commit, reindex, and push the branch.
