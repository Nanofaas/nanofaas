# Two VM Loadtest Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `./scripts/controlplane.sh e2e run two-vm-loadtest`, an E2E workflow that provisions one Helm/k3s stack VM and one minimal k6 load generator VM, runs k6 against a selected function through the control-plane, captures Prometheus snapshots, writes a local report, and performs configurable cleanup.

**Architecture:** Add a new E2E scenario and a new workflow, but reuse existing component tasks wherever the responsibility already exists. The stack VM reuses the current Helm stack prelude components; the loadgen VM reuses `VmRequest`, `VmOrchestrator`, the existing k6 Ansible playbook, and the existing loadtest/report concepts with small adapters for remote k6 and Prometheus snapshots. Keep autoscaling out of scope.

**Tech Stack:** Python 3.12+, Typer, Pydantic, existing `controlplane_tool` scenario component system, Multipass, Ansible, k3s, Helm, k6, Prometheus HTTP API, pytest.

---

## Constraints

- DRY: a new workflow is allowed, but single tasks must reuse existing component planners or orchestration primitives when possible.
- The new scenario name is `two-vm-loadtest`.
- Default stack VM: `name=nanofaas-e2e`, `cpus=4`, `memory=8G`, `disk=30G`.
- Default loadgen VM: `name=nanofaas-e2e-loadgen`, `cpus=2`, `memory=2G`, `disk=10G`.
- Default function selection should match the Helm/loadtest stack: `function_preset=demo-loadtest`, load targets defaulting to the resolved scenario load targets.
- k6 invokes the selected function through the control-plane endpoint on the stack VM.
- k6 script policy:
  - repo default script gets default load settings;
  - custom script owns its `options` unless CLI overrides are explicit;
  - explicit `--vus`, `--duration`, or load profile flags win.
- Report is local under `tools/controlplane/runs/<timestamp>-two-vm-loadtest/`.
- Cleanup is configurable with existing `--cleanup-vm/--no-cleanup-vm`; when enabled, both Multipass VMs are removed after artifacts are copied.
- Prometheus queries include required FaaS metrics and best-effort platform metrics.
- Do not include `experiments/autoscaling.py`.
- Before editing any function/class/method symbol, run GitNexus impact analysis for that symbol with `direction="upstream"` and record risk in the implementation notes. If any result is HIGH or CRITICAL, stop and warn the user before editing.

---

### Task 1: Register Scenario Catalog And Defaults

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/functions/catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- Test: `tools/controlplane/tests/test_e2e_catalog.py`

**Step 1: Run impact analysis**

Use GitNexus before edits:

```text
mcp__gitnexus__.impact(repo="mcFaas", target="SCENARIOS", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="_default_selection_for", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="SCENARIO_FUNCTION_RUNTIME_ALLOWLIST", direction="upstream")
```

Expected: LOW/MEDIUM risk. If HIGH/CRITICAL, report before editing.

**Step 2: Write failing catalog tests**

In `tools/controlplane/tests/test_e2e_catalog.py`, update the expected scenario list and add explicit assertions:

```python
def test_catalog_lists_expected_suite_names() -> None:
    names = [scenario.name for scenario in list_scenarios()]
    assert names == [
        "docker",
        "buildpack",
        "container-local",
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "deploy-host",
        "helm-stack",
        "two-vm-loadtest",
    ]


def test_two_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("two-vm-loadtest")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes
```

Add a CLI default selection test to `tools/controlplane/tests/test_e2e_commands.py` if that file already covers `_resolve_run_request`; otherwise create one there:

```python
def test_two_vm_loadtest_defaults_to_demo_loadtest_selection() -> None:
    request = _resolve_run_request(
        scenario="two-vm-loadtest",
        runtime=None,
        lifecycle="multipass",
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="30G",
        cleanup_vm=True,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv=None,
        scenario_file=None,
        saved_profile=None,
    )

    assert request.scenario == "two-vm-loadtest"
    assert request.function_preset == "demo-loadtest"
    assert request.resolved_scenario is not None
    assert request.resolved_scenario.load.targets == []
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_commands.py -q
```

Expected: FAIL because `two-vm-loadtest` is unknown.

**Step 4: Implement catalog/defaults**

In `tools/controlplane/src/controlplane_tool/scenario/catalog.py`, append:

```python
    ScenarioDefinition(
        name="two-vm-loadtest",
        description="Two-VM Helm stack load test with a dedicated k6 generator.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
```

In `tools/controlplane/src/controlplane_tool/functions/catalog.py`, extend the runtime allowlist:

```python
SCENARIO_FUNCTION_RUNTIME_ALLOWLIST: dict[str, frozenset[FunctionRuntimeKind]] = {
    "helm-stack": frozenset({"java", "java-lite", "python", "exec"}),
    "two-vm-loadtest": frozenset({"java", "java-lite", "python", "exec"}),
}
```

In `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`, update `_default_selection_for`:

```python
    if scenario in {"helm-stack", "two-vm-loadtest"}:
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-loadtest")
```

**Step 5: Run tests to verify pass**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_commands.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/catalog.py tools/controlplane/src/controlplane_tool/functions/catalog.py tools/controlplane/src/controlplane_tool/cli/e2e_commands.py tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_e2e_commands.py
git commit -m "Add two-vm loadtest scenario catalog entry"
```

---

### Task 2: Add Request Model Fields For Loadgen VM And k6 Overrides

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- Test: `tools/controlplane/tests/test_e2e_commands.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="E2eRequest", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="_build_request", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="e2e_run", direction="upstream")
```

Expected: MEDIUM risk because request construction feeds multiple scenarios. Keep defaults backward-compatible.

**Step 2: Write failing CLI option tests**

In `tools/controlplane/tests/test_e2e_commands.py`:

```python
def test_two_vm_loadtest_builds_default_loadgen_request() -> None:
    request = _resolve_run_request(
        scenario="two-vm-loadtest",
        runtime=None,
        lifecycle="multipass",
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="30G",
        cleanup_vm=True,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv=None,
        scenario_file=None,
        saved_profile=None,
    )

    assert request.vm is not None
    assert request.vm.name == "nanofaas-e2e"
    assert request.vm.cpus == 4
    assert request.vm.memory == "8G"
    assert request.vm.disk == "30G"
    assert request.loadgen_vm is not None
    assert request.loadgen_vm.name == "nanofaas-e2e-loadgen"
    assert request.loadgen_vm.cpus == 2
    assert request.loadgen_vm.memory == "2G"
    assert request.loadgen_vm.disk == "10G"


def test_two_vm_loadtest_accepts_k6_override_fields() -> None:
    request = _build_request(
        scenario="two-vm-loadtest",
        runtime="java",
        lifecycle="multipass",
        name="stack",
        host=None,
        user="ubuntu",
        home=None,
        cpus=6,
        memory="10G",
        disk="40G",
        cleanup_vm=False,
        namespace=None,
        local_registry="localhost:5000",
        k6_script=Path("custom.js"),
        k6_vus=25,
        k6_duration="2m",
        k6_payload=Path("payload.json"),
        loadgen_name="loadgen",
        loadgen_cpus=3,
        loadgen_memory="3G",
        loadgen_disk="12G",
    )

    assert request.cleanup_vm is False
    assert request.k6_script == Path("custom.js")
    assert request.k6_vus == 25
    assert request.k6_duration == "2m"
    assert request.k6_payload == Path("payload.json")
    assert request.loadgen_vm is not None
    assert request.loadgen_vm.name == "loadgen"
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_commands.py -q
```

Expected: FAIL because fields/options do not exist.

**Step 4: Add request fields**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_models.py` add:

```python
    loadgen_vm: VmRequest | None = None
    k6_script: Path | None = None
    k6_vus: int | None = Field(default=None, ge=1)
    k6_duration: str | None = None
    k6_payload: Path | None = None
```

**Step 5: Add request construction arguments**

In `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`:

```python
def _default_loadgen_vm_request(
    *,
    lifecycle: str,
    user: str,
    home: str | None,
    name: str | None,
    cpus: int,
    memory: str,
    disk: str,
) -> VmRequest:
    return VmRequest(
        lifecycle=lifecycle,
        name=name or "nanofaas-e2e-loadgen",
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
    )
```

Update `_build_request` signature with:

```python
    loadgen_name: str | None = None,
    loadgen_cpus: int = 2,
    loadgen_memory: str = "2G",
    loadgen_disk: str = "10G",
    k6_script: Path | None = None,
    k6_vus: int | None = None,
    k6_duration: str | None = None,
    k6_payload: Path | None = None,
```

Use VM construction for both `helm-stack` and `two-vm-loadtest`:

```python
    if scenario in {"k3s-junit-curl", "cli", "cli-stack", "cli-host", "helm-stack", "two-vm-loadtest"}:
        vm = _build_vm_request(...)

    loadgen_vm = None
    if scenario == "two-vm-loadtest":
        loadgen_vm = _default_loadgen_vm_request(
            lifecycle=lifecycle,
            user=user,
            home=home,
            name=loadgen_name,
            cpus=loadgen_cpus,
            memory=loadgen_memory,
            disk=loadgen_disk,
        )
```

Return these on `E2eRequest`:

```python
        loadgen_vm=loadgen_vm,
        k6_script=k6_script,
        k6_vus=k6_vus,
        k6_duration=k6_duration,
        k6_payload=k6_payload,
```

Thread the same args through `_resolve_run_request` and `e2e_run`. Add Typer options:

```python
    loadgen_name: str | None = typer.Option(None, "--loadgen-name"),
    loadgen_cpus: int = typer.Option(2, "--loadgen-cpus", min=1),
    loadgen_memory: str = typer.Option("2G", "--loadgen-memory"),
    loadgen_disk: str = typer.Option("10G", "--loadgen-disk"),
    k6_script: Path | None = typer.Option(None, "--k6-script"),
    k6_vus: int | None = typer.Option(None, "--vus", min=1),
    k6_duration: str | None = typer.Option(None, "--duration"),
    k6_payload: Path | None = typer.Option(None, "--k6-payload"),
```

**Step 6: Run tests to verify pass**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_commands.py -q
```

Expected: PASS.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/e2e_models.py tools/controlplane/src/controlplane_tool/cli/e2e_commands.py tools/controlplane/tests/test_e2e_commands.py
git commit -m "Add loadgen VM options to e2e requests"
```

---

### Task 3: Add Two-VM Recipe And Task IDs Without Duplicating Helm Stack Tasks

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/composer.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py`
- Test: `tools/controlplane/tests/test_scenario_flows.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="build_scenario_recipe", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="compose_recipe", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="scenario_task_ids", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="build_scenario_flow", direction="upstream")
```

Expected: MEDIUM risk; direct test coverage exists.

**Step 2: Write failing recipe tests**

In `tools/controlplane/tests/test_scenario_flows.py`:

```python
def test_two_vm_loadtest_recipe_reuses_helm_stack_platform_prefix() -> None:
    two_vm_recipe = build_scenario_recipe("two-vm-loadtest")
    helm_recipe = build_scenario_recipe("helm-stack")

    helm_prefix = tuple(
        component_id
        for component_id in helm_recipe.component_ids
        if component_id not in {"loadtest.install_k6", "loadtest.run", "experiments.autoscaling"}
    )

    assert two_vm_recipe.component_ids[: len(helm_prefix)] == helm_prefix
    assert two_vm_recipe.component_ids[len(helm_prefix):] == (
        "loadgen.ensure_running",
        "loadgen.provision_base",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    )


def test_two_vm_loadtest_flow_task_ids_are_derived_from_recipe() -> None:
    flow = build_scenario_flow(
        "two-vm-loadtest",
        repo_root=Path("/repo"),
        request=E2eRequest(
            scenario="two-vm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
            loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
        ),
    )
    recipe = build_scenario_recipe("two-vm-loadtest")

    assert flow.task_ids == [component.component_id for component in compose_recipe(recipe)]
```

In `tools/controlplane/tests/test_e2e_runner.py`:

```python
def test_two_vm_loadtest_plan_has_distinct_stack_and_loadgen_steps() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="two-vm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e", cpus=4, memory="8G", disk="30G"),
            loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen", cpus=2, memory="2G", disk="10G"),
        )
    )

    assert [step.step_id for step in plan.steps[-5:]] == [
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
    ] or "vm.down" in [step.step_id for step in plan.steps]
```

Prefer exact full list assertions after implementation stabilizes.

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: FAIL because recipe/components are unknown.

**Step 4: Add the recipe**

In `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`, add `two-vm-loadtest`. Reuse the Helm platform prefix, but do not include `loadtest.install_k6`, `loadtest.run`, or `experiments.autoscaling`:

```python
    "two-vm-loadtest": ScenarioRecipe(
        name="two-vm-loadtest",
        component_ids=(
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "images.build_selected_functions",
            "k3s.install",
            "k3s.configure_registry",
            "namespace.install",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
            "loadgen.ensure_running",
            "loadgen.provision_base",
            "loadgen.install_k6",
            "loadgen.run_k6",
            "metrics.prometheus_snapshot",
            "loadtest.write_report",
            "loadgen.down",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
```

**Step 5: Register placeholder components**

In `composer.py`, register new components from a new module created in Task 5. For this task, create stubs in `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`:

```python
from __future__ import annotations

from controlplane_tool.scenario.components.models import ScenarioComponentDefinition


def _not_ready(_context):
    raise NotImplementedError("two-vm loadtest component is not implemented yet")


LOADGEN_ENSURE_RUNNING = ScenarioComponentDefinition("loadgen.ensure_running", "Ensure loadgen VM is running", _not_ready)
LOADGEN_PROVISION_BASE = ScenarioComponentDefinition("loadgen.provision_base", "Provision loadgen base dependencies", _not_ready)
LOADGEN_INSTALL_K6 = ScenarioComponentDefinition("loadgen.install_k6", "Install k6 on loadgen VM", _not_ready)
LOADGEN_RUN_K6 = ScenarioComponentDefinition("loadgen.run_k6", "Run k6 from loadgen VM", _not_ready)
PROMETHEUS_SNAPSHOT = ScenarioComponentDefinition("metrics.prometheus_snapshot", "Capture Prometheus query snapshots", _not_ready)
LOADTEST_WRITE_REPORT = ScenarioComponentDefinition("loadtest.write_report", "Write two-VM loadtest report", _not_ready)
LOADGEN_DOWN = ScenarioComponentDefinition("loadgen.down", "Tear down loadgen VM", _not_ready)
```

Update `_load_all_components()` to import and register these constants.

**Step 6: Route flow and runner plan**

Update `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py` so request-backed `two-vm-loadtest` works through `E2eRunner`, like `k3s-junit-curl`.

Update `E2eRunner.plan()` in `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`:

```python
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack", "two-vm-loadtest"}:
```

**Step 7: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: recipe/flow tests pass where they inspect task IDs; execution tests should not call stub actions yet.

**Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/recipes.py tools/controlplane/src/controlplane_tool/scenario/components/composer.py tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "Add two-vm loadtest scenario recipe"
```

---

### Task 4: Add Loadgen Context Resolution Without Changing Stack VM Components

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/environment.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_components.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="ScenarioExecutionContext", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="resolve_scenario_environment", direction="upstream")
```

Expected: MEDIUM risk. Add fields with defaults only.

**Step 2: Write failing context tests**

Create `tools/controlplane/tests/test_two_vm_loadtest_components.py`:

```python
from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
from controlplane_tool.scenario.components.two_vm_loadtest import loadgen_vm_request


def test_two_vm_loadtest_context_exposes_loadgen_vm() -> None:
    request = E2eRequest(
        scenario="two-vm-loadtest",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen", cpus=2, memory="2G", disk="10G"),
    )

    context = resolve_scenario_environment(Path("/repo"), request)

    assert context.loadgen_vm_request is not None
    assert context.loadgen_vm_request.name == "nanofaas-e2e-loadgen"
    assert loadgen_vm_request(context).name == "nanofaas-e2e-loadgen"
```

**Step 3: Run test to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: FAIL because `loadgen_vm_request` and context field do not exist.

**Step 4: Extend context**

In `ScenarioExecutionContext`, add:

```python
    loadgen_vm_request: VmRequest | None = None
```

In `resolve_scenario_environment()`, pass:

```python
        loadgen_vm_request=getattr(request, "loadgen_vm", None),
```

In `two_vm_loadtest.py`, add:

```python
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext


def loadgen_vm_request(context: ScenarioExecutionContext) -> VmRequest:
    if context.loadgen_vm_request is not None:
        return context.loadgen_vm_request
    return VmRequest(
        lifecycle=context.vm_request.lifecycle,
        name="nanofaas-e2e-loadgen",
        user=context.vm_request.user,
        home=context.vm_request.home,
        cpus=2,
        memory="2G",
        disk="10G",
    )
```

**Step 5: Run test**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/environment.py tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/tests/test_two_vm_loadtest_components.py
git commit -m "Expose loadgen VM in scenario context"
```

---

### Task 5: Implement Loadgen VM Provisioning Components By Reusing Existing Ansible Helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_components.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="plan_vm_ensure_running", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="plan_vm_provision_base", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="plan_loadtest_install_k6", direction="upstream")
```

Expected: LOW/MEDIUM. The implementation will call these planners with a copied context; do not duplicate their command construction.

**Step 2: Write failing provisioning tests**

Add to `tools/controlplane/tests/test_two_vm_loadtest_components.py`:

```python
from controlplane_tool.scenario.components.two_vm_loadtest import (
    plan_loadgen_ensure_running,
    plan_loadgen_install_k6,
    plan_loadgen_provision_base,
)


def _context():
    request = E2eRequest(
        scenario="two-vm-loadtest",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e", cpus=4, memory="8G", disk="30G"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen", cpus=2, memory="2G", disk="10G"),
    )
    return resolve_scenario_environment(Path("/repo"), request)


def test_loadgen_ensure_running_uses_loadgen_vm_resources() -> None:
    op = plan_loadgen_ensure_running(_context())[0]

    assert op.operation_id == "loadgen.ensure_running"
    assert op.argv == (
        "multipass",
        "launch",
        "--name",
        "nanofaas-e2e-loadgen",
        "--cpus",
        "2",
        "--memory",
        "2G",
        "--disk",
        "10G",
    )


def test_loadgen_provision_base_does_not_install_helm() -> None:
    op = plan_loadgen_provision_base(_context())[0]
    rendered = " ".join(op.argv)

    assert op.operation_id == "loadgen.provision_base"
    assert "provision-base.yml" in rendered
    assert "install_helm=false" in rendered


def test_loadgen_install_k6_reuses_install_k6_playbook() -> None:
    op = plan_loadgen_install_k6(_context())[0]

    assert op.operation_id == "loadgen.install_k6"
    assert "install-k6.yml" in " ".join(op.argv)
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: FAIL because planners are stubs.

**Step 4: Implement by copying context, not commands**

In `two_vm_loadtest.py`:

```python
from dataclasses import replace

from controlplane_tool.scenario.components.bootstrap import (
    plan_loadtest_install_k6,
    plan_vm_ensure_running,
    plan_vm_provision_base,
)
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation


def _loadgen_context(context: ScenarioExecutionContext) -> ScenarioExecutionContext:
    return replace(context, vm_request=loadgen_vm_request(context))


def _retag(operation: RemoteCommandOperation, *, operation_id: str, summary: str) -> RemoteCommandOperation:
    return RemoteCommandOperation(
        operation_id=operation_id,
        summary=summary,
        argv=operation.argv,
        env=operation.env,
        execution_target=operation.execution_target,
    )


def plan_loadgen_ensure_running(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    operation = plan_vm_ensure_running(_loadgen_context(context))[0]
    return (_retag(operation, operation_id="loadgen.ensure_running", summary="Ensure loadgen VM is running"),)


def plan_loadgen_provision_base(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    operation = plan_vm_provision_base(_loadgen_context(context))[0]
    argv = tuple("install_helm=false" if part == "install_helm=true" else part for part in operation.argv)
    return (
        RemoteCommandOperation(
            operation_id="loadgen.provision_base",
            summary="Provision loadgen base dependencies",
            argv=argv,
            env=operation.env,
            execution_target=operation.execution_target,
        ),
    )


def plan_loadgen_install_k6(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    operation = plan_loadtest_install_k6(_loadgen_context(context))[0]
    return (_retag(operation, operation_id="loadgen.install_k6", summary="Install k6 on loadgen VM"),)
```

Update component constants to use these planners.

**Step 5: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/tests/test_two_vm_loadtest_components.py
git commit -m "Reuse VM planners for loadgen provisioning"
```

---

### Task 6: Add Remote k6 Command Builder And Default Script

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py`
- Create: `tools/controlplane/assets/k6/two-vm-function-invoke.js`
- Test: `tools/controlplane/tests/test_remote_k6.py`

**Step 1: Run impact analysis**

No existing symbol is edited in this task, but query context before reusing k6 semantics:

```text
mcp__gitnexus__.context(repo="mcFaas", name="K6Ops")
```

**Step 2: Write failing tests**

Create `tools/controlplane/tests/test_remote_k6.py`:

```python
from pathlib import Path

from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command


def test_default_script_gets_stage_profile_and_env() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/default.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        payload_path=None,
        stages=(("15s", 1), ("30s", 3)),
        custom_script=False,
    )

    command = build_k6_command(config)

    assert command[:4] == ("k6", "run", "--summary-export", "/remote/k6-summary.json")
    assert "--stage" in command
    assert "15s:1" in command
    assert "-e" in command
    assert "NANOFAAS_URL=http://10.0.0.1:8080" in command
    assert "NANOFAAS_FUNCTION=word-stats-java" in command
    assert command[-1] == "/remote/default.js"


def test_custom_script_omits_default_stages_unless_cli_overrides_are_explicit() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/custom.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        payload_path=Path("/remote/payload.json"),
        stages=(("15s", 1),),
        custom_script=True,
        vus=None,
        duration=None,
    )

    command = build_k6_command(config)

    assert "--stage" not in command
    assert "--vus" not in command
    assert "--duration" not in command
    assert "NANOFAAS_PAYLOAD=/remote/payload.json" in command


def test_explicit_cli_vus_and_duration_override_custom_script() -> None:
    config = RemoteK6RunConfig(
        script_path=Path("/remote/custom.js"),
        summary_path=Path("/remote/k6-summary.json"),
        control_plane_url="http://10.0.0.1:8080",
        function_name="word-stats-java",
        custom_script=True,
        vus=25,
        duration="2m",
    )

    command = build_k6_command(config)

    assert "--vus" in command
    assert "25" in command
    assert "--duration" in command
    assert "2m" in command
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_remote_k6.py -q
```

Expected: FAIL because module does not exist.

**Step 4: Implement command builder**

Create `tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class RemoteK6RunConfig:
    script_path: Path
    summary_path: Path
    control_plane_url: str
    function_name: str
    payload_path: Path | None = None
    stages: tuple[tuple[str, int], ...] = ()
    custom_script: bool = False
    vus: int | None = None
    duration: str | None = None


def build_k6_command(config: RemoteK6RunConfig) -> tuple[str, ...]:
    args: list[str] = [
        "k6",
        "run",
        "--summary-export",
        str(config.summary_path),
    ]
    if config.vus is not None:
        args.extend(["--vus", str(config.vus)])
    if config.duration is not None:
        args.extend(["--duration", config.duration])
    if not config.custom_script and config.vus is None and config.duration is None:
        for duration, target in config.stages:
            args.extend(["--stage", f"{duration}:{target}"])
    env = {
        "NANOFAAS_URL": config.control_plane_url,
        "NANOFAAS_FUNCTION": config.function_name,
    }
    if config.payload_path is not None:
        env["NANOFAAS_PAYLOAD"] = str(config.payload_path)
    for key, value in env.items():
        args.extend(["-e", f"{key}={value}"])
    args.append(str(config.script_path))
    return tuple(args)
```

**Step 5: Add default script**

Create `tools/controlplane/assets/k6/two-vm-function-invoke.js` based on the existing asset, but allow payload from `NANOFAAS_PAYLOAD`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { open } from 'k6/fs';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:8080';
const FUNCTION_NAME = __ENV.NANOFAAS_FUNCTION || 'word-stats-java';
const PAYLOAD_PATH = __ENV.NANOFAAS_PAYLOAD || '';

function payload() {
    if (PAYLOAD_PATH) {
        return open(PAYLOAD_PATH);
    }
    return JSON.stringify({
        input: { text: 'the quick brown fox jumps over the lazy dog', seq: __ITER },
        metadata: { source: 'two-vm-loadtest' },
    });
}

export const options = {
    thresholds: {
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],
        http_req_failed: ['rate<0.15'],
    },
};

export default function () {
    const url = `${BASE_URL}/v1/functions/${FUNCTION_NAME}:invoke`;
    const res = http.post(url, payload(), {
        headers: { 'Content-Type': 'application/json' },
        timeout: '20s',
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has success response': (r) => {
            if (r.status !== 200) {
                return false;
            }
            try {
                const body = JSON.parse(r.body);
                return body && body.status === 'success';
            } catch (e) {
                return false;
            }
        },
    });

    sleep(0.1);
}
```

If the installed k6 version does not support `k6/fs`, use plain `open()` without import. Verify against local k6 documentation or existing test compatibility during implementation.

**Step 6: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_remote_k6.py -q
```

Expected: PASS.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py tools/controlplane/assets/k6/two-vm-function-invoke.js tools/controlplane/tests/test_remote_k6.py
git commit -m "Add remote k6 command builder"
```

---

### Task 7: Implement Remote k6 Execution Component

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_components.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="operation_to_plan_step", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="plan_recipe_steps", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="E2eRunner.execute", direction="upstream")
```

Expected: MEDIUM. Avoid changing generic execution if possible; prefer action callbacks for the two-VM steps.

**Step 2: Write failing component planning tests**

Add:

```python
from controlplane_tool.scenario.components.two_vm_loadtest import plan_loadgen_run_k6


def test_loadgen_run_k6_operation_is_vm_targeted() -> None:
    context = _context()
    operation = plan_loadgen_run_k6(context)[0]

    assert operation.operation_id == "loadgen.run_k6"
    assert operation.execution_target == "loadgen"
    assert "k6" in operation.argv
```

**Step 3: Write failing execution test**

In `tools/controlplane/tests/test_e2e_runner.py`:

```python
def test_two_vm_loadtest_executes_k6_on_loadgen_vm_and_resolves_stack_host() -> None:
    class CapturingRunner(E2eRunner):
        pass

    shell = RecordingShell()
    runner = CapturingRunner(repo_root=Path("/repo"), shell=shell, host_resolver=lambda request: "10.0.0.2" if request.name == "nanofaas-e2e-loadgen" else "10.0.0.1")
    plan = runner.plan(
        E2eRequest(
            scenario="two-vm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
            loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
            cleanup_vm=False,
        )
    )

    k6_step = next(step for step in plan.steps if step.step_id == "loadgen.run_k6")

    assert k6_step.action is not None
```

This first locks the plan shape; add a stricter execution assertion after callbacks are wired.

**Step 4: Implement loadgen operation**

In `two_vm_loadtest.py`, add helpers:

```python
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command


def _target_functions(context: ScenarioExecutionContext) -> list[str]:
    scenario = context.resolved_scenario
    if scenario is None:
        return ["word-stats-java"]
    if scenario.load.targets:
        return list(scenario.load.targets)
    return list(scenario.function_keys)


def _load_profile_stages(context: ScenarioExecutionContext) -> tuple[tuple[str, int], ...]:
    profile_name = "quick"
    if context.resolved_scenario is not None and context.resolved_scenario.load.load_profile_name:
        profile_name = context.resolved_scenario.load.load_profile_name
    profile = resolve_load_profile(profile_name)
    return tuple((stage.duration, stage.target) for stage in profile.stages)
```

Implement `plan_loadgen_run_k6` as a marker operation. It will be converted to an action by the runner:

```python
def plan_loadgen_run_k6(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    target = _target_functions(context)[0]
    command = build_k6_command(
        RemoteK6RunConfig(
            script_path=Path("<remote-k6-script>"),
            summary_path=Path("<remote-k6-summary>"),
            control_plane_url="http://<stack-vm>:8080",
            function_name=target,
            stages=_load_profile_stages(context),
            custom_script=bool(getattr(context.request, "k6_script", None)),
            vus=getattr(context.request, "k6_vus", None),
            duration=getattr(context.request, "k6_duration", None),
        )
    )
    return (
        RemoteCommandOperation(
            operation_id="loadgen.run_k6",
            summary="Run k6 from loadgen VM",
            argv=command,
            execution_target="loadgen",
        ),
    )
```

**Step 5: Wire custom action in recipe planning**

In `plan_recipe_steps()` in `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, add an `on_loadgen_exec` callback, but only for `two-vm-loadtest`.

Do not change normal `vm` operation handling. Extend `operations_to_plan_steps()` only if needed; the lower-risk approach is:

```python
def _on_loadgen_run_k6() -> None:
    from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

    TwoVmLoadtestRunner(
        repo_root=repo_root,
        vm=runner.vm,
        shell=runner.shell,
    ).run_k6(request)
```

Then in the loop, when the operation ID is `loadgen.run_k6`, directly append:

```python
ScenarioPlanStep(
    summary="Run k6 from loadgen VM",
    command=list(operation.argv),
    step_id="loadgen.run_k6",
    action=_on_loadgen_run_k6,
)
```

Create `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py` with the actual implementation in Task 8. For now, make `run_k6()` raise `NotImplementedError` and tests should only assert action wiring.

**Step 6: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: PASS for plan/action tests.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "Plan remote k6 execution for two-vm loadtest"
```

---

### Task 8: Implement TwoVmLoadtestRunner For Artifact Copy, Remote Execution, And Cleanup

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_runner.py`
- Test: `tools/controlplane/tests/test_vm_adapter.py` or existing VM adapter test file

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="VmOrchestrator", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="exec_argv", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="teardown", direction="upstream")
```

Expected: MEDIUM. Add methods; do not change existing method behavior.

**Step 2: Add VM transfer helpers tests**

Find the VM adapter test file (`rg -n "VmOrchestrator" tools/controlplane/tests`). Add tests:

```python
def test_vm_transfer_to_builds_multipass_transfer_command() -> None:
    shell = RecordingShell()
    vm = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    result = vm.transfer_to(
        VmRequest(lifecycle="multipass", name="loadgen"),
        source=Path("/repo/tools/controlplane/assets/k6/two-vm-function-invoke.js"),
        destination="/home/ubuntu/two-vm-loadtest/script.js",
        dry_run=True,
    )

    assert result.command == [
        "multipass",
        "transfer",
        "/repo/tools/controlplane/assets/k6/two-vm-function-invoke.js",
        "loadgen:/home/ubuntu/two-vm-loadtest/script.js",
    ]


def test_vm_transfer_from_builds_multipass_transfer_command() -> None:
    shell = RecordingShell()
    vm = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    result = vm.transfer_from(
        VmRequest(lifecycle="multipass", name="loadgen"),
        source="/home/ubuntu/two-vm-loadtest/k6-summary.json",
        destination=Path("/repo/tools/controlplane/runs/run/k6-summary.json"),
        dry_run=True,
    )

    assert result.command == [
        "multipass",
        "transfer",
        "loadgen:/home/ubuntu/two-vm-loadtest/k6-summary.json",
        "/repo/tools/controlplane/runs/run/k6-summary.json",
    ]
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_vm_adapter.py -q
```

Expected: FAIL because transfer helpers do not exist. Use the actual test path if different.

**Step 4: Implement transfer helpers**

In `VmOrchestrator`, add non-breaking methods:

```python
    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", str(source), f"{request.user}@{request.host}:{destination}"],
                dry_run=dry_run,
            )
        name = self._vm_name(request)
        command = ["multipass", "transfer", str(source), f"{name}:{destination}"]
        if dry_run:
            return _ok(command)
        return self._shell_run(command, dry_run=False)

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", f"{request.user}@{request.host}:{source}", str(destination)],
                dry_run=dry_run,
            )
        name = self._vm_name(request)
        command = ["multipass", "transfer", f"{name}:{source}", str(destination)]
        if dry_run:
            return _ok(command)
        return self._shell_run(command, dry_run=False)
```

**Step 5: Add runner tests**

Create `tools/controlplane/tests/test_two_vm_loadtest_runner.py`:

```python
from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.core.shell_backend import RecordingShell


def test_runner_executes_k6_on_loadgen_with_stack_control_plane_url(tmp_path: Path) -> None:
    shell = RecordingShell()
    runner = TwoVmLoadtestRunner(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request: "10.0.0.2" if request.name == "nanofaas-e2e-loadgen" else "10.0.0.1",
        runs_root=tmp_path,
    )
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )

    result = runner.run_k6(request)

    rendered = [" ".join(command) for command in shell.commands]
    assert any("multipass exec nanofaas-e2e-loadgen" in command for command in rendered)
    assert any("NANOFAAS_URL=http://10.0.0.1:8080" in command for command in rendered)
    assert result.k6_summary_path.name == "k6-summary.json"
```

**Step 6: Implement runner**

Create/modify `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from controlplane_tool.core.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command
from controlplane_tool.workspace.paths import ToolPaths


@dataclass(frozen=True, slots=True)
class TwoVmK6Result:
    run_dir: Path
    k6_summary_path: Path
    target_function: str


class TwoVmLoadtestRunner:
    def __init__(
        self,
        repo_root: Path,
        *,
        vm: VmOrchestrator | None = None,
        shell: ShellBackend | None = None,
        host_resolver=None,
        runs_root: Path | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        self.shell = shell or SubprocessShell()
        self.vm = vm or VmOrchestrator(self.repo_root, shell=self.shell)
        self.host_resolver = host_resolver
        self.runs_root = runs_root or self.paths.runs_dir

    def _host(self, request: VmRequest) -> str:
        if self.host_resolver is not None:
            return self.host_resolver(request)
        return self.vm.connection_host(request)

    def _run_dir(self) -> Path:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        run_dir = self.runs_root / f"{timestamp}-two-vm-loadtest"
        run_dir.mkdir(parents=True, exist_ok=True)
        return run_dir

    def _target_function(self, request: E2eRequest) -> str:
        scenario = request.resolved_scenario
        if scenario is None:
            return "word-stats-java"
        if scenario.load.targets:
            return scenario.load.targets[0]
        return scenario.function_keys[0]

    def run_k6(self, request: E2eRequest) -> TwoVmK6Result:
        if request.vm is None or request.loadgen_vm is None:
            raise ValueError("two-vm-loadtest requires stack and loadgen VM requests")
        run_dir = self._run_dir()
        remote_dir = f"{self.vm.remote_home(request.loadgen_vm)}/two-vm-loadtest"
        self.vm.exec_argv(request.loadgen_vm, ("mkdir", "-p", remote_dir))

        local_script = request.k6_script or (self.repo_root / "tools/controlplane/assets/k6/two-vm-function-invoke.js")
        remote_script = f"{remote_dir}/{local_script.name}"
        self.vm.transfer_to(request.loadgen_vm, source=local_script, destination=remote_script)

        remote_payload = None
        if request.k6_payload is not None:
            remote_payload = f"{remote_dir}/{request.k6_payload.name}"
            self.vm.transfer_to(request.loadgen_vm, source=request.k6_payload, destination=remote_payload)

        load_profile_name = (
            request.resolved_scenario.load.load_profile_name
            if request.resolved_scenario is not None and request.resolved_scenario.load.load_profile_name
            else "quick"
        )
        load_profile = resolve_load_profile(load_profile_name)
        remote_summary = f"{remote_dir}/k6-summary.json"
        command = build_k6_command(
            RemoteK6RunConfig(
                script_path=Path(remote_script),
                summary_path=Path(remote_summary),
                control_plane_url=f"http://{self._host(request.vm)}:8080",
                function_name=self._target_function(request),
                payload_path=Path(remote_payload) if remote_payload else None,
                stages=tuple((stage.duration, stage.target) for stage in load_profile.stages),
                custom_script=request.k6_script is not None,
                vus=request.k6_vus,
                duration=request.k6_duration,
            )
        )
        result = self.vm.exec_argv(request.loadgen_vm, command, cwd=remote_dir)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "k6 failed")

        local_summary = run_dir / "k6-summary.json"
        self.vm.transfer_from(request.loadgen_vm, source=remote_summary, destination=local_summary)
        return TwoVmK6Result(run_dir=run_dir, k6_summary_path=local_summary, target_function=self._target_function(request))
```

**Step 7: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_two_vm_loadtest_runner.py -q
```

Expected: PASS.

**Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py tools/controlplane/tests/test_vm_adapter.py tools/controlplane/tests/test_two_vm_loadtest_runner.py
git commit -m "Run k6 from dedicated loadgen VM"
```

---

### Task 9: Capture Prometheus Query Snapshots

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest/prometheus_snapshots.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`
- Test: `tools/controlplane/tests/test_prometheus_snapshots.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_components.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="query_prometheus_range_series", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="query_prometheus_metric_names", direction="upstream")
```

Expected: LOW; reuse functions from `loadtest.metrics`.

**Step 2: Write failing tests**

Create `tools/controlplane/tests/test_prometheus_snapshots.py`:

```python
from controlplane_tool.loadtest.prometheus_snapshots import (
    PROMETHEUS_QUERIES,
    PrometheusQuerySpec,
    classify_snapshot_results,
)


def test_prometheus_query_catalog_has_required_faas_metrics() -> None:
    required = [query for query in PROMETHEUS_QUERIES if query.required]

    assert any(query.name == "function_invocations_total" for query in required)
    assert any(query.name == "function_errors_total" for query in required)


def test_missing_required_query_fails_snapshot_classification() -> None:
    ok, detail = classify_snapshot_results(
        [
            PrometheusQuerySpec(name="function_invocations_total", query="function_invocations_total", required=True),
            PrometheusQuerySpec(name="pod_cpu_usage", query="container_cpu_usage_seconds_total", required=False),
        ],
        {"pod_cpu_usage": []},
    )

    assert ok is False
    assert "function_invocations_total" in detail
```

**Step 3: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_prometheus_snapshots.py -q
```

Expected: FAIL because module does not exist.

**Step 4: Implement query catalog and snapshot function**

Create `prometheus_snapshots.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import json

from controlplane_tool.loadtest.metrics import query_prometheus_range_series


@dataclass(frozen=True, slots=True)
class PrometheusQuerySpec:
    name: str
    query: str
    required: bool = False


PROMETHEUS_QUERIES: tuple[PrometheusQuerySpec, ...] = (
    PrometheusQuerySpec("function_invocations_total", "function_invocations_total", True),
    PrometheusQuerySpec("function_errors_total", "function_errors_total", True),
    PrometheusQuerySpec("function_duration_ms", "function_duration_ms", False),
    PrometheusQuerySpec("control_plane_cpu", "process_cpu_usage", False),
    PrometheusQuerySpec("jvm_memory_used", "jvm_memory_used_bytes", False),
    PrometheusQuerySpec("pod_count", "count(kube_pod_info)", False),
    PrometheusQuerySpec("pod_cpu_usage", "sum(rate(container_cpu_usage_seconds_total[1m]))", False),
    PrometheusQuerySpec("pod_memory_usage", "sum(container_memory_working_set_bytes)", False),
)


def classify_snapshot_results(
    specs: list[PrometheusQuerySpec] | tuple[PrometheusQuerySpec, ...],
    results: dict[str, list[dict[str, float | str]]],
) -> tuple[bool, str]:
    missing = [spec.name for spec in specs if spec.required and not results.get(spec.name)]
    if missing:
        return (False, "missing required prometheus data: " + ", ".join(missing))
    return (True, "prometheus snapshots captured")


def capture_prometheus_snapshots(
    *,
    base_url: str,
    start: datetime,
    end: datetime,
    output_path: Path,
    specs: tuple[PrometheusQuerySpec, ...] = PROMETHEUS_QUERIES,
) -> tuple[bool, str]:
    results: dict[str, list[dict[str, float | str]]] = {}
    errors: dict[str, str] = {}
    for spec in specs:
        try:
            results[spec.name] = query_prometheus_range_series(
                base_url,
                spec.query,
                start,
                end,
            )
        except RuntimeError as exc:
            errors[spec.name] = str(exc)
            results[spec.name] = []
    payload = {
        "prometheus_url": base_url,
        "queries": [spec.__dict__ for spec in specs],
        "results": results,
        "errors": errors,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    ok, detail = classify_snapshot_results(specs, results)
    if not ok:
        return (False, detail)
    return (True, detail if not errors else f"{detail}; optional query errors: {', '.join(errors)}")
```

**Step 5: Add component marker**

In `two_vm_loadtest.py`, implement:

```python
def plan_prometheus_snapshot(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="metrics.prometheus_snapshot",
            summary="Capture Prometheus query snapshots",
            argv=("python", "-m", "controlplane_tool.loadtest.prometheus_snapshots"),
            execution_target="host",
        ),
    )
```

Wire it to an action in `plan_recipe_steps()` similar to `loadgen.run_k6`, calling `TwoVmLoadtestRunner.capture_prometheus(request)`.

**Step 6: Implement runner capture**

Add to `TwoVmLoadtestRunner`:

```python
def capture_prometheus(self, request: E2eRequest) -> tuple[bool, str]:
    if request.vm is None:
        raise ValueError("two-vm-loadtest requires stack VM request")
    run_dir = self.current_or_create_run_dir()
    return capture_prometheus_snapshots(
        base_url=f"http://{self._host(request.vm)}:9090",
        start=self.started_at,
        end=datetime.now(timezone.utc),
        output_path=run_dir / "metrics" / "prometheus-snapshots.json",
    )
```

Prefer a shared per-plan runner instance so `started_at` and `run_dir` match k6 execution. If that is awkward in the existing planner, store the run directory path in a small `TwoVmLoadtestState` object captured by the plan actions.

**Step 7: Run tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_prometheus_snapshots.py tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: PASS.

**Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/loadtest/prometheus_snapshots.py tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py tools/controlplane/tests/test_prometheus_snapshots.py tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_e2e_runner.py
git commit -m "Capture Prometheus snapshots for two-vm loadtest"
```

---

### Task 10: Write Complete Local Report

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/loadtest_tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/templates/report.html.j2`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Test: `tools/controlplane/tests/test_loadtest_report.py` or create `tools/controlplane/tests/test_two_vm_loadtest_report.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="write_loadtest_report_task", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="render_report", direction="upstream")
```

Expected: LOW/MEDIUM. Keep existing report schema backward-compatible.

**Step 2: Write failing report test**

Create `tools/controlplane/tests/test_two_vm_loadtest_report.py`:

```python
import json
from pathlib import Path

from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestReport, write_two_vm_report


def test_two_vm_report_writes_summary_with_vm_k6_and_prometheus(tmp_path: Path) -> None:
    report = TwoVmLoadtestReport(
        final_status="passed",
        stack_vm={"name": "nanofaas-e2e", "host": "10.0.0.1", "cpus": 4, "memory": "8G", "disk": "30G"},
        loadgen_vm={"name": "nanofaas-e2e-loadgen", "host": "10.0.0.2", "cpus": 2, "memory": "2G", "disk": "10G"},
        target_function="word-stats-java",
        k6_summary_path=tmp_path / "k6-summary.json",
        prometheus_snapshot_path=tmp_path / "metrics" / "prometheus-snapshots.json",
        script_path=Path("tools/controlplane/assets/k6/two-vm-function-invoke.js"),
        payload_path=None,
    )

    summary_path = write_two_vm_report(report, tmp_path)

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    assert summary["final_status"] == "passed"
    assert summary["two_vm_loadtest"]["stack_vm"]["name"] == "nanofaas-e2e"
    assert summary["two_vm_loadtest"]["loadgen_vm"]["name"] == "nanofaas-e2e-loadgen"
    assert summary["two_vm_loadtest"]["target_function"] == "word-stats-java"
```

**Step 3: Run test to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_report.py -q
```

Expected: FAIL because report model/function does not exist.

**Step 4: Implement two-VM report model and writer**

In `two_vm_loadtest_runner.py`:

```python
from dataclasses import asdict
import json
from controlplane_tool.loadtest.report import render_report


@dataclass(frozen=True, slots=True)
class TwoVmLoadtestReport:
    final_status: str
    stack_vm: dict[str, object]
    loadgen_vm: dict[str, object]
    target_function: str
    k6_summary_path: Path
    prometheus_snapshot_path: Path
    script_path: Path
    payload_path: Path | None


def write_two_vm_report(report: TwoVmLoadtestReport, run_dir: Path) -> Path:
    summary = {
        "profile_name": "two-vm-loadtest",
        "run_dir": str(run_dir),
        "final_status": report.final_status,
        "steps": [],
        "metrics": {},
        "two_vm_loadtest": {
            **asdict(report),
            "k6_summary_path": str(report.k6_summary_path),
            "prometheus_snapshot_path": str(report.prometheus_snapshot_path),
            "script_path": str(report.script_path),
            "payload_path": str(report.payload_path) if report.payload_path else None,
        },
    }
    destination = run_dir / "summary.json"
    destination.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    render_report(summary=summary, output_dir=run_dir)
    return destination
```

Update `report.html.j2` to render `summary.two_vm_loadtest` if present. Keep existing `summary.loadtest` behavior unchanged.

**Step 5: Wire report component**

Implement `plan_loadtest_write_report()` marker in `two_vm_loadtest.py`, and wire it to `TwoVmLoadtestRunner.write_report(request)` action in `plan_recipe_steps()`.

**Step 6: Run report tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_two_vm_loadtest_report.py tools/controlplane/tests/test_loadtest_flows.py -q
```

Expected: PASS; existing loadtest report tests must remain unchanged.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py tools/controlplane/src/controlplane_tool/loadtest/templates/report.html.j2 tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/tests/test_two_vm_loadtest_report.py
git commit -m "Write two-vm loadtest reports"
```

---

### Task 11: Add Cleanup Of Both VMs

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_components.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Run impact analysis**

```text
mcp__gitnexus__.impact(repo="mcFaas", target="_should_teardown", direction="upstream")
mcp__gitnexus__.impact(repo="mcFaas", target="execute", direction="upstream")
```

Expected: MEDIUM. Avoid broad generic teardown changes; use explicit recipe steps.

**Step 2: Write failing cleanup tests**

Add:

```python
def test_two_vm_loadtest_plan_skips_both_teardowns_when_cleanup_disabled() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="two-vm-loadtest",
            runtime="java",
            cleanup_vm=False,
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
            loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
        )
    )

    teardown_steps = [step for step in plan.steps if step.step_id in {"loadgen.down", "vm.down"}]

    assert teardown_steps
    assert all("Skipping" in " ".join(step.command) for step in teardown_steps)
```

**Step 3: Run test to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: FAIL until loadgen teardown is wired.

**Step 4: Implement loadgen down planner and action**

In `two_vm_loadtest.py`:

```python
def plan_loadgen_down(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    vm_request = loadgen_vm_request(context)
    if not context.cleanup_vm:
        return (
            RemoteCommandOperation(
                operation_id="loadgen.down",
                summary="Tear down loadgen VM",
                argv=("echo", "Skipping loadgen VM teardown (--no-cleanup-vm)"),
            ),
        )
    if vm_request.lifecycle == "external":
        return (
            RemoteCommandOperation(
                operation_id="loadgen.down",
                summary="Tear down loadgen VM",
                argv=("echo", "Skipping teardown for external loadgen VM lifecycle"),
            ),
        )
    return (
        RemoteCommandOperation(
            operation_id="loadgen.down",
            summary="Tear down loadgen VM",
            argv=("multipass", "delete", vm_request.name or "nanofaas-e2e-loadgen"),
        ),
    )
```

In `plan_recipe_steps()`, map `loadgen.down` to an action:

```python
if operation.operation_id == "loadgen.down" and request.cleanup_vm:
    return ScenarioPlanStep(..., action=lambda: runner.vm.teardown(request.loadgen_vm))
```

For `vm.down`, existing handling uses the stack VM and already honors `cleanup_vm`.

**Step 5: Run cleanup tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_two_vm_loadtest_components.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_two_vm_loadtest_components.py
git commit -m "Clean up two-vm loadtest VMs"
```

---

### Task 12: Add Documentation And Example Scenario File

**Files:**
- Create: `tools/controlplane/scenarios/two-vm-loadtest-java.toml`
- Modify: `tools/controlplane/README.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/control-plane.md`
- Test: `tools/controlplane/tests/test_docs_links.py`
- Test: `tools/controlplane/tests/test_scenario_loader.py`

**Step 1: Write failing docs/scenario tests**

Add a loader test:

```python
def test_two_vm_loadtest_scenario_file_loads() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/two-vm-loadtest-java.toml"))

    assert scenario.base_scenario == "two-vm-loadtest"
    assert scenario.function_preset == "demo-loadtest"
    assert scenario.load.load_profile_name == "quick"
    assert scenario.load.targets == ["word-stats-java"]
```

Add docs link assertions in `test_docs_links.py`:

```python
assert "scripts/controlplane.sh e2e run two-vm-loadtest" in tool_readme
assert "two-vm-loadtest-java.toml" in testing
```

**Step 2: Run tests to verify failure**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_scenario_loader.py -q
```

Expected: FAIL because docs/scenario file are missing.

**Step 3: Add scenario file**

Create `tools/controlplane/scenarios/two-vm-loadtest-java.toml`:

```toml
name = "two-vm-loadtest-java"
base_scenario = "two-vm-loadtest"
runtime = "java"
function_preset = "demo-loadtest"
namespace = "nanofaas-e2e"
local_registry = "localhost:5000"

[invoke]
mode = "smoke"
payload_dir = "payloads"

[payloads]
word-stats-java = "word-stats-sample.json"
json-transform-java = "json-transform-sample.json"

[load]
profile = "quick"
targets = ["word-stats-java"]
```

**Step 4: Document command**

Add concise docs:

```bash
./scripts/controlplane.sh e2e run two-vm-loadtest
./scripts/controlplane.sh e2e run two-vm-loadtest --scenario-file tools/controlplane/scenarios/two-vm-loadtest-java.toml
./scripts/controlplane.sh e2e run two-vm-loadtest --k6-script experiments/k6/custom.js --vus 25 --duration 2m --no-cleanup-vm
```

Document default VM resources and cleanup behavior.

**Step 5: Run docs tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_scenario_loader.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/scenarios/two-vm-loadtest-java.toml tools/controlplane/README.md docs/e2e-tutorial.md docs/control-plane.md tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_scenario_loader.py
git commit -m "Document two-vm loadtest scenario"
```

---

### Task 13: End-To-End Dry Run And Regression Verification

**Files:**
- No new source files unless failures reveal missing coverage.

**Step 1: Run GitNexus detect changes before final commit/PR**

```text
mcp__gitnexus__.detect_changes(repo="mcFaas", scope="all")
```

Expected: changed symbols match the new `two-vm-loadtest` scenario, loadgen VM support, remote k6, Prometheus snapshots, report writing, docs/tests.

**Step 2: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_e2e_catalog.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_two_vm_loadtest_components.py \
  tools/controlplane/tests/test_two_vm_loadtest_runner.py \
  tools/controlplane/tests/test_remote_k6.py \
  tools/controlplane/tests/test_prometheus_snapshots.py \
  tools/controlplane/tests/test_two_vm_loadtest_report.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_scenario_loader.py \
  -q
```

Expected: PASS.

**Step 3: Run controlplane dry run**

Run:

```bash
./scripts/controlplane.sh e2e run two-vm-loadtest --dry-run
```

Expected output includes, in order:

```text
Scenario: two-vm-loadtest
Step ... Ensure VM is running
Step ... Provision base VM dependencies
Step ... Deploy control-plane via Helm
Step ... Deploy function-runtime via Helm
Step ... Ensure loadgen VM is running
Step ... Provision loadgen base dependencies
Step ... Install k6 on loadgen VM
Step ... Run k6 from loadgen VM
Step ... Capture Prometheus query snapshots
Step ... Write two-VM loadtest report
Step ... Tear down loadgen VM
Step ... Tear down VM
```

**Step 4: Run full tool tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
```

Expected: PASS.

**Step 5: Optional real VM smoke**

Only run if the user approves time/resources:

```bash
./scripts/controlplane.sh e2e run two-vm-loadtest --scenario-file tools/controlplane/scenarios/two-vm-loadtest-java.toml --no-cleanup-vm
```

Expected:
- two Multipass VMs are created;
- stack VM runs k3s/Helm/control-plane/function runtime;
- loadgen VM runs k6;
- local run directory contains `summary.json`, `report.html`, `k6-summary.json`, and `metrics/prometheus-snapshots.json`;
- Prometheus required queries have data.

**Step 6: Commit final fixes**

If any final polish changes were needed:

```bash
git add <changed-files>
git commit -m "Verify two-vm loadtest workflow"
```

---

## Implementation Notes

- The safest DRY boundary is component-level reuse, not workflow-level reuse. The new recipe should list its own task IDs, but the platform prefix must reuse the same component planners as `helm-stack`.
- Avoid adding a second generic VM role into every operation unless needed. Prefer explicit `loadgen.*` components and targeted plan actions for the new scenario.
- Keep old `loadtest run` behavior untouched; that path starts a local mock-k8s/control-plane and is not the same as this VM-backed Helm scenario.
- Keep report additions backward-compatible. Existing `summary.loadtest` consumers must still work.
- For Prometheus on the stack VM, first use the endpoint exposed by the Helm stack if already available. If the service is not reachable at `http://<stack-host>:9090`, add a small helper that discovers/forwards it using existing VM execution, and cover that helper with tests.
- If k6 requires `open()` without importing `k6/fs`, adjust the default JS asset and add a syntax smoke check if k6 is available locally.
