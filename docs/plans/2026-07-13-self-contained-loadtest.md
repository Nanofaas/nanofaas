# Self-contained Load-test Workflow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore build, deployment, registration, autoscaling verification, and cleanup around the existing unified load-test body.

**Architecture:** Extract reusable Kubernetes stack task-spec factories from `validate`, then compose them with the existing role-aware load-test tasks in the control-plane plan. Keep provider behavior in environment bindings/provisioning and keep the workflow sequential.

**Tech Stack:** Python 3.11+, Typer, Pydantic, workflow-tasks, Multipass, Ansible, Helm, k3s, k6, Prometheus.

---

### Task 1: Extract reusable Kubernetes deployment task specs

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/validate.py`
- Test: `tools/workflow-tasks/tests/workflows/test_validate.py`

**Steps:**

1. Add failing tests for a public Kubernetes deployment prefix with optional load-test NodePorts and separate registration specs.
2. Run the focused tests and confirm the factories are missing.
3. Extract the existing preflight/build/images/Helm and registration code without changing validate task order.
4. Run focused and complete workflow-task tests.
5. Commit `Share Kubernetes deployment task specs`.

### Task 2: Compose the complete load-test lifecycle

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/plans/loadtest.py`
- Modify: `tools/controlplane/tests/plans/test_loadtest.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/core/workflow.py`
- Test: `tools/workflow-tasks/tests/core/test_workflow.py`

**Steps:**

1. Add a failing plan test asserting deploy and registration precede k6 and Helm cleanup follows the body.
2. Add a failing workflow test proving `keep_infrastructure` skips static infrastructure cleanup.
3. Compose deployment, registration, existing load-test body, and cleanup in one `Workflow`.
4. Implement the minimum `--keep` behavior needed by infrastructure cleanup.
5. Run focused and complete Python suites and quality gates.
6. Commit `Restore load-test stack lifecycle`.

### Task 3: Resolve remote load-test endpoints

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/execution.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Test: `tools/controlplane/tests/cli/test_execution_bindings.py`
- Test: `tools/controlplane/tests/cli/test_command_surface.py`

**Steps:**

1. Add failing tests for external host defaults, Multipass discovery after provisioning, and explicit URL overrides.
2. Run the focused tests and confirm current fixed localhost defaults fail.
3. Resolve endpoints only for `run`; keep `plan` side-effect free.
4. Run CLI and full control-plane tests.
5. Commit `Resolve load-test service endpoints`.

### Task 4: Reconnect autoscaling verification

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/config/scenario.py`
- Modify: `tools/controlplane/scenarios-v2/loadtest.yaml`
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/plans/loadtest.py`
- Test: corresponding scenario, workflow, and plan tests

**Steps:**

1. Add failing tests for an explicit `autoscaling` scenario flag, scaling registration, concurrent replica observation, and a post-load scale gate.
2. Reuse `ReplicaProbe`, `ReplicaWatcher`, `RunK6WithReplicaWatch`, and `VerifyAutoscalingReplicas`.
3. Keep autoscaling disabled unless requested; enable it in the bundled research scenario.
4. Run all Python suites and quality gates.
5. Commit `Restore load-test autoscaling verification`.

### Task 5: Document and verify one- and two-role E2E

**Files:**
- Create: `tools/controlplane/environments/multipass-two-vm.yaml`
- Modify: `tools/controlplane/README.md`
- Modify: `docs/e2e-tutorial.md`

**Steps:**

1. Document first-run `--provision`, subsequent runs, artifacts, `--keep`, and both topologies.
2. Run the full control-plane and workflow-task suites plus quality gates.
3. Run the real one-VM Multipass load test and verify artifacts and cleanup.
4. Run the real two-role Multipass load test and verify artifacts and cleanup.
5. Run GitNexus change detection, review all direct dependents, reindex, commit, and push the branch.
