# Proxmox VM Loadtest Scenario Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `proxmox-vm-loadtest` scenario to controlplane-tool that runs the two-VM k6 loadtest using Proxmox VE VMs (clone from template, NAT-optional, QEMU guest agent for command exec) — mirroring the `azure-vm-loadtest` scenario architecture.

**Architecture:** A new `ProxmoxVmProvider` lives in `workflow-tasks/vm/proxmox.py` and implements the same orchestrator contract as `AzureVmProvider` (ensure_running, teardown, connection_host, exec_argv, transfer_to, transfer_from). The controlplane-tool gets a new `proxmox-vm-loadtest` scenario registered in the catalog and a `ProxmoxVmLoadtestPlan` that mirrors `AzureVmLoadtestPlan`. Command execution uses the QEMU guest agent (no SSH needed); file transfer uses SCP to the VM's IP (reachable via LAN/VPN or NAT).

**Tech Stack:** Python 3.11+, `proxmox-sdk` (git), `proxmox_sdk.ProxmoxClient`, `proxmox_sdk.CommandResult`, `shellcraft.backend.ShellExecutionResult`, pydantic, pytest, uv.

---

## File Map

### New files
| File | Responsibility |
|------|----------------|
| `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py` | `ProxmoxVmProvider` — wraps `ProxmoxClient` into the orchestrator contract |
| `tools/workflow-tasks/tests/vm/test_proxmox_provider.py` | Unit tests for `ProxmoxVmProvider` |
| `tools/controlplane/src/controlplane_tool/infra/vm/proxmox_vm_adapter.py` | Re-export alias `ProxmoxVmOrchestrator = ProxmoxVmProvider` |
| `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` | `ProxmoxVmLoadtestPlan` and `build_proxmox_vm_loadtest_plan` |
| `tools/controlplane/tests/test_proxmox_vm_request.py` | VmRequest validates "proxmox" lifecycle |
| `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py` | Plan `task_ids` and `phase_titles` smoke tests |
| `tools/controlplane/tests/test_proxmox_vm_loadtest_components.py` | Orchestrator unit tests (ensure_running, teardown, exec_argv, transfers) |

### Modified files
| File | Change |
|------|--------|
| `tools/workflow-tasks/pyproject.toml` | Add `proxmox-sdk` git dependency |
| `tools/controlplane/pyproject.toml` | Add `proxmox-sdk` git dependency |
| `tools/workflow-tasks/src/workflow_tasks/vm/models.py` | Add `"proxmox"` to `VmLifecycle`; add proxmox fields to `VmRequest` |
| `tools/workflow-tasks/src/workflow_tasks/vm/adapters.py` | Add `ProxmoxVmAdapter` factory |
| `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py` | Export `ProxmoxVmProvider`, `ProxmoxVmAdapter` |
| `tools/workflow-tasks/src/workflow_tasks/__init__.py` | Re-export `ProxmoxVmProvider`, `ProxmoxVmAdapter` |
| `tools/workflow-tasks/tests/vm/test_vm_request.py` | Assert `"proxmox"` in `VmLifecycle.__args__` |
| `tools/workflow-tasks/tests/vm/test_vm_adapters.py` | Add `ProxmoxVmAdapter` factory test |
| `tools/workflow-tasks/tests/test_public_api.py` | Assert `ProxmoxVmProvider`, `ProxmoxVmAdapter` exported |
| `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py` | Add `ProxmoxVmAdapter` re-export |
| `tools/controlplane/src/controlplane_tool/core/models.py` | Add `"proxmox"` to `VmLifecycle`; add `"proxmox-vm-loadtest"` to `ScenarioName` and `VM_BACKED_SCENARIOS` |
| `tools/controlplane/src/controlplane_tool/scenario/catalog.py` | Add `proxmox-vm-loadtest` `ScenarioDefinition` |
| `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` | Handle `"proxmox-vm-loadtest"` in `plan()`, `plan_all()`, `_prepare_recipe_request()` |

---

## Task 1: Add proxmox-sdk dependency

**Files:**
- Modify: `tools/workflow-tasks/pyproject.toml`
- Modify: `tools/controlplane/pyproject.toml`

- [ ] **Step 1: Add dependency to workflow-tasks**

In `tools/workflow-tasks/pyproject.toml`, add to the `dependencies` list after the `azure-vm-sdk` line:

```toml
"proxmox-sdk @ git+https://github.com/miciav/proxmox-sdk.git@9e20703",
```

- [ ] **Step 2: Lock workflow-tasks**

```bash
cd tools/workflow-tasks && uv lock
```

Expected: lock file updates without errors.

- [ ] **Step 3: Add dependency to controlplane**

In `tools/controlplane/pyproject.toml`, add to the `dependencies` list after the `azure-vm-sdk` line:

```toml
"proxmox-sdk @ git+https://github.com/miciav/proxmox-sdk.git@9e20703",
```

- [ ] **Step 4: Lock controlplane**

```bash
cd tools/controlplane && uv lock
```

Expected: lock file updates without errors.

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/pyproject.toml tools/workflow-tasks/uv.lock \
        tools/controlplane/pyproject.toml tools/controlplane/uv.lock
git commit -m "chore: add proxmox-sdk git dependency to workflow-tasks and controlplane-tool"
```

---

## Task 2: Extend VmLifecycle and VmRequest for Proxmox

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/core/models.py`
- Test: `tools/workflow-tasks/tests/vm/test_vm_request.py`
- Test: `tools/controlplane/tests/test_proxmox_vm_request.py` (new)

- [ ] **Step 1: Write failing test in workflow-tasks (extend existing file)**

In `tools/workflow-tasks/tests/vm/test_vm_request.py`, add after the existing `test_vm_lifecycle_values` test:

```python
def test_vm_lifecycle_includes_proxmox() -> None:
    assert "proxmox" in VmLifecycle.__args__  # type: ignore[attr-defined]


def test_vm_request_proxmox_lifecycle() -> None:
    req = VmRequest(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        proxmox_host="192.168.1.100",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_node="pve",
        proxmox_template_id=9000,
    )
    assert req.lifecycle == "proxmox"
    assert req.proxmox_host == "192.168.1.100"
    assert req.proxmox_user == "root@pam"
    assert req.proxmox_node == "pve"
    assert req.proxmox_template_id == 9000
    assert req.proxmox_ssh_key_path is None


def test_vm_request_proxmox_fields_default_to_none() -> None:
    req = VmRequest(lifecycle="proxmox")
    assert req.proxmox_host is None
    assert req.proxmox_user is None
    assert req.proxmox_password is None
    assert req.proxmox_node is None
    assert req.proxmox_template_id is None
    assert req.proxmox_ssh_key_path is None
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_vm_request.py -q
```

Expected: FAIL — `"proxmox"` not in `VmLifecycle.__args__`, `proxmox_host` attribute error.

- [ ] **Step 3: Update VmLifecycle and VmRequest in workflow-tasks**

In `tools/workflow-tasks/src/workflow_tasks/vm/models.py`:

Replace:
```python
VmLifecycle = Literal["multipass", "external", "azure"]
```
With:
```python
VmLifecycle = Literal["multipass", "external", "azure", "proxmox"]
```

In the `VmRequest` class, add after the `azure_ssh_key_path` field:
```python
    proxmox_host: str | None = None
    proxmox_user: str | None = None
    proxmox_password: str | None = None
    proxmox_node: str | None = None
    proxmox_template_id: int | None = None
    proxmox_ssh_key_path: str | None = None
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_vm_request.py -q
```

Expected: PASS.

- [ ] **Step 5: Write failing controlplane test (new file)**

Create `tools/controlplane/tests/test_proxmox_vm_request.py`:

```python
from __future__ import annotations

import pytest
from pydantic import ValidationError

from controlplane_tool.infra.vm.vm_models import VmRequest


def test_vm_request_accepts_proxmox_lifecycle():
    request = VmRequest(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        user="ubuntu",
        proxmox_host="192.168.1.100",
        proxmox_user="root@pam",
        proxmox_node="pve",
        proxmox_template_id=9000,
    )
    assert request.lifecycle == "proxmox"
    assert request.proxmox_host == "192.168.1.100"
    assert request.proxmox_node == "pve"
    assert request.proxmox_template_id == 9000


def test_vm_request_proxmox_fields_have_defaults():
    request = VmRequest(lifecycle="proxmox")
    assert request.proxmox_host is None
    assert request.proxmox_user is None
    assert request.proxmox_password is None
    assert request.proxmox_node is None
    assert request.proxmox_template_id is None
    assert request.proxmox_ssh_key_path is None


def test_vm_request_rejects_unknown_lifecycle():
    with pytest.raises(ValidationError):
        VmRequest(lifecycle="foobar")
```

- [ ] **Step 6: Run controlplane tests to verify they pass (re-uses workflow-tasks change)**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_request.py -q
```

Expected: PASS (VmRequest comes from workflow-tasks which we already updated).

- [ ] **Step 7: Update VmLifecycle in controlplane core/models.py**

In `tools/controlplane/src/controlplane_tool/core/models.py`, replace line 13:
```python
VmLifecycle = Literal["multipass", "external", "azure"]
```
With:
```python
VmLifecycle = Literal["multipass", "external", "azure", "proxmox"]
```

- [ ] **Step 8: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/vm/models.py \
        tools/workflow-tasks/tests/vm/test_vm_request.py \
        tools/controlplane/src/controlplane_tool/core/models.py \
        tools/controlplane/tests/test_proxmox_vm_request.py
git commit -m "feat: add proxmox lifecycle to VmLifecycle and proxmox fields to VmRequest"
```

---

## Task 3: Create ProxmoxVmProvider

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`
- Create: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`

- [ ] **Step 1: Write the failing tests**

Create `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`:

```python
"""Tests for ProxmoxVmProvider."""
from __future__ import annotations

import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

from workflow_tasks.vm.models import VmRequest


def _make_provider() -> object:
    from workflow_tasks.vm.proxmox import ProxmoxVmProvider
    return ProxmoxVmProvider(repo_root=Path("/repo"))


def _make_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        user="ubuntu",
        proxmox_host="192.168.1.100",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_node="pve",
        proxmox_template_id=9000,
        proxmox_ssh_key_path="/home/user/.ssh/id_rsa",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_client_mock() -> tuple[MagicMock, MagicMock]:
    client = MagicMock()
    vm = MagicMock()
    vm.wait_for_ip.return_value = "10.0.0.10"
    client.get_vm.return_value = vm
    return client, vm


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_default(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu", home=None)
    assert provider.remote_home(req) == "/home/ubuntu"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_root(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="root", home=None)
    assert provider.remote_home(req) == "/root"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_custom(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(home="/custom/home")
    assert provider.remote_home(req) == "/custom/home"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_project_dir(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu", home=None)
    assert provider.remote_project_dir(req) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_uses_name_field(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(name="custom-vm")
    assert provider._vm_name(req) == "custom-vm"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_default(mock_cls) -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="proxmox")
    assert provider._vm_name(req) == "nanofaas-proxmox"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_key_from_request(mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path="/home/user/.ssh/id_ed25519")
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_ed25519")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
@patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=Path("/home/user/.ssh/id_rsa"))
def test_ssh_key_fallback(mock_find, mock_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_rsa")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_connection_host(mock_cls) -> None:
    client_mock, vm_mock = _make_client_mock()
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    host = provider.connection_host(req)
    assert host == "10.0.0.10"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_calls_client_ensure_running(mock_cls) -> None:
    client_mock = MagicMock()
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request(cpus=2, memory="4G", disk="20G")
    result = provider.ensure_running(req)
    client_mock.ensure_running.assert_called_once_with(
        "nanofaas-proxmox",
        9000,
        node="pve",
        cores=2,
        memory_mb=4096,
        disk_gb=20,
    )
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_handles_memory_without_suffix(mock_cls) -> None:
    client_mock = MagicMock()
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request(memory="2048")
    provider.ensure_running(req)
    _, kwargs = client_mock.ensure_running.call_args
    assert kwargs["memory_mb"] == 2048


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_calls_vm_delete(mock_cls) -> None:
    client_mock, vm_mock = _make_client_mock()
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    vm_mock.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_silences_vm_not_found(mock_cls) -> None:
    from proxmox_sdk.exceptions import VmNotFoundError
    client_mock, vm_mock = _make_client_mock()
    vm_mock.delete.side_effect = VmNotFoundError("gone")
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_calls_exec_structured_and_maps_result(mock_cls) -> None:
    client_mock, vm_mock = _make_client_mock()
    exec_result = MagicMock()
    exec_result.exit_code = 0
    exec_result.stdout = "hello"
    exec_result.stderr = ""
    vm_mock.exec_structured.return_value = exec_result
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.exec_argv(req, ["echo", "hello"])
    vm_mock.exec_structured.assert_called_once_with(["echo", "hello"], env=None, cwd=None)
    assert result.return_code == 0
    assert result.stdout == "hello"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_passes_env_and_cwd(mock_cls) -> None:
    client_mock, vm_mock = _make_client_mock()
    exec_result = MagicMock()
    exec_result.exit_code = 0
    exec_result.stdout = ""
    exec_result.stderr = ""
    vm_mock.exec_structured.return_value = exec_result
    mock_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    provider.exec_argv(req, ["k6", "run"], env={"NANOFAAS_URL": "http://10.0.0.1:30080"}, cwd="/home/ubuntu")
    vm_mock.exec_structured.assert_called_once_with(
        ["k6", "run"], env={"NANOFAAS_URL": "http://10.0.0.1:30080"}, cwd="/home/ubuntu"
    )


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_to_uses_scp(mock_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_client_mock()
    mock_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_to(req, source=Path("/local/script.js"), destination="/remote/script.js")
    assert result.return_code == 0
    assert "scp" in result.command
    cmd = mock_subproc.call_args[0][0]
    assert "/local/script.js" in cmd
    assert "ubuntu@10.0.0.10:/remote/script.js" in cmd


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from_uses_scp(mock_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_client_mock()
    mock_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()
    result = provider.transfer_from(req, source="/remote/k6-summary.json", destination=Path("/local/k6-summary.json"))
    assert result.return_code == 0
    assert "scp" in result.command
    cmd = mock_subproc.call_args[0][0]
    assert "ubuntu@10.0.0.10:/remote/k6-summary.json" in cmd
    assert "/local/k6-summary.json" in cmd


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from_no_ssh_key(mock_cls, mock_subproc) -> None:
    client_mock, vm_mock = _make_client_mock()
    mock_cls.return_value = client_mock
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    with patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=None):
        result = provider.transfer_from(req, source="/remote/file", destination=Path("/local"))
    assert result.return_code == 0
    assert "-i" not in result.command
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_proxmox_provider.py -q
```

Expected: FAIL — `ModuleNotFoundError: No module named 'workflow_tasks.vm.proxmox'`.

- [ ] **Step 3: Create ProxmoxVmProvider**

Create `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`:

```python
from __future__ import annotations

import subprocess
from pathlib import Path

from proxmox_sdk import ProxmoxClient
from proxmox_sdk.exceptions import VmNotFoundError
from shellcraft.backend import ShellExecutionResult

from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.vm.multipass import _find_ssh_private_key_path, _ok


def _parse_memory_mb(memory: str) -> int:
    """Convert memory string ('2G', '512M', '2048') to integer MB."""
    s = memory.strip().upper()
    if s.endswith("G"):
        return int(s[:-1]) * 1024
    if s.endswith("M"):
        return int(s[:-1])
    return int(s)


def _parse_disk_gb(disk: str) -> int:
    """Convert disk string ('20G', '512M') to integer GB."""
    s = disk.strip().upper()
    if s.endswith("G"):
        return int(s[:-1])
    if s.endswith("M"):
        return max(1, int(s[:-1]) // 1024)
    return int(s)


class ProxmoxVmProvider:
    """Proxmox VM provider: lifecycle, command execution, file transfer.

    Command execution uses the QEMU guest agent (no SSH needed).
    File transfer uses SCP to the VM's IP (requires SSH access to the VM).
    """

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _client(self, request: VmRequest) -> ProxmoxClient:
        return ProxmoxClient(
            host=request.proxmox_host or "",
            user=request.proxmox_user or "root@pam",
            password=request.proxmox_password,
            node=request.proxmox_node,
        )

    def _vm_name(self, request: VmRequest) -> str:
        return request.name or "nanofaas-proxmox"

    def _ssh_key(self, request: VmRequest) -> Path | None:
        if request.proxmox_ssh_key_path:
            return Path(request.proxmox_ssh_key_path)
        return _find_ssh_private_key_path()

    def remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def connection_host(self, request: VmRequest) -> str:
        vm = self._client(request).get_vm(self._vm_name(request))
        return vm.wait_for_ip()

    def ensure_running(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        template_id = request.proxmox_template_id or 0
        self._client(request).ensure_running(
            name,
            template_id,
            node=request.proxmox_node,
            cores=request.cpus,
            memory_mb=_parse_memory_mb(request.memory),
            disk_gb=_parse_disk_gb(request.disk),
        )
        return _ok(["proxmox", "ensure_running", name])

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        try:
            self._client(request).get_vm(name).delete()
        except VmNotFoundError:
            pass
        return _ok(["proxmox", "delete", name])

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        del dry_run
        vm = self._client(request).get_vm(self._vm_name(request))
        result = vm.exec_structured(list(argv), env=env, cwd=cwd)
        return ShellExecutionResult(
            command=list(argv),
            return_code=result.exit_code,
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
        ip = self._client(request).get_vm(self._vm_name(request)).wait_for_ip()
        key = self._ssh_key(request)
        cmd: list[str] = ["scp"]
        if key:
            cmd.extend(["-i", str(key)])
        cmd.extend([
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            str(source),
            f"{request.user}@{ip}:{destination}",
        ])
        proc = subprocess.run(cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            command=cmd,
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )

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

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_proxmox_provider.py -q
```

Expected: PASS.

- [ ] **Step 5: Run full workflow-tasks test suite**

```bash
cd tools/workflow-tasks && uv run pytest -q
```

Expected: PASS (coverage ≥ 90%).

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py \
        tools/workflow-tasks/tests/vm/test_proxmox_provider.py
git commit -m "feat: add ProxmoxVmProvider to workflow-tasks"
```

---

## Task 4: Export ProxmoxVmAdapter and update public API

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/adapters.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Modify: `tools/workflow-tasks/tests/vm/test_vm_adapters.py`
- Modify: `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Write failing tests**

In `tools/workflow-tasks/tests/vm/test_vm_adapters.py`, add at the end:

```python
def test_proxmox_vm_adapter_factory() -> None:
    from workflow_tasks.vm.adapters import ProxmoxVmAdapter
    orch = _make_orchestrator("10.0.0.50")
    adapter = ProxmoxVmAdapter(orch)
    config = VmConfig(name="proxmox-vm")

    info = adapter.ensure_running(config)
    assert info.host == "10.0.0.50"
```

In `tools/workflow-tasks/tests/test_public_api.py`, extend `test_public_api_exports_vm_infrastructure`:

```python
def test_public_api_exports_proxmox_provider() -> None:
    assert hasattr(workflow_tasks, "ProxmoxVmProvider")
    assert hasattr(workflow_tasks, "ProxmoxVmAdapter")
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_vm_adapters.py tests/test_public_api.py -q
```

Expected: FAIL — `ImportError: cannot import name 'ProxmoxVmAdapter'`.

- [ ] **Step 3: Add ProxmoxVmAdapter factory to adapters.py**

In `tools/workflow-tasks/src/workflow_tasks/vm/adapters.py`, add after `AzureVmAdapter`:

```python
def ProxmoxVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="proxmox")
```

- [ ] **Step 4: Update vm/__init__.py**

In `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py`:

Replace:
```python
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter
```
With:
```python
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, ProxmoxVmAdapter, VmLifecycleAdapter
```

Add `ProxmoxVmProvider` import after `AzureVmProvider`:
```python
from workflow_tasks.vm.proxmox import ProxmoxVmProvider
```

Update `__all__` to include:
```python
    "ProxmoxVmProvider", "ProxmoxVmAdapter",
```

The full updated `__all__` should be:
```python
__all__ = [
    "VmConfig", "VmInfo", "VmLifecycle", "VmRequest", "vm_request_from_env",
    "VmLifecycleProtocol",
    "EnsureVmRunning", "DestroyVm",
    "MultipassVmProvider", "AzureVmProvider", "ProxmoxVmProvider",
    "OrchestratorVmRunner", "VmFileFetcher",
    "VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter", "ProxmoxVmAdapter",
]
```

- [ ] **Step 5: Update workflow_tasks/__init__.py**

In `tools/workflow-tasks/src/workflow_tasks/__init__.py`:

In the `from workflow_tasks.vm import (...)` block, add `ProxmoxVmAdapter` and `ProxmoxVmProvider`:

```python
from workflow_tasks.vm import (
    AzureVmAdapter,
    AzureVmProvider,
    DestroyVm,
    EnsureVmRunning,
    MultipassVmAdapter,
    MultipassVmProvider,
    OrchestratorVmRunner,
    ProxmoxVmAdapter,
    ProxmoxVmProvider,
    VmConfig,
    VmFileFetcher,
    VmInfo,
    VmLifecycle,
    VmLifecycleAdapter,
    VmLifecycleProtocol,
    VmRequest,
    vm_request_from_env,
)
```

In `__all__`, update the vm section:
```python
    # vm
    "VmConfig", "VmInfo", "VmLifecycle", "VmRequest", "vm_request_from_env",
    "VmLifecycleProtocol",
    "EnsureVmRunning", "DestroyVm",
    "MultipassVmProvider", "AzureVmProvider", "ProxmoxVmProvider",
    "OrchestratorVmRunner", "VmFileFetcher",
    "VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter", "ProxmoxVmAdapter",
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd tools/workflow-tasks && uv run pytest tests/vm/test_vm_adapters.py tests/test_public_api.py -q
```

Expected: PASS.

- [ ] **Step 7: Run full test suite**

```bash
cd tools/workflow-tasks && uv run pytest -q
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/vm/adapters.py \
        tools/workflow-tasks/src/workflow_tasks/vm/__init__.py \
        tools/workflow-tasks/src/workflow_tasks/__init__.py \
        tools/workflow-tasks/tests/vm/test_vm_adapters.py \
        tools/workflow-tasks/tests/test_public_api.py
git commit -m "feat: export ProxmoxVmAdapter and ProxmoxVmProvider from workflow-tasks public API"
```

---

## Task 5: Create proxmox re-exports in controlplane

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/infra/vm/proxmox_vm_adapter.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`

- [ ] **Step 1: Create proxmox_vm_adapter.py (re-export alias)**

Create `tools/controlplane/src/controlplane_tool/infra/vm/proxmox_vm_adapter.py`:

```python
from __future__ import annotations

from workflow_tasks.vm.proxmox import ProxmoxVmProvider

ProxmoxVmOrchestrator = ProxmoxVmProvider

__all__ = ["ProxmoxVmOrchestrator", "ProxmoxVmProvider"]
```

- [ ] **Step 2: Update vm_lifecycle_adapters.py**

In `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`:

Replace:
```python
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter

__all__ = ["VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter"]
```
With:
```python
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, ProxmoxVmAdapter, VmLifecycleAdapter

__all__ = ["VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter", "ProxmoxVmAdapter"]
```

- [ ] **Step 3: Run controlplane tests to ensure no regressions**

```bash
cd tools/controlplane && uv run pytest -q --no-header 2>&1 | tail -5
```

Expected: all existing tests PASS.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/infra/vm/proxmox_vm_adapter.py \
        tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py
git commit -m "feat: add ProxmoxVmOrchestrator re-export to controlplane infra"
```

---

## Task 6: Register proxmox-vm-loadtest scenario

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/core/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`

- [ ] **Step 1: Write a failing test**

Create `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py` (partial — just the catalog test for now):

```python
from __future__ import annotations

from controlplane_tool.scenario.catalog import resolve_scenario


def test_proxmox_vm_loadtest_in_catalog():
    scenario = resolve_scenario("proxmox-vm-loadtest")
    assert scenario.name == "proxmox-vm-loadtest"
    assert scenario.requires_vm is True
    assert "java" in scenario.supported_runtimes
    assert "rust" in scenario.supported_runtimes
    assert scenario.grouped_phases is True
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py::test_proxmox_vm_loadtest_in_catalog -q
```

Expected: FAIL — `ValueError: Unknown scenario: proxmox-vm-loadtest`.

- [ ] **Step 3: Add "proxmox-vm-loadtest" to ScenarioName and VM_BACKED_SCENARIOS in core/models.py**

In `tools/controlplane/src/controlplane_tool/core/models.py`, extend `ScenarioName`:

```python
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
    "proxmox-vm-loadtest",
]
```

Add `"proxmox-vm-loadtest"` to `VM_BACKED_SCENARIOS`:

```python
VM_BACKED_SCENARIOS = frozenset(
    {
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "helm-stack",
        "two-vm-loadtest",
        "azure-vm-loadtest",
        "proxmox-vm-loadtest",
    }
)
```

- [ ] **Step 4: Register scenario in catalog.py**

In `tools/controlplane/src/controlplane_tool/scenario/catalog.py`, add after the `azure-vm-loadtest` entry (before the closing parenthesis of the tuple):

```python
    ScenarioDefinition(
        name="proxmox-vm-loadtest",
        description="Two-VM Proxmox load test: stack VM + k6 loadgen on Proxmox VE.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
```

- [ ] **Step 5: Run the catalog test**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py::test_proxmox_vm_loadtest_in_catalog -q
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/core/models.py \
        tools/controlplane/src/controlplane_tool/scenario/catalog.py \
        tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
git commit -m "feat: register proxmox-vm-loadtest scenario in catalog and models"
```

---

## Task 7: Create ProxmoxVmLoadtestPlan

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
- Modify: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py` (extend)
- Create: `tools/controlplane/tests/test_proxmox_vm_loadtest_components.py`

- [ ] **Step 1: Write failing plan tests (extend the file created in Task 6)**

In `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`, add after the existing test:

```python
from pathlib import Path
from unittest.mock import MagicMock


def test_proxmox_vm_loadtest_plan_has_expected_task_ids() -> None:
    """task_ids must include all steps for TUI dry-run planning."""
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan

    runner = MagicMock()
    runner.paths.workspace_root = Path("/repo")
    request = MagicMock()

    scenario = resolve_scenario("proxmox-vm-loadtest")
    plan = ProxmoxVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)

    ids = plan.task_ids
    assert "vm.stack.ensure_running" in ids
    assert "vm.loadgen.ensure_running" in ids
    assert "loadgen.install_k6" in ids
    assert "loadgen.run_k6" in ids
    assert "loadgen.fetch_results" in ids
    assert "metrics.prometheus_snapshot" in ids
    assert "loadtest.write_report" in ids
    assert "vm.loadgen.destroy" in ids


def test_proxmox_vm_loadtest_plan_phase_titles_contain_proxmox() -> None:
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan

    runner = MagicMock()
    runner.paths.workspace_root = Path("/repo")
    request = MagicMock()
    request.vm.name = "stack-vm"
    request.vm.cpus = 4
    request.vm.memory = "12G"
    request.vm.disk = "30G"
    request.loadgen_vm.name = "loadgen-vm"
    request.loadgen_vm.cpus = 2
    request.loadgen_vm.memory = "4G"
    request.loadgen_vm.disk = "20G"

    scenario = resolve_scenario("proxmox-vm-loadtest")
    plan = ProxmoxVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)

    titles = plan.phase_titles
    assert any("Proxmox" in t for t in titles)
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py -q
```

Expected: FAIL — `ImportError: No module named 'controlplane_tool.scenario.scenarios.proxmox_vm_loadtest'`.

- [ ] **Step 3: Write the orchestrator component tests**

Create `tools/controlplane/tests/test_proxmox_vm_loadtest_components.py`:

```python
from __future__ import annotations

import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.infra.vm.vm_models import VmRequest


def _proxmox_request(**kwargs) -> VmRequest:
    defaults = dict(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        user="ubuntu",
        proxmox_host="192.168.1.100",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_node="pve",
        proxmox_template_id=9000,
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_orchestrator(tmp_path: Path):
    from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator
    return ProxmoxVmOrchestrator(tmp_path)


def test_remote_home_uses_request_home_when_set(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _proxmox_request(home="/custom/home")
    assert orch.remote_home(request) == "/custom/home"


def test_remote_home_defaults_to_home_slash_user(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _proxmox_request(user="ubuntu")
    assert orch.remote_home(request) == "/home/ubuntu"


def test_remote_project_dir_appends_nanofaas(tmp_path):
    orch = _make_orchestrator(tmp_path)
    request = _proxmox_request(user="ubuntu")
    assert orch.remote_project_dir(request) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_calls_client(mock_cls, tmp_path):
    mock_client = MagicMock()
    mock_cls.return_value = mock_client

    orch = _make_orchestrator(tmp_path)
    result = orch.ensure_running(_proxmox_request(cpus=4, memory="8G", disk="20G"))

    mock_client.ensure_running.assert_called_once_with(
        "nanofaas-proxmox",
        9000,
        node="pve",
        cores=4,
        memory_mb=8192,
        disk_gb=20,
    )
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_calls_vm_delete(mock_cls, tmp_path):
    mock_client = MagicMock()
    mock_vm = MagicMock()
    mock_client.get_vm.return_value = mock_vm
    mock_cls.return_value = mock_client

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_proxmox_request())

    mock_vm.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_silences_vm_not_found(mock_cls, tmp_path):
    from proxmox_sdk.exceptions import VmNotFoundError

    mock_client = MagicMock()
    mock_vm = MagicMock()
    mock_vm.delete.side_effect = VmNotFoundError("gone")
    mock_client.get_vm.return_value = mock_vm
    mock_cls.return_value = mock_client

    orch = _make_orchestrator(tmp_path)
    result = orch.teardown(_proxmox_request())

    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_maps_result(mock_cls, tmp_path):
    from proxmox_sdk._backend import CommandResult as ProxmoxResult

    mock_client = MagicMock()
    mock_vm = MagicMock()
    mock_vm.exec_structured.return_value = ProxmoxResult(exit_code=0, stdout="hello", stderr="")
    mock_client.get_vm.return_value = mock_vm
    mock_cls.return_value = mock_client

    orch = _make_orchestrator(tmp_path)
    result = orch.exec_argv(_proxmox_request(), ("echo", "hello"), cwd="/home/ubuntu")

    mock_vm.exec_structured.assert_called_once_with(["echo", "hello"], env=None, cwd="/home/ubuntu")
    assert result.return_code == 0
    assert result.stdout == "hello"


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_to_uses_scp(mock_cls, mock_subproc, tmp_path):
    mock_client = MagicMock()
    mock_vm = MagicMock()
    mock_vm.wait_for_ip.return_value = "10.0.0.10"
    mock_client.get_vm.return_value = mock_vm
    mock_cls.return_value = mock_client

    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc

    source = tmp_path / "script.js"
    source.write_text("// k6 script")
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_to(_proxmox_request(), source=source, destination="/home/ubuntu/script.js")

    assert result.return_code == 0
    cmd = mock_subproc.call_args[0][0]
    assert cmd[0] == "scp"
    assert str(source) in cmd
    assert "ubuntu@10.0.0.10:/home/ubuntu/script.js" in cmd


@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from_uses_scp(mock_cls, mock_subproc, tmp_path):
    mock_client = MagicMock()
    mock_vm = MagicMock()
    mock_vm.wait_for_ip.return_value = "10.0.0.10"
    mock_client.get_vm.return_value = mock_vm
    mock_cls.return_value = mock_client

    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc

    dest = tmp_path / "k6-summary.json"
    orch = _make_orchestrator(tmp_path)
    result = orch.transfer_from(
        _proxmox_request(user="ubuntu"),
        source="/home/ubuntu/results/k6-summary.json",
        destination=dest,
    )

    assert result.return_code == 0
    cmd = mock_subproc.call_args[0][0]
    assert cmd[0] == "scp"
    assert "ubuntu@10.0.0.10:/home/ubuntu/results/k6-summary.json" in cmd
    assert str(dest) in cmd
```

- [ ] **Step 4: Run component tests to verify they fail**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_components.py -q
```

Expected: FAIL — `ImportError: No module named 'controlplane_tool.infra.vm.proxmox_vm_adapter'` (Task 5 should already be done, so this should actually PASS; if it does, skip to step 5).

- [ ] **Step 5: Create ProxmoxVmLoadtestPlan**

Create `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`:

```python
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks import (
    CapturePrometheusSnapshot,
    DestroyVm,
    EnsureVmRunning,
    FetchVmResults,
    InstallK6,
    RunK6,
    TimeWindow,
    Workflow,
    WriteK6Report,
    workflow_step,
)
from workflow_tasks.loadtest.models import K6Config, K6Stage
from workflow_tasks.vm.models import VmConfig

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator
from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import (
    HttpPrometheusClient,
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_PROMETHEUS_QUERIES,
    LOADTEST_STATIC_TASK_IDS,
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class ProxmoxVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return list(LOADTEST_STATIC_TASK_IDS)

    @property
    def phase_titles(self) -> list[str]:
        pre, wf = self._skeleton()
        return [t.title for t in pre] + wf.phase_titles

    def _skeleton(self) -> "tuple[list[EnsureVmRunning], Workflow]":
        """Task objects with None adapters — only task_id and title are valid here."""
        r = self.request
        sc = VmConfig(name=r.vm.name or "", cpus=r.vm.cpus, memory=r.vm.memory, disk=r.vm.disk)
        lc = VmConfig(name=r.loadgen_vm.name or "", cpus=r.loadgen_vm.cpus, memory=r.loadgen_vm.memory, disk=r.loadgen_vm.disk)
        pre = [
            EnsureVmRunning(task_id="vm.stack.ensure_running", title="Ensure stack VM running (Proxmox)", lifecycle=None, config=sc),  # type: ignore[arg-type]
            EnsureVmRunning(task_id="vm.loadgen.ensure_running", title="Ensure loadgen VM running (Proxmox)", lifecycle=None, config=lc),  # type: ignore[arg-type]
        ]
        wf = Workflow(
            tasks=[
                InstallK6(task_id="loadgen.install_k6", title="Install k6 on loadgen VM (Proxmox)", runner=None, remote_dir=None),  # type: ignore[arg-type]
                RunK6(task_id="loadgen.run_k6", title="Run k6 loadtest (Proxmox)", runner=None, config=None, remote_dir=None),  # type: ignore[arg-type]
                FetchVmResults(task_id="loadgen.fetch_results", title="Fetch k6 results from loadgen VM (Proxmox)", fetcher=None, remote_source=None, local_dest=None),  # type: ignore[arg-type]
                CapturePrometheusSnapshot(task_id="metrics.prometheus_snapshot", title="Capture Prometheus snapshots (Proxmox)", client=None, queries=None, window=None, output_dir=None),  # type: ignore[arg-type]
                WriteK6Report(task_id="loadtest.write_report", title="Write loadtest report (Proxmox)", data_dir=None, output_dir=None),  # type: ignore[arg-type]
            ],
            cleanup_tasks=[
                DestroyVm(task_id="vm.loadgen.destroy", title="Destroy loadgen VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
            ],
        )
        return pre, wf

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        request = self.request
        proxmox_orch = ProxmoxVmOrchestrator(repo_root=self.runner.paths.workspace_root)
        lifecycle = ProxmoxVmAdapter(proxmox_orch)
        run_dir_creator = TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root, vm=proxmox_orch
        )

        [s_ensure_stack, s_ensure_loadgen], s_wf = self._skeleton()
        [s_install_k6, s_run_k6, s_fetch, s_prom, s_report] = s_wf.tasks
        [s_destroy] = s_wf.cleanup_tasks

        stack_config = VmConfig(name=request.vm.name, cpus=request.vm.cpus, memory=request.vm.memory, disk=request.vm.disk)
        loadgen_config = VmConfig(name=request.loadgen_vm.name, cpus=request.loadgen_vm.cpus, memory=request.loadgen_vm.memory, disk=request.loadgen_vm.disk)

        ensure_stack = EnsureVmRunning(task_id=s_ensure_stack.task_id, title=s_ensure_stack.title, lifecycle=lifecycle, config=stack_config)
        ensure_loadgen = EnsureVmRunning(task_id=s_ensure_loadgen.task_id, title=s_ensure_loadgen.title, lifecycle=lifecycle, config=loadgen_config)

        with workflow_step(task_id=ensure_stack.task_id, title=ensure_stack.title):
            stack_info = ensure_stack.run()
        with workflow_step(task_id=ensure_loadgen.task_id, title=ensure_loadgen.title):
            loadgen_info = ensure_loadgen.run()

        remote_home = loadgen_info.home
        remote_paths = two_vm_remote_paths(
            remote_home,
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )
        run_dir = run_dir_creator._create_run_dir()  # noqa: SLF001
        control_plane_url = two_vm_control_plane_url(request.vm, host=stack_info.host)

        k6_config = K6Config(
            script_path=Path(remote_paths.script_path),
            target_url=control_plane_url,
            summary_output_path=Path(remote_paths.summary_path),
            stages=tuple(
                K6Stage(duration=d, target=t)
                for d, t in two_vm_load_stages(request)
            ),
            env={
                "NANOFAAS_URL": control_plane_url,
                "NANOFAAS_FUNCTION": two_vm_target_function(request),
                **(
                    {"NANOFAAS_PAYLOAD": str(remote_paths.payload_path)}
                    if remote_paths.payload_path
                    else {}
                ),
            },
            vus=request.k6_vus,
            duration=request.k6_duration,
            payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path else None,
        )

        loadgen_runner = OrchestratorVmRunner(proxmox_orch, request.loadgen_vm)
        fetcher = VmFileFetcher(vm=proxmox_orch, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )

        k6_task = RunK6(task_id=s_run_k6.task_id, title=s_run_k6.title, runner=loadgen_runner, config=k6_config, remote_dir=remote_home)

        workflow = Workflow(
            tasks=[
                InstallK6(task_id=s_install_k6.task_id, title=s_install_k6.title, runner=loadgen_runner, remote_dir=remote_home),
                k6_task,
                FetchVmResults(task_id=s_fetch.task_id, title=s_fetch.title, fetcher=fetcher, remote_source=remote_paths.summary_path, local_dest=run_dir),
                CapturePrometheusSnapshot(
                    task_id=s_prom.task_id, title=s_prom.title,
                    client=prom_client,
                    queries=LOADTEST_PROMETHEUS_QUERIES,
                    window=lambda: TimeWindow(start=k6_task.result.started_at, end=k6_task.result.ended_at),
                    output_dir=run_dir,
                ),
                WriteK6Report(task_id=s_report.task_id, title=s_report.title, data_dir=run_dir, output_dir=run_dir),
            ],
            cleanup_tasks=[
                DestroyVm(task_id=s_destroy.task_id, title=s_destroy.title, lifecycle=lifecycle, info=loadgen_info),
            ],
        )
        workflow.run()


def build_proxmox_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> ProxmoxVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("proxmox-vm-loadtest")
    return ProxmoxVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
```

- [ ] **Step 6: Run all plan tests**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py tests/test_proxmox_vm_loadtest_components.py -q
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py \
        tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py \
        tools/controlplane/tests/test_proxmox_vm_loadtest_components.py
git commit -m "feat: add ProxmoxVmLoadtestPlan scenario"
```

---

## Task 8: Wire proxmox-vm-loadtest in E2eRunner

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

The `e2e_runner.py` has three places that need updating. `"proxmox-vm-loadtest"` must be treated identically to `"azure-vm-loadtest"` throughout.

- [ ] **Step 1: Write a failing integration test**

Add to `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`:

```python
def test_e2e_runner_plan_builds_proxmox_plan() -> None:
    """E2eRunner.plan() must return a ProxmoxVmLoadtestPlan for proxmox-vm-loadtest."""
    from pathlib import Path
    from unittest.mock import MagicMock
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan

    runner = E2eRunner(Path("/repo"))
    vm = VmRequest(lifecycle="proxmox", name="stack-vm")
    loadgen = VmRequest(lifecycle="proxmox", name="loadgen-vm")
    from controlplane_tool.e2e.e2e_models import E2eRequest
    request = E2eRequest(scenario="proxmox-vm-loadtest", vm=vm, loadgen_vm=loadgen)
    plan = runner.plan(request)
    assert isinstance(plan, ProxmoxVmLoadtestPlan)
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py::test_e2e_runner_plan_builds_proxmox_plan -q
```

Expected: FAIL — `ValueError: Unsupported VM-backed scenario: 'proxmox-vm-loadtest'`.

- [ ] **Step 3: Update _prepare_recipe_request() in e2e_runner.py**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, locate `_prepare_recipe_request()`.

Find this condition:
```python
        if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"}
            and request.loadgen_vm is None
```
Replace with:
```python
        if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"}
            and request.loadgen_vm is None
```

Also find:
```python
            if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"} and request.loadgen_vm is None:
                updates["loadgen_vm"] = loadgen_vm_request(context)
```
Replace with:
```python
            if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"} and request.loadgen_vm is None:
                updates["loadgen_vm"] = loadgen_vm_request(context)
```

- [ ] **Step 4: Update plan() in e2e_runner.py**

In `plan()`, find:
```python
        if request.scenario == "azure-vm-loadtest":
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
            return build_azure_vm_loadtest_plan(self, self._prepare_recipe_request(request))
```
Add after it:
```python
        if request.scenario == "proxmox-vm-loadtest":
            from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
            return build_proxmox_vm_loadtest_plan(self, self._prepare_recipe_request(request))
```

- [ ] **Step 5: Update plan_all() in e2e_runner.py**

In `plan_all()`, locate the `loadgen_vm` construction block:
```python
            if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"} and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(
                    lifecycle=shared_vm_request.lifecycle,
                    name=(
                        "nanofaas-e2e-loadgen"
                        if scenario.name == "two-vm-loadtest"
                        else "nanofaas-azure-loadgen"
                    ),
```
Replace with:
```python
            if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"} and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(
                    lifecycle=shared_vm_request.lifecycle,
                    name=(
                        "nanofaas-e2e-loadgen"
                        if scenario.name == "two-vm-loadtest"
                        else "nanofaas-azure-loadgen"
                        if scenario.name == "azure-vm-loadtest"
                        else "nanofaas-proxmox-loadgen"
                    ),
```

Also in `plan_all()`, find:
```python
                if scenario.name == "azure-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
                    plans.append(build_azure_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
```
Add after it:
```python
                if scenario.name == "proxmox-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
                    plans.append(build_proxmox_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
```

Also update the condition in `plan_all()` that checks `scenario.name in {"two-vm-loadtest", "azure-vm-loadtest"}`:
```python
            if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"} and shared_vm_request is not None:
```

- [ ] **Step 6: Run the integration test**

```bash
cd tools/controlplane && uv run pytest tests/test_proxmox_vm_loadtest_plan.py -q
```

Expected: PASS all tests including the new `test_e2e_runner_plan_builds_proxmox_plan`.

- [ ] **Step 7: Run full controlplane test suite**

```bash
cd tools/controlplane && uv run pytest -q --no-header 2>&1 | tail -10
```

Expected: PASS. No regressions.

- [ ] **Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
git commit -m "feat: wire proxmox-vm-loadtest into E2eRunner plan() and plan_all()"
```

---

## Self-Review

### Spec coverage check

| Requirement | Task |
|------------|------|
| Add proxmox-sdk via uv (same as azure-vm-sdk) | Task 1 |
| VmLifecycle includes "proxmox" | Task 2 |
| VmRequest has proxmox config fields (host, user, password, node, template_id, ssh_key) | Task 2 |
| ProxmoxVmProvider with ensure_running / teardown / exec_argv / transfer_to / transfer_from | Task 3 |
| Command exec via QEMU guest agent | Task 3 (exec_structured) |
| File transfer via SCP using VM's IP | Task 3 |
| ProxmoxVmAdapter factory in adapters.py | Task 4 |
| ProxmoxVmProvider/Adapter exported in public API | Task 4 |
| controlplane re-export alias ProxmoxVmOrchestrator | Task 5 |
| scenario "proxmox-vm-loadtest" registered in catalog and ScenarioName literal | Task 6 |
| ProxmoxVmLoadtestPlan with skeleton + run() | Task 7 |
| E2eRunner.plan() returns ProxmoxVmLoadtestPlan | Task 8 |
| E2eRunner.plan_all() handles proxmox scenario | Task 8 |

### No placeholders found — all steps contain actual code.

### Type consistency
- `ProxmoxVmOrchestrator = ProxmoxVmProvider` — alias used in `proxmox_vm_loadtest.py` ✓
- `ProxmoxVmAdapter(proxmox_orch)` returns `VmLifecycleAdapter` — same as `AzureVmAdapter` ✓
- `LOADTEST_STATIC_TASK_IDS` shared between multipass/azure/proxmox plans — `task_ids` consistent ✓
- `two_vm_*` config helpers reused — port numbers, remote paths identical ✓
- `CommandResult.exit_code` (proxmox-sdk) vs `CommandResult.returncode` (azure-vm-sdk) — correctly mapped in `exec_argv` ✓
