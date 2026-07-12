# Control-plane Tool Simplification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the control-plane tool's overlapping runners, recipes, commands, profiles, and Prefect integration with three scenario workflows built from portable `workflow-tasks` tasks.

**Architecture:** Scenario files describe what to run; environment files bind logical roles to local or remote executors. `workflow-tasks` owns task behavior and ordered cleanup, while `controlplane-tool` owns configuration, plan composition, CLI/TUI rendering, and artifacts.

**Tech Stack:** Python 3.11+, Pydantic 2, Typer, Rich, shellcraft, Multipass/Azure/Proxmox SDKs, SSH, Ansible, JUnit/Gradle E2E tests.

---

### Task 1: Freeze the current resource and external-VM fixes

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_loader.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_manifest.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/function_tasks.py`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eTest.java`
- Test: existing focused Python and Gradle tests modified in the working tree

**Steps:**

1. Run the focused Python workflow-task tests and expect all tests to pass.
2. Run the focused control-plane scenario tests and expect all tests to pass.
3. Run `./gradlew :control-plane-modules:k8s-deployment-provider:test` and expect success.
4. Run GitNexus change detection and review every affected flow.
5. Commit only the resource and external-lifecycle changes, excluding user-owned `AGENTS.md` and `CLAUDE.md` changes.

### Task 2: Introduce scenario/environment v2 models

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/config/scenario.py`
- Create: `tools/controlplane/src/controlplane_tool/config/environment.py`
- Create: `tools/controlplane/tests/config/test_scenario.py`
- Create: `tools/controlplane/tests/config/test_environment.py`

**Steps:**

1. Write failing tests for the three workflow names, backend/build validation, role definitions, and ignored unknown legacy fields.
2. Run the new tests and confirm imports or validation fail.
3. Implement minimal Pydantic models with no saved-profile or Prefect fields.
4. Add cross-validation: required stack/loadgen roles and resource request not exceeding limit.
5. Run tests and static checks.
6. Commit `Add scenario and environment configuration`.

### Task 3: Add role-bound executors to workflow-tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/execution/roles.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/execution/bindings.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/tasks/models.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/tasks/executors.py`
- Test: `tools/workflow-tasks/tests/execution/test_bindings.py`

**Steps:**

1. Write failing tests binding `host`, `stack`, and `loadgen` to recording executors.
2. Verify the tests fail because role bindings do not exist.
3. Add `ExecutionRole`, immutable bindings, and role-aware command execution.
4. Test local, shared stack/loadgen, and distinct two-role bindings.
5. Run the complete `workflow-tasks` suite and 90% coverage gate.
6. Commit `Bind workflow tasks to execution roles`.

### Task 4: Make cleanup acquisition-based and reversible

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/core/workflow.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/core/resource_task.py`
- Test: `tools/workflow-tasks/tests/core/test_resource_cleanup.py`

**Steps:**

1. Write failing tests for reverse cleanup, cleanup after partial acquisition, primary-error preservation, and `keep_infrastructure`.
2. Confirm failures against current static cleanup ordering.
3. Implement the smallest acquisition stack needed by the tests.
4. Ensure process/forwarding cleanup is not suppressed by infrastructure keep mode.
5. Run all workflow-task tests.
6. Commit `Run workflow cleanup in acquisition order`.

### Task 5: Build the unified validate workflow

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflows/validate.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/components/container.py`
- Modify: existing build, function-registration, Helm, and Kubernetes component task modules
- Create: `tools/workflow-tasks/tests/workflows/test_validate.py`
- Create: `tools/controlplane/src/controlplane_tool/plans/validate.py`
- Test: `tools/controlplane/tests/plans/test_validate.py`

**Steps:**

1. Write task-order tests for POOL, container, and Kubernetes backends.
2. Add rendering tests for local, Multipass, and external stack bindings.
3. Implement task factories by extracting behavior from `container_local_runner.py` and `k3s_curl_runner.py`, not by calling those runners.
4. Add explicit resource inspection tasks for Docker soft/hard limits and Kubernetes requests/limits.
5. Run unit tests, local dry runs, and the real container E2E.
6. Run the K3s E2E on an available Multipass or external environment.
7. Commit `Add portable validate workflow`.
8. Delete `container_local_runner.py`, `k3s_curl_runner.py`, their monolithic tests, and obsolete planner branches.
9. Run the full suites and commit `Remove legacy validation runners`.

### Task 6: Build the unified CLI workflow

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflows/cli.py`
- Create: `tools/workflow-tasks/tests/workflows/test_cli.py`
- Create: `tools/controlplane/src/controlplane_tool/plans/cli.py`
- Test: `tools/controlplane/tests/plans/test_cli.py`

**Steps:**

1. Write failing tests for CLI-on-host and CLI-on-stack role bindings.
2. Extract CLI lifecycle steps into task factories.
3. Verify both bindings render correctly for local and external environments.
4. Run the real CLI lifecycle E2E.
5. Delete `cli-suite`, `cli-stack`, `cli-host`, `cli_validation` runners, duplicate scenario plans, aliases, and snapshot tests.
6. Run all suites and commit `Unify CLI validation workflow`.

### Task 7: Build the unified load-test workflow

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflows/loadtest.py`
- Extend: `tools/workflow-tasks/src/workflow_tasks/loadtest/`
- Create: `tools/workflow-tasks/tests/workflows/test_loadtest.py`
- Create: `tools/controlplane/src/controlplane_tool/plans/loadtest.py`
- Test: `tools/controlplane/tests/plans/test_loadtest.py`

**Steps:**

1. Write failing task-order tests for shared stack/loadgen and dedicated loadgen roles.
2. Add provider contract tests for Multipass, external, Azure, and Proxmox bindings.
3. Move remaining k6, Prometheus, metrics-gate, autoscaling, and report tasks into workflow-tasks.
4. Execute one- and two-role E2E tests.
5. Delete all per-provider load-test plans, adapters, the legacy Helm scenario, and the two-VM runner.
6. Remove Grafana runtime if no retained test requires it.
7. Run all suites and commit `Unify load-test workflows`.

### Task 8: Delete compatibility and Prefect architecture

**Files:**
- Delete: `tools/controlplane/src/controlplane_tool/orchestation/`
- Delete: `tools/workflow-tasks/src/workflow_tasks/integrations/prefect.py`
- Delete or simplify: `tools/workflow-tasks/src/workflow_tasks/orchestration/runtime.py`
- Delete: `tools/controlplane/prefect.yaml`
- Delete: saved-profile code under `tools/controlplane/src/controlplane_tool/workspace/profiles.py`
- Modify: both Python `pyproject.toml` files and lockfile
- Delete: Prefect, profile, compatibility re-export, milestone, and legacy-alias tests

**Steps:**

1. Write a test asserting the runtime executes directly without an orchestration backend.
2. Replace `run_local_flow` with direct workflow execution and normalized events.
3. Remove all Prefect fields from configuration and TUI.
4. Remove Prefect and unused dependencies from project metadata.
5. Remove compatibility re-export modules after updating their remaining imports.
6. Run import-linter, Ruff, BasedPyright, and all Python tests.
7. Commit `Remove Prefect and compatibility layers`.

### Task 9: Replace the command surface

**Files:**
- Rewrite: `tools/controlplane/src/controlplane_tool/app/main.py`
- Create: `tools/controlplane/src/controlplane_tool/cli/run.py`
- Create: `tools/controlplane/src/controlplane_tool/cli/plan.py`
- Create: `tools/controlplane/src/controlplane_tool/cli/doctor.py`
- Delete: old command modules after moving retained list/inspect behavior
- Test: `tools/controlplane/tests/cli/`

**Steps:**

1. Write failing CLI tests for exactly `run`, `plan`, `list`, `inspect`, `doctor`, and `tui`.
2. Implement the six-command surface over the shared plan API.
3. Add task slicing tests for `--only`, `--from`, and `--until`.
4. Add `--keep` cleanup-policy tests.
5. Delete top-level build/image/native/matrix commands and `vm`, `cli-test`, `e2e`, `functions`, and `loadtest` groups.
6. Update `scripts/controlplane.sh` as a thin launcher only.
7. Run CLI tests and commit `Simplify control-plane tool commands`.

### Task 10: Rebuild the TUI as a shared-plan client

**Files:**
- Rewrite: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Retain and simplify: renderer/event modules that consume normalized events
- Delete: workflow-specific TUI branches and saved-profile screens
- Test: focused TUI product and event tests

**Steps:**

1. Write product tests for selecting scenario, environment, task slice, and keep policy.
2. Implement TUI calls to the same functions used by CLI commands.
3. Remove workflow and provider branching from views.
4. Remove snapshot tests for deleted menus.
5. Run TUI and full control-plane tests.
6. Commit `Rebuild TUI on shared workflow plans`.

### Task 11: Delete transition layers and finalize documentation

**Files:**
- Delete: recipe, composer, legacy planner, old runners, deprecated aliases, mock Kubernetes/deploy-host path, development console entry points
- Update: `README.md`, `docs/`, `scripts/`, example scenario and environment files

**Steps:**

1. Use GitNexus and `rg` to prove every deletion candidate has no retained caller.
2. Delete the transition packages and their tests.
3. Run dependency analysis and remove Jinja2/Plotly/TOML/YAML/Flask dependencies if no retained feature uses them; keep report dependencies only if load-test HTML remains required.
4. Run `uv lock` and verify clean installs for all three Python packages.
5. Run import-linter, Ruff, BasedPyright, full Python tests, Gradle tests, and architecture audits.
6. Run local container and VM-backed K3s smoke E2Es.
7. Run GitNexus change detection and review all affected workflows.
8. Update architecture and operator documentation.
9. Commit `Remove control-plane tool transition architecture`.
