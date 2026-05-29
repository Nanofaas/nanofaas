# Proxmox Loadtest Platform Prefix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `proxmox-vm-loadtest` execute the same stack setup prefix as `two-vm-loadtest` and `azure-vm-loadtest`: provision VM, sync repo, registry, build control-plane/functions, install k3s, configure registry, deploy Helm, build CLI, register functions, then run the load generator.

**Architecture:** The scenario recipe remains the source of truth for shared loadtest ordering. Proxmox uses `workflow_tasks.vm.proxmox.ProxmoxVmProvider` and `proxmox-sdk` routing APIs to create VM/NAT, then SSH/rsync/Ansible for the rest, matching the Multipass/Azure model without reimplementing NAT logic. The typed Proxmox plan composes a recipe-built platform prelude with the existing Proxmox-specific loadtest tail because the tail needs provider-specific local NodePort publishing for Prometheus and cleanup.

**Tech Stack:** Python, pytest, GitNexus, `workflow_tasks`, `proxmox-sdk`, Ansible, rsync, k3s, Helm.

---

## Files

- Modify: `src/controlplane_tool/e2e/e2e_runner.py`
- Modify: `src/controlplane_tool/scenario/scenario_flows.py`
- Modify: `src/controlplane_tool/scenario/components/helm.py`
- Modify: `src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
- Modify: `src/controlplane_tool/scenario/two_vm_loadtest_config.py`
- Modify: `../workflow-tasks/src/workflow_tasks/vm/proxmox.py`
- Modify: `../workflow-tasks/src/workflow_tasks/vm/multipass.py`
- Test: `tests/test_scenario_builders.py`
- Test: `tests/test_scenario_flows.py`
- Test: `tests/test_proxmox_vm_loadtest_plan.py`
- Test: `tests/test_recipe_execution_hooks.py`
- Test: `../workflow-tasks/tests/vm/test_proxmox_vm_provider.py`

## Current Failure

The recipe for `proxmox-vm-loadtest` already declares the full platform prefix, but `E2eRunner.plan()` routes to `build_proxmox_vm_loadtest_plan()`, whose `run()` executes only:

```text
ensure stack VM
ensure loadgen VM
publish NAT ports
install k6
run k6
fetch results
capture Prometheus
write report
cleanup
```

That skips:

```text
vm.provision_base
repo.sync_to_vm
registry.ensure_container
images.build_core
images.build_selected_functions
k3s.install
k3s.configure_registry
namespace.install
helm.deploy_control_plane
helm.deploy_function_runtime
cli.build_install_dist
functions.register
```

GitNexus pre-change impact for `ProxmoxVmLoadtestPlan` is HIGH: direct callers/importers include `build_proxmox_vm_loadtest_plan`, `E2eRunner.plan`, `E2eRunner.plan_all`, TUI, and scenario tests. Treat this as a structural refactor.

---

### Task 1: Add Failing Tests For Proxmox Loadtest Symmetry

**Files:**
- Modify: `tests/test_scenario_builders.py`
- Modify: `tests/test_scenario_flows.py`
- Modify: `tests/test_proxmox_vm_loadtest_plan.py`

- [ ] **Step 1: Add scenario task id tests**

Append to `tests/test_scenario_builders.py`:

```python
def test_proxmox_vm_loadtest_task_ids_include_functions_register() -> None:
    """proxmox-vm-loadtest uses REST function registration like the loadtest scenarios."""
    ids = scenario_task_ids("proxmox-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_proxmox_vm_loadtest_task_ids_order() -> None:
    ids = scenario_task_ids("proxmox-vm-loadtest")
    assert ids.index("cli.build_install_dist") < ids.index("functions.register")
    assert ids.index("functions.register") < ids.index("loadgen.ensure_running")
```

- [ ] **Step 2: Add flow task id test**

Append to `tests/test_scenario_flows.py`:

```python
def test_proxmox_vm_loadtest_flow_uses_loadtest_recipe_ids() -> None:
    flow = build_scenario_flow(
        "proxmox-vm-loadtest",
        repo_root=Path("/repo"),
        request=E2eRequest(
            scenario="proxmox-vm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="proxmox", name="nanofaas-proxmox"),
            loadgen_vm=VmRequest(lifecycle="proxmox", name="nanofaas-proxmox-loadgen"),
        ),
    )

    assert "vm.ensure_running" in flow.task_ids
    assert "registry.ensure_container" in flow.task_ids
    assert "k3s.install" in flow.task_ids
    assert "helm.deploy_control_plane" in flow.task_ids
    assert "functions.register" in flow.task_ids
    assert "loadgen.ensure_running" in flow.task_ids
    assert "cli.fn_apply_selected" not in flow.task_ids
```

- [ ] **Step 3: Replace the Proxmox typed-plan exact tail assertion**

In `tests/test_proxmox_vm_loadtest_plan.py`, replace `test_proxmox_vm_loadtest_plan_task_ids` with:

```python
def test_proxmox_vm_loadtest_plan_task_ids_include_platform_prefix() -> None:
    from pathlib import Path
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )

    plan = runner.plan(request)
    ids = plan.task_ids

    required = [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core.control_image",
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "functions.register",
        "vm.stack.publish_ports",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "vm.loadgen.destroy",
        "vm.stack.destroy",
    ]
    for step_id in required:
        assert step_id in ids

    assert ids.index("functions.register") < ids.index("vm.stack.publish_ports")
    assert ids.index("vm.stack.publish_ports") < ids.index("loadgen.install_k6")
```

- [ ] **Step 4: Run tests and verify failure**

Run:

```bash
uv run pytest -o addopts='' tests/test_scenario_builders.py::test_proxmox_vm_loadtest_task_ids_include_functions_register tests/test_scenario_builders.py::test_proxmox_vm_loadtest_task_ids_order tests/test_scenario_flows.py::test_proxmox_vm_loadtest_flow_uses_loadtest_recipe_ids tests/test_proxmox_vm_loadtest_plan.py::test_proxmox_vm_loadtest_plan_task_ids_include_platform_prefix -q
```

Expected: FAIL because Proxmox still exposes `cli.fn_apply_selected` in recipe task IDs and the typed plan has only tail phases.

### Task 2: Centralize Loadtest Recipe ID Remapping

**Files:**
- Modify: `src/controlplane_tool/scenario/two_vm_loadtest_config.py`
- Modify: `src/controlplane_tool/scenario/scenario_flows.py`
- Modify: `src/controlplane_tool/e2e/e2e_runner.py`

- [ ] **Step 1: Add shared loadtest scenario constants**

In `src/controlplane_tool/scenario/two_vm_loadtest_config.py`, add:

```python
LOADTEST_SCENARIOS: frozenset[str] = frozenset(
    {
        "two-vm-loadtest",
        "azure-vm-loadtest",
        "proxmox-vm-loadtest",
    }
)


def remap_loadtest_component_id(scenario_name: str, component_id: str) -> str:
    if scenario_name in LOADTEST_SCENARIOS and component_id == "cli.fn_apply_selected":
        return "functions.register"
    return component_id
```

- [ ] **Step 2: Use the shared remap in `scenario_task_ids`**

Replace the hard-coded two-scenario condition in `src/controlplane_tool/scenario/scenario_flows.py` with:

```python
from controlplane_tool.scenario.two_vm_loadtest_config import remap_loadtest_component_id
```

and:

```python
ids = [
    remap_loadtest_component_id(scenario, component.component_id)
    for component in compose_recipe(recipe)
]
return ids
```

- [ ] **Step 3: Use the same remap in `plan_recipe_steps`**

In `src/controlplane_tool/e2e/e2e_runner.py`, import `LOADTEST_SCENARIOS` and replace:

```python
if component.component_id == "cli.fn_apply_selected" and scenario_name in {"two-vm-loadtest", "azure-vm-loadtest"}:
```

with:

```python
if component.component_id == "cli.fn_apply_selected" and scenario_name in LOADTEST_SCENARIOS:
```

- [ ] **Step 4: Run remap tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_scenario_builders.py::test_two_vm_loadtest_task_ids_include_functions_register tests/test_scenario_builders.py::test_azure_vm_loadtest_task_ids_include_functions_register tests/test_scenario_builders.py::test_proxmox_vm_loadtest_task_ids_include_functions_register tests/test_scenario_builders.py::test_proxmox_vm_loadtest_task_ids_order tests/test_scenario_builders.py::test_cli_stack_plan_uses_cli_fn_apply_not_rest_api -q
```

Expected: PASS. `cli-stack` must still use CLI apply, not REST registration.

### Task 3: Expose NodePorts For All VM Loadtest Scenarios

**Files:**
- Modify: `src/controlplane_tool/scenario/components/helm.py`
- Test: `tests/test_recipe_execution_hooks.py`

- [ ] **Step 1: Add a failing Helm values test**

Append to `tests/test_recipe_execution_hooks.py`:

```python
def test_control_plane_nodeports_enabled_for_all_vm_loadtest_scenarios() -> None:
    from pathlib import Path

    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.infra.vm.vm_models import VmRequest

    for scenario in ("two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"):
        request = E2eRequest(
            scenario=scenario,
            runtime="java",
            vm=VmRequest(lifecycle="proxmox" if scenario == "proxmox-vm-loadtest" else "multipass", name="stack"),
            loadgen_vm=VmRequest(lifecycle="proxmox" if scenario == "proxmox-vm-loadtest" else "multipass", name="loadgen"),
        )
        steps = plan_recipe_steps(
            Path("/repo"),
            request,
            scenario,
            shell=RecordingShell(),
            component_ids=("helm.deploy_control_plane",),
        )
        helm_step = steps[0]
        command = " ".join(helm_step.command)
        assert "controlPlane.service.type=NodePort" in command
        assert "prometheus.service.type=NodePort" in command
```

- [ ] **Step 2: Extend `plan_recipe_steps` signature for component filtering**

In `src/controlplane_tool/e2e/e2e_runner.py`, change the signature:

```python
def plan_recipe_steps(
    repo_root: Path,
    request: E2eRequest,
    scenario_name: str,
    *,
    shell: ShellBackend | None = None,
    release: str | None = None,
    manifest_root: Path | None = None,
    host_resolver: Callable[[VmRequest], str] | None = None,
    multipass_client: MultipassClient | None = None,
    component_ids: tuple[str, ...] | None = None,
) -> list[ScenarioPlanStep]:
```

Then replace:

```python
recipe = build_scenario_recipe(scenario_name)
```

with:

```python
recipe = build_scenario_recipe(scenario_name)
if component_ids is not None:
    recipe = recipe.__class__(
        name=recipe.name,
        component_ids=component_ids,
        requires_managed_vm=recipe.requires_managed_vm,
    )
```

- [ ] **Step 3: Make Helm loadtest detection shared**

In `src/controlplane_tool/scenario/components/helm.py`, import:

```python
from controlplane_tool.scenario.two_vm_loadtest_config import LOADTEST_SCENARIOS
```

and replace:

```python
expose_node_port=context.scenario_name == "two-vm-loadtest",
```

with:

```python
expose_node_port=context.scenario_name in LOADTEST_SCENARIOS,
```

- [ ] **Step 4: Run Helm tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_recipe_execution_hooks.py::test_control_plane_nodeports_enabled_for_all_vm_loadtest_scenarios -q
```

Expected: PASS.

### Task 4: Add Public Proxmox SSH Endpoint Helpers

**Files:**
- Modify: `../workflow-tasks/src/workflow_tasks/vm/proxmox.py`
- Modify: `../workflow-tasks/src/workflow_tasks/vm/multipass.py`
- Test: `../workflow-tasks/tests/vm/test_proxmox_vm_provider.py`

- [ ] **Step 1: Add tests for public SSH endpoint helpers**

Append to `../workflow-tasks/tests/vm/test_proxmox_vm_provider.py`:

```python
def test_proxmox_provider_exposes_published_ssh_endpoint(monkeypatch, tmp_path):
    from workflow_tasks.vm.models import VmRequest
    from workflow_tasks.vm.proxmox import ProxmoxVmProvider

    provider = ProxmoxVmProvider(tmp_path)
    request = VmRequest(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        proxmox_host="149.132.176.73",
    )

    monkeypatch.setattr(provider, "_ssh_endpoint", lambda req: ("149.132.176.73", 20001))

    assert provider.ssh_endpoint(request) == ("149.132.176.73", 20001)
```

Append:

```python
def test_repo_sync_ssh_rsh_supports_non_default_port(tmp_path):
    from workflow_tasks.vm.multipass import repo_sync_ssh_rsh

    key = tmp_path / "id_ed25519"
    key.write_text("private", encoding="utf-8")

    command = repo_sync_ssh_rsh(key, port=20001)

    assert "-p 20001" in command
    assert f"-i {key}" in command
```

- [ ] **Step 2: Add public helper methods to Proxmox provider**

In `../workflow-tasks/src/workflow_tasks/vm/proxmox.py`, add methods on `ProxmoxVmProvider`:

```python
def ssh_endpoint(self, request: VmRequest) -> tuple[str, int]:
    return self._ssh_endpoint(request)


def ssh_private_key_path(self, request: VmRequest) -> Path | None:
    return self._ssh_key(request)
```

- [ ] **Step 3: Extend rsync SSH command helper**

In `../workflow-tasks/src/workflow_tasks/vm/multipass.py`, change:

```python
def repo_sync_ssh_rsh(private_key_path: Path | None = None) -> str:
```

to:

```python
def repo_sync_ssh_rsh(
    private_key_path: Path | None = None,
    *,
    port: int | None = None,
) -> str:
```

and insert before key handling:

```python
if port is not None:
    parts.extend(["-p", str(port)])
```

- [ ] **Step 4: Run workflow-task VM tests**

Run:

```bash
uv run pytest -o addopts='' ../workflow-tasks/tests/vm -q
```

Expected: PASS.

### Task 5: Make Recipe Execution Proxmox-Aware

**Files:**
- Modify: `src/controlplane_tool/e2e/e2e_runner.py`
- Test: `tests/test_recipe_execution_hooks.py`

- [ ] **Step 1: Add tests for Proxmox orchestrator selection**

Append to `tests/test_recipe_execution_hooks.py`:

```python
def test_plan_recipe_steps_uses_proxmox_provider_for_proxmox_lifecycle(monkeypatch) -> None:
    from pathlib import Path

    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    calls: list[str] = []

    class FakeProxmox:
        def __init__(self, repo_root):
            calls.append(str(repo_root))

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            calls.append(f"ensure:{request.name}")

        def teardown(self, request):
            calls.append(f"teardown:{request.name}")

        def exec_argv(self, request, argv, env=None, cwd=None):
            calls.append(f"exec:{request.name}:{argv[0]}")
            return type("Result", (), {"return_code": 0, "stdout": "", "stderr": ""})()

        def connection_host(self, request):
            return "10.0.2.10"

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )

    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        component_ids=("vm.ensure_running", "images.build_core"),
    )

    steps[0].action()
    assert calls[0] == "/repo"
    assert "ensure:stack" in calls
```

- [ ] **Step 2: Select Proxmox provider in `plan_recipe_steps`**

In `src/controlplane_tool/e2e/e2e_runner.py`, add to the provider selection block:

```python
    if request.vm and request.vm.lifecycle == "azure":
        vm_orch = AzureVmOrchestrator(repo_root)
    elif request.vm and request.vm.lifecycle == "proxmox":
        from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

        vm_orch = ProxmoxVmOrchestrator(repo_root=repo_root)
    else:
        vm_orch = runner.vm
```

- [ ] **Step 3: Add Proxmox-aware repo sync action**

Still in `plan_recipe_steps`, import:

```python
from workflow_tasks.vm.multipass import repo_rsync_command, repo_sync_ssh_rsh
```

Add helper:

```python
    def _on_repo_sync_to_vm() -> None:
        if request.vm is None:
            raise RuntimeError("repo sync requires a VM request")
        if request.vm.lifecycle != "proxmox":
            return _on_host_command(tuple(component_steps[0].command), component_steps[0].env)
        host, port = vm_orch.ssh_endpoint(vm_request)
        key = vm_orch.ssh_private_key_path(vm_request)
        command = repo_rsync_command(
            source=repo_root,
            user=vm_request.user,
            host=host,
            destination=remote_dir,
            ssh_rsh=repo_sync_ssh_rsh(key, port=port),
        )
        result = runner.shell.run(command, cwd=repo_root)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")
```

When `component.component_id == "repo.sync_to_vm"` and lifecycle is Proxmox, replace its step with a `ScenarioPlanStep` carrying `action=_on_repo_sync_to_vm`.

- [ ] **Step 4: Add Proxmox-aware Ansible inventory action**

Add helper:

```python
    def _rewrite_ansible_inventory_for_proxmox(argv: tuple[str, ...]) -> list[str]:
        if request.vm is None or request.vm.lifecycle != "proxmox":
            return list(argv)
        host, port = vm_orch.ssh_endpoint(vm_request)
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{host},"
        rewritten.extend(["-e", f"ansible_port={port}"])
        key = vm_orch.ssh_private_key_path(vm_request)
        if key is not None and "--private-key" not in rewritten:
            rewritten.extend(["--private-key", str(key)])
        return rewritten
```

For Proxmox lifecycle, when `component.component_id` is one of:

```python
{"vm.provision_base", "registry.ensure_container", "k3s.install", "k3s.configure_registry"}
```

replace each step with an action that runs the rewritten command through `runner.shell.run(...)`.

- [ ] **Step 5: Run recipe hook tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_recipe_execution_hooks.py -q
```

Expected: PASS.

### Task 6: Compose Proxmox Platform Prelude With Existing Tail

**Files:**
- Modify: `src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
- Test: `tests/test_proxmox_vm_loadtest_plan.py`

- [ ] **Step 1: Add prelude step builder**

In `src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`, import:

```python
from controlplane_tool.e2e.e2e_runner import E2ePlan, plan_recipe_steps
```

Add:

```python
_PROXMOX_LOADTEST_PRELUDE_COMPONENTS: tuple[str, ...] = (
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
    "cli.build_install_dist",
    "cli.fn_apply_selected",
)
```

- [ ] **Step 2: Build prelude steps in the builder**

Replace the current empty `steps=[]` in `build_proxmox_vm_loadtest_plan` with:

```python
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "proxmox-vm-loadtest",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
        multipass_client=runner._multipass_client,
        component_ids=_PROXMOX_LOADTEST_PRELUDE_COMPONENTS,
    )
    return ProxmoxVmLoadtestPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

- [ ] **Step 3: Include prelude IDs and titles**

Change `ProxmoxVmLoadtestPlan.task_ids`:

```python
    @property
    def task_ids(self) -> list[str]:
        pre, wf = self._skeleton()
        return [s.step_id for s in self.steps if s.step_id] + [t.task_id for t in pre] + wf.task_ids
```

Change `phase_titles`:

```python
    @property
    def phase_titles(self) -> list[str]:
        pre, wf = self._skeleton()
        return [s.summary for s in self.steps if s.step_id] + [t.title for t in pre] + wf.phase_titles
```

- [ ] **Step 4: Execute prelude before the Proxmox tail**

At the start of `ProxmoxVmLoadtestPlan.run`, before creating `ProxmoxVmOrchestrator`, add:

```python
        if self.steps:
            prelude = E2ePlan(
                scenario=self.scenario,
                request=self.request,
                steps=self.steps,
            )
            self.runner._execute_steps(prelude, event_listener=event_listener)
```

Then continue with existing tail execution.

- [ ] **Step 5: Avoid double stack VM teardown**

Because the prelude intentionally stops before `loadgen.ensure_running` and excludes `vm.down`, no extra stack cleanup is created. Keep `vm.stack.destroy` in the Proxmox tail as the single stack cleanup point.

- [ ] **Step 6: Run Proxmox plan tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_proxmox_vm_loadtest_plan.py -q
```

Expected: PASS.

### Task 7: Publish Local Proxmox NodePorts Before REST Registration

**Files:**
- Modify: `src/controlplane_tool/e2e/e2e_runner.py`
- Modify: `src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
- Test: `tests/test_recipe_execution_hooks.py`

- [ ] **Step 1: Add test that Proxmox registration uses a published HTTP endpoint**

Append to `tests/test_recipe_execution_hooks.py`:

```python
def test_proxmox_register_functions_uses_published_control_plane_endpoint(monkeypatch) -> None:
    from pathlib import Path

    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    captured: dict[str, str] = {}

    class FakeProvider:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def connection_host(self, request):
            return "10.0.2.10"

        def publish_port(self, request, *, service, guest_port):
            assert service == "CONTROL_PLANE_HTTP"
            return ("149.132.176.73", 30080)

    class FakeRegisterFunctions:
        def __init__(self, **kwargs):
            captured["url"] = kwargs["control_plane_url"]

        def run(self):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProvider,
    )
    monkeypatch.setattr(
        "controlplane_tool.e2e.e2e_runner.RegisterFunctions",
        FakeRegisterFunctions,
    )

    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        component_ids=("cli.fn_apply_selected",),
    )
    steps[0].action()

    assert captured["url"] == "http://149.132.176.73:30080"
```

- [ ] **Step 2: Publish control-plane HTTP for Proxmox registration**

In `_on_register_functions` inside `plan_recipe_steps`, replace:

```python
        cp_host = _resolve_cp_host()
        cp_url = f"http://{cp_host}:{TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT}"
```

with:

```python
        if request.vm is not None and request.vm.lifecycle == "proxmox":
            cp_host, cp_port = vm_orch.publish_port(
                vm_request,
                service="CONTROL_PLANE_HTTP",
                guest_port=TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
            )
        else:
            cp_host = _resolve_cp_host()
            cp_port = TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT
        cp_url = f"http://{cp_host}:{cp_port}"
```

- [ ] **Step 3: Keep Prometheus publishing in the existing Proxmox tail**

Do not move the Prometheus publish into the generic recipe prelude. The Proxmox tail already publishes:

```python
proxmox_orch.publish_port(
    request.vm,
    service="PROMETHEUS",
    guest_port=TWO_VM_PROMETHEUS_NODE_PORT,
)
```

This keeps local Prometheus capture provider-aware while k6 still targets the stack guest IP from loadgen.

- [ ] **Step 4: Run registration tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_recipe_execution_hooks.py::test_proxmox_register_functions_uses_published_control_plane_endpoint -q
```

Expected: PASS.

### Task 8: Verification And Regression Suite

**Files:**
- No code changes.

- [ ] **Step 1: Run targeted controlplane tests**

Run:

```bash
uv run pytest -o addopts='' tests/test_scenario_builders.py tests/test_scenario_flows.py tests/test_proxmox_vm_loadtest_plan.py tests/test_recipe_execution_hooks.py tests/test_tui_choices.py -q
```

Expected: PASS.

- [ ] **Step 2: Run workflow task VM tests**

Run:

```bash
uv run pytest -o addopts='' ../workflow-tasks/tests/vm ../workflow-tasks/tests/loadtest -q
```

Expected: PASS.

- [ ] **Step 3: Run GitNexus change detection**

Run GitNexus:

```text
gitnexus_detect_changes(scope="all", repo="mcFaas")
```

Expected: changes limited to loadtest planning, Proxmox provider SSH endpoint helpers, and tests.

- [ ] **Step 4: Manual E2E verification**

Run the TUI scenario:

```bash
./scripts/controlplane.sh tui
```

Select `proxmox-vm-loadtest`.

Expected phases include, in order:

```text
Ensure VM is running
Provision base VM dependencies
Sync project to VM
Ensure registry container
Build core JVM artifacts
Build control-plane image
Build function-runtime image
Build selected function images
Install k3s
Configure k3s registry
Install namespace Helm release
Deploy control-plane via Helm
Deploy function-runtime via Helm
Build nanofaas-cli installDist
Register selected functions via REST API
Ensure loadgen VM running (Proxmox)
Publish Proxmox NAT ports
Install k6 on loadgen VM (Proxmox)
Run k6 loadtest (Proxmox)
Capture Prometheus snapshots (Proxmox)
Destroy loadgen VM (Proxmox)
Destroy stack VM (Proxmox)
```

## Self-Review

- Spec coverage: covers stack VM build/deploy prefix, function registration, Proxmox NAT via provider/sdk, SSH for remaining work, and loadtest tail.
- Symmetry: recipe ordering is shared; only Proxmox endpoint publication remains provider-specific.
- Risk: HIGH impact on E2E/TUI planning. Required mitigations are targeted tests plus GitNexus `detect_changes`.
- No workaround: no manual VM repair, no hard-coded NAT ports, no duplicated k3s/registry/build shell scripts.
