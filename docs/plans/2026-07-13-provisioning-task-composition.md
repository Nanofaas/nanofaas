# Provisioning Task Composition Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep `run --provision` while separating VM lifecycle from software bootstrap and composing the existing `workflow-tasks` definitions.

**Architecture:** The control-plane tool remains responsible only for selecting and ordering work. `EnsureVmRunning` acquires or checks each VM first; after its resolved host is available, the existing bootstrap component planners produce executable `CommandTask` instances for Ansible, k3s, registry, k6, and repository synchronization. The old recipe/adapter framework is not restored.

**Tech Stack:** Python 3.13, `workflow-tasks`, Typer, pytest, Ansible, Multipass/SSH.

---

### Task 1: Specify the lifecycle/bootstrap boundary

**Files:**
- Modify: `tools/controlplane/tests/test_provisioning.py`

1. Replace the orchestrator-method assertions with a fake VM lifecycle plus a recording host-command runner.
2. Assert that VM acquisition happens before any bootstrap command.
3. Assert that the stack uses the existing base, k3s, registry, and sync task specifications.
4. Assert that an external environment performs only an SSH lifecycle preflight and is never destroyed.
5. Assert that a dedicated load generator receives its own lifecycle, k6, and sync phases.
6. Assert that a failed bootstrap command stops subsequent tasks.
7. Run `uv run pytest tests/test_provisioning.py -q` from `tools/controlplane`; expect the new tests to fail because provisioning still invokes orchestrator methods directly.

### Task 2: Compose existing provisioning tasks

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/provisioning.py`

1. Build and run `EnsureVmRunning` with `VmLifecycleAdapter` for each selected role.
2. Convert the returned `VmInfo` to a resolved SSH `VmRequest` for bootstrap planning.
3. Use `plan_vm_provision_base`, `plan_k3s_install`, `plan_registry_ensure_container`, `plan_k3s_configure_registry`, `plan_loadtest_install_k6`, and `plan_repo_sync_to_vm`.
4. Convert their operations with `command_task_from_operation` and execute them in a normal `Workflow` through `HostCommandTaskExecutor`.
5. Prefix task identifiers with `provision.stack` or `provision.loadgen` so progress remains unambiguous.
6. Remove the local callback runner and direct calls to `VmOrchestrator.install_*`, `setup_registry`, and `sync_project`.
7. Run `uv run pytest tests/test_provisioning.py -q`; expect all tests to pass.

### Task 3: Regression verification

**Files:**
- Verify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Verify: `tools/workflow-tasks/src/workflow_tasks/components/bootstrap.py`

1. Run `uv run pytest -q` from `tools/controlplane`.
2. Run `uv run pytest -q` from `tools/workflow-tasks`.
3. Run GitNexus change detection and confirm only provisioning composition and its tests/docs are affected.
4. Review `git diff --check` and the final diff.
