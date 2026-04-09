# Prefect-Native Controlplane Tooling Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rebuild the entire Python control-plane tooling stack around local-first Prefect flows and tasks so the TUI, CLI, E2E scenarios, VM management, and load testing share one orchestration model and are ready for later CI/orchestrator execution.

**Architecture:** Keep external infrastructure tools as the execution truth for provisioning and deployment work: Ansible for host/bootstrap tasks, Helm for application deployment, Multipass for VM lifecycle, Gradle and container tooling for builds, and k6/Prometheus for load validation. Introduce a Prefect runtime layer inside `tools/controlplane/` that wraps these integrations as reusable tasks, composes them into flows, and exposes a normalized event stream consumed by both the TUI and the CLI. All flows must run locally without requiring a Prefect server; remote Prefect deployments are a later, optional execution target.

**Tech Stack:** Python 3.11, Prefect 3, Typer, Rich, pytest, Ansible, Helm, Multipass, Docker-compatible runtimes, Gradle, httpx, tenacity, existing `tools/controlplane` domain models.

---

## Locked Decisions

- `Prefect` is the orchestration runtime for all Python tooling.
- Local execution remains the default. `PREFECT_API_URL` is optional, not required.
- No `Taskfile`, `make`, or other additional launcher layer is introduced.
- External tools remain authoritative for provisioning and deployment behavior.
- Scenario-specific shell snippets must move out of runners and into shared task/integration modules.
- TUI renders flow state and logs; it does not own orchestration logic.
- The current `configure-registry` behavior must be split into:
  - `ensure registry container`
  - `configure k3s registry mirror`

## Scope

This plan covers all Python tooling under `tools/controlplane/src/controlplane_tool/`, including:

- interactive TUI
- Typer CLI entrypoints
- VM lifecycle workflows
- E2E scenario workflows
- local/host/VM CLI validation workflows
- K3s and Helm compatibility workflows
- load testing, Prometheus validation, and reporting workflows
- shared logging and process streaming

## Explicit Non-Goals

- rewriting Gradle builds in Python
- replacing Ansible, Helm, Multipass, Docker, k6, or Prometheus with custom Python implementations
- requiring a remote Prefect server for local development
- redesigning NanoFaaS product semantics, APIs, or Kubernetes manifests

## End State

At the end of the roadmap:

- every operational workflow in `tools/controlplane/` is implemented as a Prefect flow
- reusable infrastructure and deployment actions are exposed as Prefect tasks with stable semantic IDs
- TUI and CLI both invoke the same flow catalog
- scenario definitions are composed from reusable tasks instead of hardcoded `if scenario == ...` branches
- all flow runs have local `flow_run_id` and `task_run_id` metadata, with optional Prefect orchestration IDs when running against a remote API
- the `k8s-vm` scenario orders expensive work to minimize cluster-up critical path
- remote Prefect deployments can be added without rewriting workflow logic

## Milestone Map

- `M1` Add the local-first Prefect runtime substrate
- `M2` Normalize operation models and event/log streaming
- `M3` Extract infrastructure integrations and split registry vs k3s responsibilities
- `M4` Convert VM management and build/test flows to Prefect
- `M5` Convert all E2E and CLI validation workflows to Prefect
- `M6` Convert load testing and metrics validation workflows to Prefect
- `M7` Rebuild the TUI as a Prefect flow monitor
- `M8` Add declarative flow composition and remote Prefect deployment readiness

## Ordering Constraints

- `M1` and `M2` must land before any family-wide workflow conversion.
- `M3` must land before reordering `k8s-vm`, because the current registry playbook couples registry startup and k3s configuration.
- `M4`, `M5`, and `M6` can proceed incrementally once the shared task catalog exists.
- `M7` must consume the normalized flow/task event stream from `M2`, not invent a TUI-only model.
- `M8` comes last so declarative flow specs describe stable tasks, not moving internals.

---

### Task 1 / M1: Add the local-first Prefect runtime substrate

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/prefect_runtime.py`
- Create: `tools/controlplane/src/controlplane_tool/prefect_models.py`
- Create: `tools/controlplane/tests/test_prefect_runtime.py`
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/uv.lock`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Modify: `tools/controlplane/tests/test_main_entrypoint.py`

**Step 1: Write the failing tests**

Add tests that lock the required local execution semantics:

```python
from pathlib import Path

from controlplane_tool.prefect_runtime import run_local_flow


def test_run_local_flow_returns_normalized_run_metadata(tmp_path: Path) -> None:
    def sample_flow() -> str:
        return "ok"

    result = run_local_flow("sample.flow", sample_flow)

    assert result.status == "completed"
    assert result.flow_id == "sample.flow"
    assert result.flow_run_id
    assert result.orchestrator_backend in {"none", "prefect-local"}
```

Add an entrypoint test proving the CLI can invoke the Prefect runtime layer without requiring a configured API URL.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_prefect_runtime.py \
  tools/controlplane/tests/test_main_entrypoint.py -q
```

Expected:

- import or attribute failures because the Prefect runtime module does not exist yet
- no current local flow metadata contract

**Step 3: Write minimal implementation**

Implement:

- a small runtime facade over Prefect for local flow execution
- normalized run metadata carrying:
  - `flow_id`
  - `flow_run_id`
  - `orchestrator_backend`
  - `started_at`
  - `finished_at`
  - `status`
- a convention that local runs work with no Prefect server configured
- dependency wiring in `pyproject.toml`

Do not convert existing workflows yet. This milestone is substrate only.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/prefect_runtime.py \
  tools/controlplane/src/controlplane_tool/prefect_models.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/pyproject.toml \
  tools/controlplane/uv.lock \
  tools/controlplane/src/controlplane_tool/prefect_runtime.py \
  tools/controlplane/src/controlplane_tool/prefect_models.py \
  tools/controlplane/src/controlplane_tool/main.py \
  tools/controlplane/tests/test_prefect_runtime.py \
  tools/controlplane/tests/test_main_entrypoint.py
git commit -m "refactor: add local-first prefect runtime"
```

---

### Task 2 / M2: Normalize operation models and event/log streaming

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/workflow_models.py`
- Create: `tools/controlplane/src/controlplane_tool/workflow_events.py`
- Create: `tools/controlplane/src/controlplane_tool/prefect_event_bridge.py`
- Create: `tools/controlplane/tests/test_workflow_events.py`
- Create: `tools/controlplane/tests/test_prefect_event_bridge.py`
- Modify: `tools/controlplane/src/controlplane_tool/console.py`
- Modify: `tools/controlplane/src/controlplane_tool/process_streaming.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`

**Step 1: Write the failing tests**

Add tests that require one normalized event model for all runtimes:

```python
def test_prefect_task_state_is_mapped_to_workflow_event() -> None:
    event = normalize_task_state(
        flow_id="e2e.k8s_vm",
        task_id="vm.ensure_running",
        state_name="Completed",
    )
    assert event.kind == "task.completed"
    assert event.task_id == "vm.ensure_running"


def test_logged_process_output_is_tagged_with_task_run_context() -> None:
    event = build_log_event(task_id="images.build_core", line="docker push ok")
    assert event.kind == "log.line"
    assert event.task_id == "images.build_core"
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_workflow_events.py \
  tools/controlplane/tests/test_prefect_event_bridge.py \
  tools/controlplane/tests/test_console_workflow.py \
  tools/controlplane/tests/test_tui_workflow.py -q
```

Expected: FAIL because no unified event bridge exists.

**Step 3: Write minimal implementation**

Implement:

- stable workflow model types:
  - `WorkflowRun`
  - `TaskDefinition`
  - `TaskRun`
  - `WorkflowEvent`
- mapping from Prefect state transitions to normalized events
- log events that carry `flow_id`, `task_id`, `task_run_id`, `stream`
- console and TUI helpers that consume only normalized events, not runner-specific callbacks

Preserve the existing TUI log pane behavior while moving the data source behind the new bridge.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/workflow_models.py \
  tools/controlplane/src/controlplane_tool/workflow_events.py \
  tools/controlplane/src/controlplane_tool/prefect_event_bridge.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/workflow_models.py \
  tools/controlplane/src/controlplane_tool/workflow_events.py \
  tools/controlplane/src/controlplane_tool/prefect_event_bridge.py \
  tools/controlplane/src/controlplane_tool/console.py \
  tools/controlplane/src/controlplane_tool/process_streaming.py \
  tools/controlplane/src/controlplane_tool/tui_workflow.py \
  tools/controlplane/tests/test_workflow_events.py \
  tools/controlplane/tests/test_prefect_event_bridge.py \
  tools/controlplane/tests/test_console_workflow.py \
  tools/controlplane/tests/test_tui_workflow.py
git commit -m "refactor: normalize workflow events across prefect tasks"
```

---

### Task 3 / M3: Extract infrastructure integrations and split registry vs k3s responsibilities

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/helm_ops.py`
- Create: `tools/controlplane/src/controlplane_tool/image_ops.py`
- Create: `tools/controlplane/tests/test_helm_ops.py`
- Create: `tools/controlplane/tests/test_image_ops.py`
- Create: `ops/ansible/playbooks/ensure-registry.yml`
- Create: `ops/ansible/playbooks/configure-k3s-registry.yml`
- Modify: `tools/controlplane/src/controlplane_tool/runtime_primitives.py`
- Modify: `tools/controlplane/src/controlplane_tool/ansible_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/registry_runtime.py`
- Modify: `tools/controlplane/tests/test_ansible_adapter.py`
- Modify: `tools/controlplane/tests/test_vm_adapter.py`
- Modify: `tools/controlplane/tests/test_registry_runtime.py`
- Delete: `ops/ansible/playbooks/configure-registry.yml`

**Step 1: Write the failing tests**

Lock the split responsibilities:

```python
def test_vm_adapter_exposes_registry_container_and_k3s_registry_as_separate_operations() -> None:
    orchestrator = VmOrchestrator(Path("/repo"), shell=RecordingShell())
    assert orchestrator.ensure_registry_container is not None
    assert orchestrator.configure_k3s_registry is not None


def test_helm_ops_build_upgrade_install_command() -> None:
    command = HelmOps(Path("/repo")).upgrade_install(
        release="control-plane",
        chart=Path("helm/nanofaas"),
        namespace="nanofaas-e2e",
        values={"controlPlane.image.tag": "e2e"},
        dry_run=True,
    )
    assert command.command[:3] == ["helm", "upgrade", "--install"]
```

Add a regression test that fails if a playbook both starts the registry container and edits `/etc/rancher/k3s/registries.yaml`.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_helm_ops.py \
  tools/controlplane/tests/test_image_ops.py \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py \
  tools/controlplane/tests/test_registry_runtime.py -q
```

Expected: FAIL because the split operations do not exist yet.

**Step 3: Write minimal implementation**

Implement:

- `HelmOps` for stable Helm command construction
- `ImageOps` for build/push/tag operations that currently appear inline in multiple runners
- split Ansible playbooks:
  - `ensure-registry.yml`
  - `configure-k3s-registry.yml`
- adapter methods:
  - `ensure_registry_container(...)`
  - `configure_k3s_registry(...)`

Preserve current behavior, but decouple registry startup from k3s installation.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/helm_ops.py \
  tools/controlplane/src/controlplane_tool/image_ops.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/runtime_primitives.py \
  tools/controlplane/src/controlplane_tool/ansible_adapter.py \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  tools/controlplane/src/controlplane_tool/registry_runtime.py \
  tools/controlplane/src/controlplane_tool/helm_ops.py \
  tools/controlplane/src/controlplane_tool/image_ops.py \
  tools/controlplane/tests/test_helm_ops.py \
  tools/controlplane/tests/test_image_ops.py \
  tools/controlplane/tests/test_ansible_adapter.py \
  tools/controlplane/tests/test_vm_adapter.py \
  tools/controlplane/tests/test_registry_runtime.py \
  ops/ansible/playbooks/ensure-registry.yml \
  ops/ansible/playbooks/configure-k3s-registry.yml
git rm ops/ansible/playbooks/configure-registry.yml
git commit -m "refactor: split registry and k3s infrastructure operations"
```

---

### Task 4 / M4: Convert VM management and build/test workflows to Prefect

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/vm_tasks.py`
- Create: `tools/controlplane/src/controlplane_tool/build_tasks.py`
- Create: `tools/controlplane/src/controlplane_tool/infra_flows.py`
- Create: `tools/controlplane/tests/test_vm_tasks.py`
- Create: `tools/controlplane/tests/test_build_tasks.py`
- Create: `tools/controlplane/tests/test_infra_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/gradle_ops.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`
- Modify: `tools/controlplane/tests/test_vm_commands.py`
- Modify: `tools/controlplane/tests/test_cli_commands.py`
- Modify: `tools/controlplane/tests/test_gradle_ops.py`
- Modify: `tools/controlplane/tests/test_pipeline.py`

**Step 1: Write the failing tests**

Write tests that require command entrypoints to delegate to flows instead of ad hoc orchestration:

```python
def test_vm_provision_base_command_runs_prefect_flow(monkeypatch) -> None:
    called = {}

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):
        called["flow_id"] = flow_id
        return DummyFlowResult.completed()

    monkeypatch.setattr("controlplane_tool.vm_commands.run_local_flow", fake_run_local_flow)
    vm_provision_base(...)
    assert called["flow_id"] == "vm.provision_base"
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_vm_tasks.py \
  tools/controlplane/tests/test_build_tasks.py \
  tools/controlplane/tests/test_infra_flows.py \
  tools/controlplane/tests/test_vm_commands.py \
  tools/controlplane/tests/test_cli_commands.py -q
```

Expected: FAIL because the commands still orchestrate directly.

**Step 3: Write minimal implementation**

Implement Prefect tasks and flows for:

- VM ensure/inspect/sync/provision/teardown
- build/test entrypoints currently reachable from CLI
- shared preflight/build pipeline execution

Keep all actual work delegated to existing adapters and ops modules.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/vm_tasks.py \
  tools/controlplane/src/controlplane_tool/build_tasks.py \
  tools/controlplane/src/controlplane_tool/infra_flows.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/vm_tasks.py \
  tools/controlplane/src/controlplane_tool/build_tasks.py \
  tools/controlplane/src/controlplane_tool/infra_flows.py \
  tools/controlplane/src/controlplane_tool/vm_commands.py \
  tools/controlplane/src/controlplane_tool/cli_commands.py \
  tools/controlplane/src/controlplane_tool/gradle_ops.py \
  tools/controlplane/src/controlplane_tool/pipeline.py \
  tools/controlplane/tests/test_vm_tasks.py \
  tools/controlplane/tests/test_build_tasks.py \
  tools/controlplane/tests/test_infra_flows.py \
  tools/controlplane/tests/test_vm_commands.py \
  tools/controlplane/tests/test_cli_commands.py \
  tools/controlplane/tests/test_gradle_ops.py \
  tools/controlplane/tests/test_pipeline.py
git commit -m "refactor: convert infrastructure workflows to prefect flows"
```

---

### Task 5 / M5: Convert all E2E and CLI validation workflows to Prefect

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario_tasks.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Create: `tools/controlplane/tests/test_scenario_tasks.py`
- Create: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/local_e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/deploy_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/helm_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/local_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_cli_run_behavior.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_k3s_e2e_commands.py`

**Step 1: Write the failing tests**

Add scenario-family tests that enforce shared task composition:

```python
def test_k8s_vm_flow_uses_reusable_vm_and_deploy_tasks() -> None:
    flow = build_scenario_flow("k8s-vm")
    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core",
        "k3s.install",
        "k3s.configure_registry",
        "tests.run_k8s_e2e",
    ]


def test_cli_vm_flow_reuses_build_and_helm_deploy_tasks() -> None:
    flow = build_scenario_flow("cli")
    assert "helm.deploy_control_plane" in flow.task_ids
```

Add regression tests that fail if `e2e_runner.py`, `cli_vm_runner.py`, or `k3s_curl_runner.py` contain inline `docker build`, `docker push`, or `helm upgrade --install` orchestration logic.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_run_behavior.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_k3s_e2e_commands.py -q
```

Expected: FAIL because the current runners still embed orchestration logic.

**Step 3: Write minimal implementation**

Implement reusable tasks for:

- local E2E setup and teardown
- host deploy E2E
- VM image build and push
- Helm deployment and rollout wait
- CLI install and validation
- K3s curl compatibility setup and verification
- Helm stack deployment and validation
- Java test invocation in VM

Convert scenario entrypoints to flow composition only.

For `k8s-vm`, lock the new order:

1. `vm.ensure_running`
2. `vm.provision_base`
3. `repo.sync_to_vm`
4. `registry.ensure_container`
5. `images.build_core`
6. `k3s.install`
7. `k3s.configure_registry`
8. `tests.run_k8s_e2e`

Only add a separate `helm.deploy_*` step if the scenario truly deploys through Helm outside the test itself.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/scenario_tasks.py \
  tools/controlplane/src/controlplane_tool/scenario_flows.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_tasks.py \
  tools/controlplane/src/controlplane_tool/scenario_flows.py \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/local_e2e_runner.py \
  tools/controlplane/src/controlplane_tool/container_local_runner.py \
  tools/controlplane/src/controlplane_tool/deploy_host_runner.py \
  tools/controlplane/src/controlplane_tool/cli_vm_runner.py \
  tools/controlplane/src/controlplane_tool/cli_host_runner.py \
  tools/controlplane/src/controlplane_tool/k3s_curl_runner.py \
  tools/controlplane/src/controlplane_tool/helm_stack_runner.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/src/controlplane_tool/local_e2e_commands.py \
  tools/controlplane/src/controlplane_tool/cli_e2e_commands.py \
  tools/controlplane/src/controlplane_tool/k3s_e2e_commands.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_run_behavior.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_k3s_e2e_commands.py
git commit -m "refactor: convert e2e and cli workflows to prefect flows"
```

---

### Task 6 / M6: Convert load testing and metrics validation workflows to Prefect

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest_tasks.py`
- Create: `tools/controlplane/src/controlplane_tool/loadtest_flows.py`
- Create: `tools/controlplane/tests/test_loadtest_tasks.py`
- Create: `tools/controlplane/tests/test_loadtest_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_bootstrap.py`
- Modify: `tools/controlplane/src/controlplane_tool/prometheus_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/grafana_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/k6_ops.py`
- Modify: `tools/controlplane/src/controlplane_tool/metrics_gate.py`
- Modify: `tools/controlplane/tests/test_loadtest_runner.py`
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`
- Modify: `tools/controlplane/tests/test_prometheus_runtime.py`
- Modify: `tools/controlplane/tests/test_grafana_runtime.py`
- Modify: `tools/controlplane/tests/test_k6_ops.py`
- Modify: `tools/controlplane/tests/test_metrics_gate.py`

**Step 1: Write the failing tests**

Add tests proving the loadtest stack is a composed flow:

```python
def test_loadtest_flow_runs_bootstrap_execute_gate_and_report_tasks() -> None:
    flow = build_loadtest_flow("quick")
    assert flow.task_ids == [
        "loadtest.bootstrap",
        "loadtest.execute_k6",
        "metrics.evaluate_gate",
        "loadtest.write_report",
    ]
```

Add regression tests that fail if `loadtest_runner.py` directly coordinates process sequencing instead of delegating to flow tasks.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_tasks.py \
  tools/controlplane/tests/test_loadtest_flows.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_prometheus_runtime.py \
  tools/controlplane/tests/test_grafana_runtime.py \
  tools/controlplane/tests/test_k6_ops.py \
  tools/controlplane/tests/test_metrics_gate.py -q
```

Expected: FAIL because the current loadtest orchestration is still runner-owned.

**Step 3: Write minimal implementation**

Implement flow tasks for:

- bootstrap SUT and observability
- execute k6
- query Prometheus
- evaluate metrics gate
- persist reports and artifacts

Reuse the existing runtime and metrics modules as task internals rather than rewriting them.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/loadtest_tasks.py \
  tools/controlplane/src/controlplane_tool/loadtest_flows.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_tasks.py \
  tools/controlplane/src/controlplane_tool/loadtest_flows.py \
  tools/controlplane/src/controlplane_tool/loadtest_runner.py \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/loadtest_bootstrap.py \
  tools/controlplane/src/controlplane_tool/prometheus_runtime.py \
  tools/controlplane/src/controlplane_tool/grafana_runtime.py \
  tools/controlplane/src/controlplane_tool/k6_ops.py \
  tools/controlplane/src/controlplane_tool/metrics_gate.py \
  tools/controlplane/tests/test_loadtest_tasks.py \
  tools/controlplane/tests/test_loadtest_flows.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_prometheus_runtime.py \
  tools/controlplane/tests/test_grafana_runtime.py \
  tools/controlplane/tests/test_k6_ops.py \
  tools/controlplane/tests/test_metrics_gate.py
git commit -m "refactor: convert loadtest workflows to prefect flows"
```

---

### Task 7 / M7: Rebuild the TUI as a Prefect flow monitor

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py`
- Create: `tools/controlplane/tests/test_tui_prefect_bridge.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow.py`
- Modify: `tools/controlplane/src/controlplane_tool/console.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`

**Step 1: Write the failing tests**

Add tests that require the TUI to render Prefect-derived state instead of runner callbacks:

```python
def test_tui_bridge_maps_workflow_events_to_execution_panels() -> None:
    bridge = TuiPrefectBridge()
    bridge.handle_event(task_started("vm.ensure_running"))
    model = bridge.snapshot()
    assert model.phases[0].task_id == "vm.ensure_running"


def test_tui_log_panel_can_toggle_without_losing_buffer() -> None:
    bridge = TuiPrefectBridge()
    bridge.handle_event(log_line("images.build_core", "docker push ok"))
    bridge.toggle_logs()
    bridge.toggle_logs()
    assert "docker push ok" in bridge.snapshot().logs[-1]
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_tui_prefect_bridge.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_console_workflow.py -q
```

Expected: FAIL because the TUI is not yet consuming Prefect-backed events.

**Step 3: Write minimal implementation**

Implement:

- a bridge from normalized workflow events to the current Rich view model
- flow launch from TUI actions through the Prefect runtime facade
- persistent log buffering across `l` toggles
- display of:
  - semantic task IDs
  - task state
  - current task
  - streamed stdout and stderr lines

The TUI must continue to work without a Prefect server configured.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py \
  tools/controlplane/src/controlplane_tool/tui_app.py \
  tools/controlplane/src/controlplane_tool/tui_workflow.py \
  tools/controlplane/src/controlplane_tool/console.py \
  tools/controlplane/tests/test_tui_prefect_bridge.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_console_workflow.py
git commit -m "refactor: drive tui from prefect workflow events"
```

---

### Task 8 / M8: Add declarative flow composition and remote Prefect deployment readiness

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Create: `tools/controlplane/src/controlplane_tool/prefect_deployments.py`
- Create: `tools/controlplane/tests/test_flow_catalog.py`
- Create: `tools/controlplane/tests/test_prefect_deployments.py`
- Create: `tools/controlplane/prefect.yaml`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`
- Modify: `tools/controlplane/tests/test_profiles.py`

**Step 1: Write the failing tests**

Add tests that require declarative composition and optional deployment metadata:

```python
def test_flow_catalog_resolves_scenario_to_prefect_flow_definition() -> None:
    definition = resolve_flow_definition("k8s-vm")
    assert definition.flow_id == "e2e.k8s_vm"
    assert "vm.ensure_running" in definition.task_ids


def test_prefect_deployment_spec_is_optional_for_local_runs() -> None:
    deployment = build_prefect_deployment("e2e.k8s_vm", enabled=False)
    assert deployment is None
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_prefect_deployments.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_profiles.py -q
```

Expected: FAIL because no declarative flow catalog or deployment layer exists.

**Step 3: Write minimal implementation**

Implement:

- a flow catalog resolving names like `k8s-vm`, `cli`, `helm-stack`, `loadtest.quick`
- profile/scenario metadata that selects flows and overrides inputs without hardcoding orchestration
- `prefect.yaml` and helpers for optional deployment generation
- README documentation for:
  - local flow execution
  - optional remote deployment
  - required environment variables

Do not make remote deployments mandatory.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Then run:

```bash
uv run --project tools/controlplane --locked python -m py_compile \
  tools/controlplane/src/controlplane_tool/flow_catalog.py \
  tools/controlplane/src/controlplane_tool/prefect_deployments.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/flow_catalog.py \
  tools/controlplane/src/controlplane_tool/prefect_deployments.py \
  tools/controlplane/src/controlplane_tool/scenario_loader.py \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/src/controlplane_tool/profiles.py \
  tools/controlplane/README.md \
  tools/controlplane/prefect.yaml \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_prefect_deployments.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_profiles.py
git commit -m "feat: add declarative prefect flow catalog"
```

---

## Cross-Cutting Acceptance Gates

After each milestone:

- targeted pytest suite passes
- modified modules compile with `python -m py_compile`
- no newly introduced runner contains inline orchestration that duplicates a shared task
- logs continue to stream into the TUI execution panel
- local execution still works with no `PREFECT_API_URL`

Before calling the roadmap complete:

- run the full controlplane test suite:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
```

- smoke test these entrypoints manually:

```bash
uv run --project tools/controlplane --locked controlplane-tool vm inspect --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run k8s-vm --dry-run
uv run --project tools/controlplane --locked controlplane-tool loadtest run --dry-run
uv run --project tools/controlplane --locked controlplane-tool
```

- validate that the TUI can:
  - launch a flow
  - display task phases
  - stream stdout and stderr
  - toggle the log pane with `l`

## Migration Heuristics

- Prefer adapting existing modules before introducing new ones when responsibilities already exist.
- Do not wrap shell scripts in Prefect tasks as a permanent design. Replace shell orchestration with stable adapters first.
- Keep task IDs semantic and stable:
  - `vm.ensure_running`
  - `repo.sync_to_vm`
  - `images.build_core`
  - `k3s.install`
  - `k3s.configure_registry`
  - `helm.deploy_control_plane`
  - `tests.run_k8s_e2e`
- Do not let Prefect-specific objects leak into TUI rendering or domain models.
- Keep all infrastructure assets centralized under:
  - `ops/ansible/`
  - `helm/`
  - existing repo build files

## Risks To Watch

- introducing Prefect too deep into UI code instead of keeping a narrow runtime bridge
- preserving duplicated build/deploy logic under new task names
- accidentally making remote Prefect infrastructure mandatory for local developer workflows
- leaving the registry and k3s concerns coupled, which would block optimal `k8s-vm` ordering
- changing scenario semantics while refactoring orchestration

