# azure-vm-loadtest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `azure-vm-loadtest` scenario to `tools/controlplane` that runs a k6 load test against a nanofaas stack deployed on two Azure VMs (stack + loadgen).

**Architecture:** A new `AzureVmOrchestrator` adapter in `infra/vm/` implements the same method surface as `VmOrchestrator` (ensure_running, exec_argv, transfer_to, transfer_from, remote_home, connection_host, teardown) using `azure-vm-sdk`. The existing `TwoVmLoadtestRunner` is reused unchanged (only type annotation widened). The recipe is a focused subset of `two-vm-loadtest` — provisioning is delegated to cloud-init, eliminating ansible/rsync steps that contain unresolvable Multipass IP placeholders on Azure.

**Tech Stack:** Python 3.11+, azure-vm-sdk (git), pydantic, typer, pytest, uv

**Recipe scope (differs from spec):** The spec stated "identical recipe" but `vm.provision_base`, `repo.sync_to_vm`, `k3s.install`, etc. use `<multipass-ip:...>` placeholders resolved only by `VmOrchestrator`. The implementation instead relies on cloud-init to install k3s and deploy the control plane on the stack VM, and cloud-init to install k6 on the loadgen VM. All recipe steps carry `action` closures — no IP placeholder resolution is required.

---

## File Map

| Status | Path | Role |
|---|---|---|
| create | `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py` | `AzureVmOrchestrator` — same interface as `VmOrchestrator` |
| create | `tools/controlplane/tests/test_azure_vm_loadtest_components.py` | Unit tests for `AzureVmOrchestrator` |
| create | `tools/controlplane/tests/test_azure_vm_loadtest_runner.py` | Unit tests for `TwoVmLoadtestRunner` with azure orchestrator |
| create | `tools/controlplane/scenarios/azure-vm-loadtest-java.toml` | Scenario manifest for CLI usage |
| modify | `tools/controlplane/pyproject.toml` | Add `azure-vm-sdk` git dependency |
| modify | `tools/controlplane/uv.lock` | Auto-updated by `uv lock` |
| modify | `tools/controlplane/src/controlplane_tool/core/models.py` | `VmLifecycle`, `ScenarioName`, `VM_BACKED_SCENARIOS` |
| modify | `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py` | Azure fields on `VmRequest` |
| modify | `tools/controlplane/src/controlplane_tool/scenario/catalog.py` | `azure-vm-loadtest` `ScenarioDefinition` |
| modify | `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py` | `azure-vm-loadtest` `ScenarioRecipe` |
| modify | `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py` | Widen `vm` type annotation |
| modify | `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` | Azure branch in `plan_recipe_steps`, `plan`, `plan_all` |
| modify | `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py` | Azure CLI options + lifecycle handling |
| modify | `tools/controlplane/tests/test_e2e_catalog.py` | Add azure-vm-loadtest assertions |
| modify | `tools/controlplane/tests/test_scenario_loader.py` | Add azure-vm-loadtest manifest test |

---

## Task 1: Add azure-vm-sdk dependency + core type changes + VmRequest Azure fields

**Files:**
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/src/controlplane_tool/core/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py`
- Test: `tools/controlplane/tests/test_azure_vm_request.py` (new, small, co-located with existing tests)

- [ ] **Step 1.1: Write the failing test**

Create `tools/controlplane/tests/test_azure_vm_request.py`:

```python
from __future__ import annotations

import pytest
from pydantic import ValidationError

from controlplane_tool.infra.vm.vm_models import VmRequest


def test_vm_request_accepts_azure_lifecycle():
    request = VmRequest(
        lifecycle="azure",
        name="nanofaas-azure",
        user="azureuser",
        azure_resource_group="my-rg",
        azure_location="westeurope",
    )
    assert request.lifecycle == "azure"
    assert request.azure_resource_group == "my-rg"
    assert request.azure_location == "westeurope"
    assert request.azure_vm_size == "Standard_B2s"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_azure_fields_have_defaults():
    request = VmRequest(lifecycle="azure", azure_resource_group="rg", azure_location="west")
    assert request.azure_vm_size == "Standard_B2s"
    assert request.azure_image_urn is None
    assert request.azure_ssh_key_path is None


def test_vm_request_rejects_unknown_lifecycle():
    with pytest.raises(ValidationError):
        VmRequest(lifecycle="foobar")
```

- [ ] **Step 1.2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_request.py -v
```

Expected: `FAILED` — `ValidationError` because `"azure"` is not in `VmLifecycle`.

- [ ] **Step 1.3: Add azure-vm-sdk to pyproject.toml**

In `tools/controlplane/pyproject.toml`, add after the `multipass-sdk` line:

```toml
    "azure-vm-sdk @ git+https://github.com/miciav/azure-vm-sdk.git@2311b1c",
```

- [ ] **Step 1.4: Update VmLifecycle, ScenarioName, VM_BACKED_SCENARIOS in core/models.py**

In `tools/controlplane/src/controlplane_tool/core/models.py`:

```python
# Change this line:
VmLifecycle = Literal["multipass", "external"]
# To:
VmLifecycle = Literal["multipass", "external", "azure"]

# Change ScenarioName to include azure-vm-loadtest:
ScenarioName = Literal[
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
    "azure-vm-loadtest",
]

# Change VM_BACKED_SCENARIOS:
VM_BACKED_SCENARIOS = frozenset(
    {
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "helm-stack",
        "two-vm-loadtest",
        "azure-vm-loadtest",
    }
)
```

- [ ] **Step 1.5: Add Azure fields to VmRequest in vm_models.py**

In `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py`, add after `disk: str = "30G"`:

```python
    azure_vm_size: str = "Standard_B2s"
    azure_resource_group: str | None = None
    azure_location: str | None = None
    azure_image_urn: str | None = None
    azure_ssh_key_path: str | None = None
```

- [ ] **Step 1.6: Run uv lock to update the lockfile**

```bash
cd tools/controlplane && uv lock
```

Expected: lockfile updated, no errors.

- [ ] **Step 1.7: Run test to verify it passes**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_request.py -v
```

Expected: all 3 tests PASS.

- [ ] **Step 1.8: Run existing tests to ensure no regressions**

```bash
cd tools/controlplane && uv run pytest tests/ -v --ignore=tests/integration -x -q 2>&1 | tail -20
```

Expected: all existing tests pass.

- [ ] **Step 1.9: Commit**

```bash
git add tools/controlplane/pyproject.toml tools/controlplane/uv.lock \
  tools/controlplane/src/controlplane_tool/core/models.py \
  tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py \
  tools/controlplane/tests/test_azure_vm_request.py
git commit -m "feat: add azure lifecycle, azure-vm-loadtest scenario name, and VmRequest azure fields"
```

---

## Task 2: Add azure-vm-loadtest to catalog + recipe + scenario manifest file

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`
- Modify: `tools/controlplane/tests/test_e2e_catalog.py`
- Create: `tools/controlplane/scenarios/azure-vm-loadtest-java.toml`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`

- [ ] **Step 2.1: Write failing catalog test**

In `tools/controlplane/tests/test_e2e_catalog.py`, add at the bottom:

```python
def test_azure_vm_loadtest_scenario_is_vm_backed_and_grouped() -> None:
    scenario = resolve_scenario("azure-vm-loadtest")

    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True
    assert scenario.selection_mode == "multi"
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes
```

- [ ] **Step 2.2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_e2e_catalog.py::test_azure_vm_loadtest_scenario_is_vm_backed_and_grouped -v
```

Expected: `FAILED` — `ValueError: Unknown scenario: azure-vm-loadtest`.

- [ ] **Step 2.3: Add azure-vm-loadtest to catalog.py**

In `tools/controlplane/src/controlplane_tool/scenario/catalog.py`, add after the `two-vm-loadtest` entry:

```python
    ScenarioDefinition(
        name="azure-vm-loadtest",
        description="Two-VM Azure load test: stack VM + k6 loadgen on Azure.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
```

- [ ] **Step 2.4: Update test_catalog_lists_expected_suite_names**

In `tools/controlplane/tests/test_e2e_catalog.py`, update the existing `test_catalog_lists_expected_suite_names` to include `"azure-vm-loadtest"` at the end:

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
        "azure-vm-loadtest",
    ]
```

- [ ] **Step 2.5: Add azure-vm-loadtest recipe to recipes.py**

In `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`, add before the closing `}` of `_SCENARIO_RECIPES`:

```python
    "azure-vm-loadtest": ScenarioRecipe(
        name="azure-vm-loadtest",
        component_ids=(
            "vm.ensure_running",
            "loadgen.ensure_running",
            "loadgen.run_k6",
            "metrics.prometheus_snapshot",
            "loadtest.write_report",
            "loadgen.down",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
```

Note: provisioning (k3s, k6 install, image build) is handled by cloud-init in `AzureVmOrchestrator.ensure_running`. Only the load-test execution steps are in the recipe.

- [ ] **Step 2.6: Create the azure-vm-loadtest scenario manifest**

Create `tools/controlplane/scenarios/azure-vm-loadtest-java.toml`:

```toml
name = "azure-vm-loadtest-java"
base_scenario = "azure-vm-loadtest"
runtime = "java"
function_preset = "demo-loadtest"
namespace = "nanofaas"
local_registry = "localhost:5000"

[load]
profile = "quick"
targets = ["word-stats-java"]
```

- [ ] **Step 2.7: Add scenario loader test**

In `tools/controlplane/tests/test_scenario_loader.py`, add:

```python
def test_loader_resolves_azure_vm_loadtest_manifest() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/azure-vm-loadtest-java.toml"))

    assert scenario.base_scenario == "azure-vm-loadtest"
    assert scenario.function_preset == "demo-loadtest"
    assert scenario.load.targets == ["word-stats-java"]
```

- [ ] **Step 2.8: Run all catalog and loader tests**

```bash
cd tools/controlplane && uv run pytest tests/test_e2e_catalog.py tests/test_scenario_loader.py -v
```

Expected: all tests PASS.

- [ ] **Step 2.9: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/catalog.py \
  tools/controlplane/src/controlplane_tool/scenario/components/recipes.py \
  tools/controlplane/scenarios/azure-vm-loadtest-java.toml \
  tools/controlplane/tests/test_e2e_catalog.py \
  tools/controlplane/tests/test_scenario_loader.py
git commit -m "feat: add azure-vm-loadtest to scenario catalog, recipe, and manifest"
```

---

## Task 3: Create AzureVmOrchestrator

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py`
- Create: `tools/controlplane/tests/test_azure_vm_loadtest_components.py`

- [ ] **Step 3.1: Write failing tests for remote_home and teardown**

Create `tools/controlplane/tests/test_azure_vm_loadtest_components.py`:

```python
from __future__ import annotations

import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.infra.vm.vm_models import VmRequest


def _azure_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="azure",
        name="nanofaas-azure",
        user="azureuser",
        azure_resource_group="my-rg",
        azure_location="westeurope",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_orchestrator(tmp_path: Path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
    return AzureVmOrchestrator(tmp_path)


# ----------------------------------------------------------------- remote_home

def test_remote_home_uses_request_home_when_set(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(home="/custom/home")
    assert orch.remote_home(request) == "/custom/home"


def test_remote_home_defaults_to_home_slash_user(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="azureuser")
    assert orch.remote_home(request) == "/home/azureuser"


def test_remote_home_returns_root_for_root_user(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="root")
    assert orch.remote_home(request) == "/root"


def test_remote_project_dir_appends_nanofaas(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _azure_request(user="azureuser")
    assert orch.remote_project_dir(request) == "/home/azureuser/nanofaas"


# ----------------------------------------------------------------- teardown

def test_teardown_calls_vm_delete(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_azure_request())

    mock_vm.delete.assert_called_once()
    assert result.return_code == 0


def test_teardown_silences_vm_not_found(tmp_path, monkeypatch):
    from azure_vm.exceptions import VmNotFoundError

    mock_client = MagicMock()
    mock_client.get_vm.side_effect = VmNotFoundError("nanofaas-azure")
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_azure_request())

    assert result.return_code == 0
```

- [ ] **Step 3.2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_components.py -v
```

Expected: `FAILED` — `ModuleNotFoundError: No module named 'controlplane_tool.infra.vm.azure_vm_adapter'`.

- [ ] **Step 3.3: Create azure_vm_adapter.py with lifecycle and path methods**

Create `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py`:

```python
from __future__ import annotations

import subprocess
from pathlib import Path

from azure_vm import AzureClient
from azure_vm.exceptions import VmNotFoundError

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.workspace.paths import ToolPaths


def _find_ssh_private_key() -> Path | None:
    ssh_dir = Path.home() / ".ssh"
    for name in ("id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"):
        priv = ssh_dir / name
        if priv.exists():
            return priv
    return None


def _ok(command: list[str]) -> ShellExecutionResult:
    return ShellExecutionResult(command=command, return_code=0, stdout="")


class AzureVmOrchestrator:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)

    def _client(self, request: VmRequest) -> AzureClient:
        return AzureClient(
            resource_group=request.azure_resource_group,
            location=request.azure_location,
            ssh_key_path=request.azure_ssh_key_path,
            ssh_username=request.user,
        )

    def _vm_name(self, request: VmRequest) -> str:
        return request.name or "nanofaas-azure"

    def _ssh_key(self, request: VmRequest) -> Path | None:
        if request.azure_ssh_key_path:
            return Path(request.azure_ssh_key_path)
        return _find_ssh_private_key()

    def remote_home(self, request: VmRequest) -> str:
        if request.home:
            return request.home
        if request.user == "root":
            return "/root"
        return f"/home/{request.user}"

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def connection_host(self, request: VmRequest) -> str:
        vm = self._client(request).get_vm(self._vm_name(request))
        return vm.wait_for_ip()

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        try:
            self._client(request).get_vm(name).delete()
        except VmNotFoundError:
            pass
        return _ok(["azure", "delete", name])
```

- [ ] **Step 3.4: Run teardown + remote_home tests to verify they pass**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_components.py -v -k "remote_home or remote_project or teardown"
```

Expected: 6 tests PASS.

- [ ] **Step 3.5: Add ensure_running tests**

Add to `tools/controlplane/tests/test_azure_vm_loadtest_components.py`:

```python
# ----------------------------------------------------------------- ensure_running

def test_ensure_running_calls_client_ensure_running(tmp_path, monkeypatch):
    mock_client = MagicMock()
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.ensure_running(_azure_request(azure_vm_size="Standard_B4ms"))

    mock_client.ensure_running.assert_called_once_with(
        "nanofaas-azure",
        vm_size="Standard_B4ms",
        image_urn=None,
        ssh_key_path=None,
    )
    assert result.return_code == 0


def test_ensure_running_passes_azure_fields(tmp_path, monkeypatch):
    mock_client = MagicMock()
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    orch.ensure_running(_azure_request(
        name="custom-vm",
        azure_vm_size="Standard_D2s_v3",
        azure_image_urn="Canonical:0001-com-ubuntu-server-noble:24_04-lts:latest",
        azure_ssh_key_path="/home/user/.ssh/id_rsa",
    ))

    mock_client.ensure_running.assert_called_once_with(
        "custom-vm",
        vm_size="Standard_D2s_v3",
        image_urn="Canonical:0001-com-ubuntu-server-noble:24_04-lts:latest",
        ssh_key_path="/home/user/.ssh/id_rsa",
    )
```

- [ ] **Step 3.6: Add ensure_running to azure_vm_adapter.py**

In `azure_vm_adapter.py`, add after `connection_host`:

```python
    def ensure_running(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        self._client(request).ensure_running(
            name,
            vm_size=request.azure_vm_size,
            image_urn=request.azure_image_urn,
            ssh_key_path=request.azure_ssh_key_path,
        )
        return _ok(["azure", "ensure_running", name])
```

- [ ] **Step 3.7: Run ensure_running tests**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_components.py -v -k "ensure_running"
```

Expected: 2 tests PASS.

- [ ] **Step 3.8: Add exec_argv and transfer tests**

Add to `tools/controlplane/tests/test_azure_vm_loadtest_components.py`:

```python
# ----------------------------------------------------------------- exec_argv

def test_exec_argv_calls_exec_structured_and_maps_result(tmp_path, monkeypatch):
    from azure_vm._backend import CommandResult as AzureResult

    mock_vm = MagicMock()
    mock_vm.exec_structured.return_value = AzureResult(
        args=[], returncode=0, stdout="hello", stderr=""
    )
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    result = orch.exec_argv(_azure_request(), ("echo", "hello"), cwd="/home/azureuser")

    mock_vm.exec_structured.assert_called_once_with(
        ["echo", "hello"], env=None, cwd="/home/azureuser"
    )
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_exec_argv_passes_env(tmp_path, monkeypatch):
    from azure_vm._backend import CommandResult as AzureResult

    mock_vm = MagicMock()
    mock_vm.exec_structured.return_value = AzureResult(
        args=[], returncode=0, stdout="", stderr=""
    )
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    orch = _make_orchestrator(tmp_path)
    orch.exec_argv(_azure_request(), ("k6", "run"), env={"NANOFAAS_URL": "http://1.2.3.4:30080"})

    mock_vm.exec_structured.assert_called_once_with(
        ["k6", "run"], env={"NANOFAAS_URL": "http://1.2.3.4:30080"}, cwd=None
    )


# ----------------------------------------------------------------- transfer_to

def test_transfer_to_calls_vm_transfer(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    source = tmp_path / "script.js"
    source.write_text("// k6 script")
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_to(_azure_request(), source=source, destination="/home/azureuser/script.js")

    mock_vm.transfer.assert_called_once_with(str(source), "/home/azureuser/script.js")
    assert result.return_code == 0


# ----------------------------------------------------------------- transfer_from

def test_transfer_from_uses_scp_subprocess(tmp_path, monkeypatch):
    mock_vm = MagicMock()
    mock_vm.wait_for_ip.return_value = "1.2.3.4"
    mock_client = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.azure_vm_adapter.AzureClient",
        lambda **kwargs: mock_client,
    )

    scp_calls: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        scp_calls.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr("controlplane_tool.infra.vm.azure_vm_adapter.subprocess.run", fake_run)

    dest = tmp_path / "k6-summary.json"
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_from(
        _azure_request(user="azureuser"),
        source="/home/azureuser/results/k6-summary.json",
        destination=dest,
    )

    assert len(scp_calls) == 1
    cmd = scp_calls[0]
    assert cmd[0] == "scp"
    assert "azureuser@1.2.3.4:/home/azureuser/results/k6-summary.json" in cmd
    assert str(dest) in cmd
    assert result.return_code == 0
```

- [ ] **Step 3.9: Add exec_argv and transfer methods to azure_vm_adapter.py**

Add to `AzureVmOrchestrator` after `ensure_running`:

```python
    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
    ) -> ShellExecutionResult:
        vm = self._client(request).get_vm(self._vm_name(request))
        result = vm.exec_structured(list(argv), env=env, cwd=cwd)
        return ShellExecutionResult(
            command=list(argv),
            return_code=result.returncode,
            stdout=result.stdout,
            stderr=result.stderr,
        )

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
    ) -> ShellExecutionResult:
        vm = self._client(request).get_vm(self._vm_name(request))
        vm.transfer(str(source), destination)
        return _ok(["scp", str(source), destination])

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
    ) -> ShellExecutionResult:
        ip = self._client(request).get_vm(self._vm_name(request)).wait_for_ip()
        key = self._ssh_key(request)
        cmd: list[str] = ["scp"]
        if key:
            cmd.extend(["-i", str(key)])
        cmd.extend([
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            f"{request.user}@{ip}:{source}",
            str(destination),
        ])
        proc = subprocess.run(cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            command=cmd,
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )
```

Add `import subprocess` at the top of the file (after the existing imports).

- [ ] **Step 3.10: Run all component tests**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_components.py -v
```

Expected: all 12 tests PASS.

- [ ] **Step 3.11: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py \
  tools/controlplane/tests/test_azure_vm_loadtest_components.py
git commit -m "feat: add AzureVmOrchestrator adapter"
```

---

## Task 4: Wire into TwoVmLoadtestRunner and e2e_runner

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Create: `tools/controlplane/tests/test_azure_vm_loadtest_runner.py`

- [ ] **Step 4.1: Write failing runner test**

Create `tools/controlplane/tests/test_azure_vm_loadtest_runner.py`:

```python
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
from controlplane_tool.infra.vm.vm_models import VmRequest


def _azure_request() -> E2eRequest:
    return E2eRequest(
        scenario="azure-vm-loadtest",
        runtime="java",
        vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure",
            user="azureuser",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
        loadgen_vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure-loadgen",
            user="azureuser",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
    )


def _ok() -> ShellExecutionResult:
    return ShellExecutionResult(command=[], return_code=0, stdout="")


def _write_default_k6_asset(repo_root: Path) -> Path:
    script_path = repo_root / "tools" / "controlplane" / "assets" / "k6" / "two-vm-function-invoke.js"
    script_path.parent.mkdir(parents=True)
    script_path.write_text("export default function () {}\n", encoding="utf-8")
    return script_path


def test_runner_accepts_azure_vm_orchestrator(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "1.2.3.4",
    )

    result = runner.run_k6(_azure_request())

    assert mock_orch.transfer_to.called
    assert mock_orch.exec_argv.called
    assert result.target_function == "word-stats-java"


def test_runner_transfers_script_to_loadgen_vm(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda _: "1.2.3.4",
    )
    runner.run_k6(_azure_request())

    transfer_calls = mock_orch.transfer_to.call_args_list
    transferred_destinations = [str(call.kwargs.get("destination", "")) for call in transfer_calls]
    assert any("script.js" in d for d in transferred_destinations)


def test_runner_executes_k6_with_control_plane_url(tmp_path):
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

    _write_default_k6_asset(tmp_path)
    mock_orch = MagicMock(spec=AzureVmOrchestrator)
    mock_orch.remote_home.return_value = "/home/azureuser"
    mock_orch.exec_argv.return_value = _ok()
    mock_orch.transfer_to.return_value = _ok()
    mock_orch.transfer_from.return_value = _ok()

    runner = TwoVmLoadtestRunner(
        repo_root=tmp_path,
        vm=mock_orch,
        runs_root=tmp_path / "runs",
        host_resolver=lambda vm: "10.0.0.1" if vm.name == "nanofaas-azure" else "10.0.0.2",
    )
    runner.run_k6(_azure_request())

    exec_calls = mock_orch.exec_argv.call_args_list
    all_exec_args = " ".join(str(c) for c in exec_calls)
    assert "http://10.0.0.1:30080" in all_exec_args
```

- [ ] **Step 4.2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_runner.py -v
```

Expected: `FAILED` — `TwoVmLoadtestRunner` refuses `AzureVmOrchestrator` (type mismatch at runtime).

Actually the test may fail because `AzureVmOrchestrator` isn't `VmOrchestrator` — the runner's default instantiation would fail. Verify the error message says something about `VmOrchestrator`.

- [ ] **Step 4.3: Widen TwoVmLoadtestRunner type annotation**

In `tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py`, update the import and `__init__` signature:

```python
# Add this import at the top (after existing imports):
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
```

```python
# Change the vm parameter type in __init__:
    def __init__(
        self,
        *,
        repo_root: Path,
        vm: VmOrchestrator | AzureVmOrchestrator | None = None,
        shell: ShellBackend | None = None,
        host_resolver: Callable[[VmRequest], str] | None = None,
        runs_root: Path | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        self.shell = shell or SubprocessShell()
        self.vm = vm or VmOrchestrator(self.repo_root, shell=self.shell)
        self.host_resolver = host_resolver
        self.runs_root = Path(runs_root) if runs_root is not None else self.paths.runs_dir
```

- [ ] **Step 4.4: Run runner tests**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_runner.py -v
```

Expected: all 3 tests PASS.

- [ ] **Step 4.5: Update e2e_runner.py — plan_recipe_steps**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`:

1. Add import at the top:
```python
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
```

2. At the beginning of `plan_recipe_steps`, after creating `runner`, add:
```python
    # Select the orchestrator based on VM lifecycle.
    vm_orch: VmOrchestrator | AzureVmOrchestrator
    if request.vm and request.vm.lifecycle == "azure":
        vm_orch = AzureVmOrchestrator(repo_root)
    else:
        vm_orch = runner.vm
```

3. Replace `runner.vm.remote_project_dir(vm_request)` with `vm_orch.remote_project_dir(vm_request)`.

4. Replace `TwoVmLoadtestRunner(repo_root=repo_root, vm=runner.vm, ...)` with `TwoVmLoadtestRunner(repo_root=repo_root, vm=vm_orch, ...)`.

5. Replace ALL five local closures that reference `runner.vm` with the versions below (find each by name and substitute):

```python
    def _on_ensure_running() -> None:
        vm_orch.ensure_running(vm_request)

    def _on_loadgen_ensure_running() -> None:
        vm_orch.ensure_running(loadgen_vm_request(context))

    def _on_vm_down() -> None:
        vm_orch.teardown(vm_request)

    def _on_loadgen_down() -> None:
        vm_orch.teardown(loadgen_vm_request(context))

    def _on_remote_exec(argv: tuple[str, ...], env: Mapping[str, str]) -> None:
        result = vm_orch.exec_argv(vm_request, argv, env=dict(env), cwd=remote_dir)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")
```

- [ ] **Step 4.6: Update e2e_runner.py — E2eRunner.plan**

In `E2eRunner.plan`, update the set of scenarios using `plan_recipe_steps`:

```python
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack",
                                 "two-vm-loadtest", "azure-vm-loadtest"}:
            plan_request = request
            recipe = build_scenario_recipe(request.scenario)
            if (request.vm is None and recipe.requires_managed_vm) or (
                request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"}
                and request.loadgen_vm is None
            ):
                context = resolve_scenario_environment(self.paths.workspace_root, request)
                updates: dict[str, object] = {}
                if request.vm is None and recipe.requires_managed_vm:
                    updates["vm"] = context.vm_request
                if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"} and request.loadgen_vm is None:
                    updates["loadgen_vm"] = loadgen_vm_request(context)
                plan_request = request.model_copy(update=updates)
```

- [ ] **Step 4.7: Update e2e_runner.py — E2eRunner.plan_all**

In `E2eRunner.plan_all`, locate the block:
```python
            if scenario.name == "two-vm-loadtest" and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(...)
```

Update it to also handle `azure-vm-loadtest`:
```python
            if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"} and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(
                    lifecycle=shared_vm_request.lifecycle,
                    name=(
                        "nanofaas-e2e-loadgen"
                        if scenario.name == "two-vm-loadtest"
                        else "nanofaas-azure-loadgen"
                    ),
                    host=shared_vm_request.host,
                    user=shared_vm_request.user,
                    home=shared_vm_request.home,
                    cpus=2,
                    memory="2G",
                    disk="10G",
                )
```

And in the inner loop, add `"azure-vm-loadtest"` to the `two-vm-loadtest` branch:
```python
                if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"}:
                    steps = plan_recipe_steps(
                        self.paths.workspace_root,
                        request,
                        scenario.name,
                        shell=self.shell,
                        manifest_root=self.manifest_root,
                        host_resolver=self._host_resolver,
                    )
                    vm_bootstrap_planned = True
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                    continue
```

- [ ] **Step 4.8: Run all runner and e2e tests**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_vm_loadtest_runner.py tests/test_two_vm_loadtest_runner.py tests/test_e2e_runner.py -v -q 2>&1 | tail -30
```

Expected: all tests PASS.

- [ ] **Step 4.9: Run full test suite**

```bash
cd tools/controlplane && uv run pytest tests/ -v -q --ignore=tests/integration -x 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 4.10: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/two_vm_loadtest_runner.py \
  tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
  tools/controlplane/tests/test_azure_vm_loadtest_runner.py
git commit -m "feat: wire AzureVmOrchestrator into TwoVmLoadtestRunner and e2e_runner"
```

---

## Task 5: Update CLI for azure lifecycle

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`

- [ ] **Step 5.1: Update _build_vm_request to accept azure fields**

In `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`, update `_build_vm_request`:

```python
def _build_vm_request(
    *,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
    azure_resource_group: str | None = None,
    azure_location: str | None = None,
    azure_vm_size: str = "Standard_B2s",
    azure_image_urn: str | None = None,
    azure_ssh_key_path: str | None = None,
) -> VmRequest:
    return VmRequest(
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
        azure_resource_group=azure_resource_group,
        azure_location=azure_location,
        azure_vm_size=azure_vm_size,
        azure_image_urn=azure_image_urn,
        azure_ssh_key_path=azure_ssh_key_path,
    )
```

- [ ] **Step 5.2: Update _build_request to handle azure-vm-loadtest**

In `_build_request`, add `azure_*` parameters to the function signature:

```python
def _build_request(
    *,
    ...  # existing params unchanged
    azure_resource_group: str | None = None,
    azure_location: str | None = None,
    azure_vm_size: str = "Standard_B2s",
    azure_image_urn: str | None = None,
    azure_ssh_key_path: str | None = None,
) -> E2eRequest:
```

Update the vm/loadgen_vm construction block:

```python
    if scenario in {
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "helm-stack",
        "two-vm-loadtest",
        "azure-vm-loadtest",
    }:
        stack_name = name
        if scenario == "two-vm-loadtest" and stack_name is None:
            stack_name = "nanofaas-e2e"
        if scenario == "azure-vm-loadtest" and stack_name is None:
            stack_name = "nanofaas-azure"
        vm = _build_vm_request(
            lifecycle=lifecycle,
            name=stack_name,
            host=host,
            user=user,
            home=home,
            cpus=cpus,
            memory=memory,
            disk=disk,
            azure_resource_group=azure_resource_group,
            azure_location=azure_location,
            azure_vm_size=azure_vm_size,
            azure_image_urn=azure_image_urn,
            azure_ssh_key_path=azure_ssh_key_path,
        )
    if scenario in {"two-vm-loadtest", "azure-vm-loadtest"}:
        loadgen_name_default = (
            "nanofaas-e2e-loadgen" if scenario == "two-vm-loadtest" else "nanofaas-azure-loadgen"
        )
        loadgen_vm = _build_vm_request(
            lifecycle=lifecycle,
            name=loadgen_name or loadgen_name_default,
            host=host,
            user=user,
            home=home,
            cpus=loadgen_cpus,
            memory=loadgen_memory,
            disk=loadgen_disk,
            azure_resource_group=azure_resource_group,
            azure_location=azure_location,
            azure_vm_size="Standard_B1s",
            azure_ssh_key_path=azure_ssh_key_path,
        )
```

- [ ] **Step 5.3: Update _default_selection_for**

```python
def _default_selection_for(scenario: str) -> ScenarioSelectionConfig:
    if scenario in {"container-local", "deploy-host"}:
        return ScenarioSelectionConfig(base_scenario=scenario, functions=["word-stats-java"])
    if scenario in {"helm-stack", "two-vm-loadtest", "azure-vm-loadtest"}:
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-loadtest")
    if scenario == "cli-stack":
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-java")
    return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-java")
```

- [ ] **Step 5.4: Add azure options to e2e_run command and update _resolve_run_request**

In `e2e_run`, add new parameters after `disk`:

```python
    azure_resource_group: str | None = typer.Option(None, "--azure-resource-group", help="Azure resource group (or set AZURE_RESOURCE_GROUP env var)."),
    azure_location: str | None = typer.Option(None, "--azure-location", help="Azure location (or set AZURE_LOCATION env var)."),
    azure_vm_size: str = typer.Option("Standard_B2s", "--azure-vm-size"),
    azure_image_urn: str | None = typer.Option(None, "--azure-image-urn"),
    azure_ssh_key_path: str | None = typer.Option(None, "--azure-ssh-key"),
```

Add default user override for azure lifecycle, just before calling `_resolve_run_request`:

```python
    # Azure cloud-init creates "azureuser" by default; override "ubuntu" default.
    effective_user = user if not (lifecycle == "azure" and user == "ubuntu") else "azureuser"
```

Pass `user=effective_user` (not `user`) and the azure params to `_resolve_run_request`.

Update `_resolve_run_request` signature — add these parameters after `k6_payload`:

```python
    azure_resource_group: str | None = None,
    azure_location: str | None = None,
    azure_vm_size: str = "Standard_B2s",
    azure_image_urn: str | None = None,
    azure_ssh_key_path: str | None = None,
```

At the end of `_resolve_run_request`, pass them to `_build_request`:

```python
    return _build_request(
        ...  # existing params unchanged
        azure_resource_group=azure_resource_group,
        azure_location=azure_location,
        azure_vm_size=azure_vm_size,
        azure_image_urn=azure_image_urn,
        azure_ssh_key_path=azure_ssh_key_path,
    )
```

Pass the same azure params from `e2e_run` to `_resolve_run_request` (6 extra kwargs).

- [ ] **Step 5.5: Run full test suite to verify no regressions**

```bash
cd tools/controlplane && uv run pytest tests/ -v -q --ignore=tests/integration -x 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 5.6: Verify the plan command works for azure-vm-loadtest (dry-run)**

```bash
cd tools/controlplane && uv run python -c "
from pathlib import Path
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest

runner = E2eRunner(Path('.').resolve())
request = E2eRequest(
    scenario='azure-vm-loadtest',
    runtime='java',
    vm=VmRequest(lifecycle='azure', name='nanofaas-azure', user='azureuser', azure_resource_group='my-rg', azure_location='westeurope'),
    loadgen_vm=VmRequest(lifecycle='azure', name='nanofaas-azure-loadgen', user='azureuser', azure_resource_group='my-rg', azure_location='westeurope'),
)
plan = runner.plan(request)
for step in plan.steps:
    print(f'  {step.step_id}: {step.summary}  [action={step.action is not None}]')
"
```

Expected: 7 steps printed, all with `action=True`.

- [ ] **Step 5.7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/cli/e2e_commands.py
git commit -m "feat: add azure lifecycle support to e2e CLI — --azure-resource-group, --azure-location, --azure-vm-size"
```

---

## Usage after implementation

Run an azure-vm-loadtest:

```bash
./scripts/controlplane.sh e2e run azure-vm-loadtest \
  --lifecycle azure \
  --azure-resource-group my-rg \
  --azure-location westeurope \
  --azure-vm-size Standard_B2s \
  --name nanofaas-azure
```

Pre-conditions:
- `az login` completed in the current shell environment
- `tofu` (OpenTofu) installed and available in PATH
- `AZURE_RESOURCE_GROUP` and `AZURE_LOCATION` set (or passed via `--azure-*` flags)
- The stack VM's cloud-init must deploy k3s + nanofaas control-plane + function-runtime
- The loadgen VM's cloud-init must install k6
- Pass cloud-init configs via `azure_image_urn` + a pre-baked image, or extend `AzureVmOrchestrator.ensure_running` to pass `cloud_init_config`
