# Proxmox NAT Connectivity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Proxmox VM workflows structurally support NAT-backed connectivity while preserving the existing VM provider symmetry: create VM, publish required NAT rules, then use SSH/SCP for remote execution and transfer.

**Architecture:** Keep Proxmox-specific networking inside `workflow_tasks.vm.proxmox.ProxmoxVmProvider`. Use `proxmox-sdk` APIs for all Proxmox VM and NAT operations: `ProxmoxClient`, `ProxmoxVM.wait_ready()`, `ProxmoxVM.wait_for_ip()`, `ProxmoxVM.stop()`, `ProxmoxVM.delete()`, `ProxmoxRoutingManager`, and `PortMapping`. Add a small endpoint-resolution layer in the provider so runner-facing operations use `proxmox_host:<published_port>`, while scenario code can still request the guest IP for VM-to-VM traffic.

**Tech Stack:** Python 3.12, pytest, workflow-tasks editable package, proxmox-sdk, shellcraft `ShellExecutionResult`, existing `OrchestratorVmRunner`/`VmFileFetcher` protocols.

---

## File Structure

- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`
  - Add Proxmox NAT publication and endpoint lookup helpers.
  - Keep `exec_argv()`, `transfer_to()`, and `transfer_from()` SSH/SCP-based.
  - Stop running VMs before delete.
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`
  - Cover NAT publication, SSH/SCP endpoint resolution, guest IP semantics, and cleanup behavior.
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
  - Use guest IP explicitly for loadgen-to-stack target URLs.
  - Use published Proxmox endpoint explicitly for runner-local Prometheus snapshot.
- Modify: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`
  - Add plan-level coverage only if the existing test fixture can execute `ProxmoxVmLoadtestPlan.run()` without live Proxmox; otherwise rely on provider unit tests and scenario import/build tests.

---

### Task 1: Run Required GitNexus Impact Analysis

**Files:**
- Read: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`
- Read: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`

- [ ] **Step 1: Analyze provider symbols before editing**

Use the GitNexus MCP `impact` tool with these payloads:

```json
{"target":"ProxmoxVmProvider.ensure_running","direction":"upstream","repo":"mcFaas"}
{"target":"ProxmoxVmProvider.connection_host","direction":"upstream","repo":"mcFaas"}
{"target":"ProxmoxVmProvider.exec_argv","direction":"upstream","repo":"mcFaas"}
{"target":"ProxmoxVmProvider.transfer_to","direction":"upstream","repo":"mcFaas"}
{"target":"ProxmoxVmProvider.transfer_from","direction":"upstream","repo":"mcFaas"}
{"target":"ProxmoxVmProvider.teardown","direction":"upstream","repo":"mcFaas"}
```

Expected: Identify direct callers, affected flows, and risk level. If any result is HIGH or CRITICAL, stop and report the blast radius before editing.

- [ ] **Step 2: Analyze scenario symbol before editing**

Use the GitNexus MCP `impact` tool with this payload:

```json
{"target":"ProxmoxVmLoadtestPlan.run","direction":"upstream","repo":"mcFaas"}
```

Expected: Identify the `proxmox-vm-loadtest` execution flow and any direct callers.

---

### Task 2: Add Tests for NAT Publication During Ensure Running

**Files:**
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`

- [ ] **Step 1: Add failing test for wait-ready, guest IP discovery, and NAT rule publication**

Add this test near `test_ensure_running`:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_waits_ready_and_publishes_ssh_nat(mock_client_cls, mock_routing_cls) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.node = "pve"
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    client_mock.ensure_running.return_value = vm_mock
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20000,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock

    provider = _make_provider()
    req = _make_request(cpus=2, memory="4G", disk="20G")

    result = provider.ensure_running(req)

    assert result.return_code == 0
    vm_mock.wait_ready.assert_called_once()
    vm_mock.wait_for_ip.assert_called_once()
    mgr_mock.add_rules.assert_called_once()
    mapping = mgr_mock.add_rules.call_args[0][0][0]
    assert mapping == PortMapping(
        vm_id=123,
        vm_name="test-vm",
        vm_ip="10.0.2.27",
        vm_port=22,
        service="SSH",
    )
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_ensure_running_waits_ready_and_publishes_ssh_nat -q
```

Expected: FAIL because `ensure_running()` does not call `wait_ready()` or `ProxmoxRoutingManager.add_rules()` yet.

---

### Task 2.5: Fix plan_all() — Propagate Proxmox Credentials to loadgen_vm

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

**Context:** When `plan_all()` synthesises the `loadgen_vm` VmRequest it copies only generic fields (lifecycle, name, host, user, home, cpus, memory, disk) but omits all `proxmox_*` credential fields. `OrchestratorVmRunner` and `VmFileFetcher` use `request.loadgen_vm` directly, so every SSH command to the loadgen VM fails with `proxmox_host=None`.

- [ ] **Step 1: Write a failing test**

In `tests/test_e2e_runner.py`, add near the existing proxmox plan_all tests:

```python
def test_plan_all_propagates_proxmox_credentials_to_loadgen_vm(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm_request = VmRequest(
        lifecycle="proxmox",
        proxmox_host="pve.example.com",
        proxmox_node="venus",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=101,
        proxmox_ssh_key_path="/home/user/.ssh/id_rsa",
    )
    plans = runner.plan_all(only=["proxmox-vm-loadtest"], vm_request=vm_request)

    assert len(plans) == 1
    loadgen_vm = plans[0].request.loadgen_vm
    assert loadgen_vm.proxmox_host == "pve.example.com"
    assert loadgen_vm.proxmox_node == "venus"
    assert loadgen_vm.proxmox_password == "secret"
    assert loadgen_vm.proxmox_template_id == 101
    assert loadgen_vm.proxmox_ssh_key_path == "/home/user/.ssh/id_rsa"
```

- [ ] **Step 2: Run the failing test**

Run from `tools/controlplane/`:
```bash
uv run pytest tests/test_e2e_runner.py::test_plan_all_propagates_proxmox_credentials_to_loadgen_vm -q
```

Expected: FAIL — `loadgen_vm.proxmox_host` is `None`.

- [ ] **Step 3: Fix plan_all() in e2e_runner.py**

Find the `loadgen_vm` synthesis in `plan_all()` (the `VmRequest(lifecycle=shared_vm_request.lifecycle, ...)` block) and add the proxmox fields:

```python
loadgen_vm = loadgen_vm_request or VmRequest(
    lifecycle=shared_vm_request.lifecycle,
    name=...,
    host=shared_vm_request.host,
    user=shared_vm_request.user,
    home=shared_vm_request.home,
    cpus=2,
    memory="2G",
    disk="10G",
    proxmox_host=shared_vm_request.proxmox_host,
    proxmox_node=shared_vm_request.proxmox_node,
    proxmox_user=shared_vm_request.proxmox_user,
    proxmox_password=shared_vm_request.proxmox_password,
    proxmox_template_id=shared_vm_request.proxmox_template_id,
    proxmox_ssh_key_path=shared_vm_request.proxmox_ssh_key_path,
)
```

- [ ] **Step 4: Run test and verify it passes**

```bash
uv run pytest tests/test_e2e_runner.py::test_plan_all_propagates_proxmox_credentials_to_loadgen_vm -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_e2e_runner.py
git commit -m "fix(e2e): propagate proxmox credentials to loadgen_vm in plan_all()"
```

---

### Task 3: Implement Proxmox NAT Publication Helpers

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`

- [ ] **Step 1: Add helper methods that only orchestrate proxmox-sdk APIs**

Update imports:

```python
from proxmox_sdk.routing import PortMapping, ProxmoxRoutingManager
```

Add these methods inside `ProxmoxVmProvider`:

```python
    def _publish_ssh(self, request: VmRequest, *, vm: object, guest_ip: str) -> PortMapping:
        mapping = PortMapping(
            vm_id=int(vm.vm_id),
            vm_name=self._vm_name(request),
            vm_ip=guest_ip,
            vm_port=22,
            service="SSH",
        )
        [published] = self._routing_manager(request).add_rules([mapping])
        if published.host_port is None:
            raise RuntimeError(f"Proxmox SSH NAT rule for {mapping.vm_name} has no host port")
        return published

    def _published_rule(self, request: VmRequest, service: str = "SSH") -> PortMapping:
        name = self._vm_name(request)
        rules = [
            r for r in self._routing_manager(request).list_rules()
            if r.vm_name == name and r.service == service
        ]
        if not rules:
            raise RuntimeError(f"Missing Proxmox NAT rule for {name} service {service}")
        rule = rules[0]
        if rule.host_port is None:
            raise RuntimeError(f"Proxmox NAT rule for {name} service {service} has no host port")
        return rule

    def _ssh_endpoint(self, request: VmRequest) -> tuple[str, int]:
        name = self._vm_name(request)
        if name in self._ssh_endpoints:
            return self._ssh_endpoints[name]
        try:
            rule = self._published_rule(request, "SSH")
            return request.proxmox_host or "", int(rule.host_port)
        except Exception:
            # Fallback: direct VM IP (non-NAT setup)
            return self.connection_host(request), 22
```

- [ ] **Step 2: Add `_ssh_endpoints` cache in `__init__` and populate in `ensure_running()`**

Add `self._ssh_endpoints: dict[str, tuple[str, int]] = {}` in `ProxmoxVmProvider.__init__`:

```python
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)
        self._ssh_endpoints: dict[str, tuple[str, int]] = {}
```

Update `ensure_running()` to publish SSH and cache the endpoint:

```python
        vm = client.ensure_running(
            name,
            template_id=request.proxmox_template_id,
            node=request.proxmox_node,
            cores=cores,
            memory_mb=memory_mb,
            disk_gb=disk_gb,
        )
        vm.wait_ready()
        guest_ip = vm.wait_for_ip()
        published = self._publish_ssh(request, vm=vm, guest_ip=guest_ip)
        self._ssh_endpoints[name] = (request.proxmox_host or "", int(published.host_port))
        return _ok(["proxmox", "ensure_running", name])
```

- [ ] **Step 3: Run NAT publication test**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_ensure_running_waits_ready_and_publishes_ssh_nat -q
```

Expected: PASS.

---

### Task 4: Keep Guest IP Semantics Explicit

**Files:**
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`

- [ ] **Step 1: Replace `test_connection_host` with explicit guest host expectation**

Update the test name and assertions:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_connection_host_returns_guest_ip(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()

    host = provider.connection_host(req)

    assert host == "192.168.1.100"
    client_mock.get_vm.assert_called_once_with("test-vm")
    vm_mock.wait_for_ip.assert_called_once()
```

- [ ] **Step 2: Add alias method test for Proxmox guest host**

Add:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_guest_host_returns_guest_ip(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()

    assert provider.guest_host(req) == "192.168.1.100"
```

- [ ] **Step 3: Implement `guest_host()` as the explicit VM-internal API**

Add inside `ProxmoxVmProvider`:

```python
    def guest_host(self, request: VmRequest) -> str:
        return self.connection_host(request)
```

- [ ] **Step 4: Run guest host tests**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_connection_host_returns_guest_ip ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_guest_host_returns_guest_ip -q
```

Expected: PASS.

---

### Task 5: Route Exec and Transfer Through Published SSH Endpoint

**Files:**
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`

- [ ] **Step 1: Add helper fixture logic in tests for existing NAT rule**

Add helper:

```python
def _mock_ssh_nat_rule(mock_routing_cls, *, host_port: int = 20000) -> MagicMock:
    from proxmox_sdk.routing import PortMapping

    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=host_port,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    return mgr_mock
```

- [ ] **Step 2: Update exec test to assert Proxmox host and NAT port**

Change decorators and assertions for `test_exec_argv`:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = "output"
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.exec_argv(req, ["echo", "hello"])

    assert result.return_code == 0
    assert result.stdout == "output"
    called_cmd = mock_subproc.call_args[0][0]
    assert called_cmd[:6] == ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "-p", "20022"]
    assert "ubuntu@pve.example.com" in called_cmd
    assert "echo hello" in called_cmd[-1]
```

- [ ] **Step 3: Update env/cwd test to include routing mock**

Change decorators and setup:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_with_cwd_and_env(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()

    result = provider.exec_argv(req, ["ls"], cwd="/home/ubuntu", env={"FOO": "bar"})

    assert result.return_code == 0
    remote_cmd = mock_subproc.call_args[0][0][-1]
    assert "cd /home/ubuntu" in remote_cmd
    assert "FOO=bar" in remote_cmd
    assert "ls" in remote_cmd
```

- [ ] **Step 4: Update transfer tests to assert `scp -P` and Proxmox host**

For `test_transfer_to`, use:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_to(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")

    assert result.return_code == 0
    cmd = result.command
    assert cmd[:3] == ["scp", "-P", "20022"]
    assert "/local/file" in cmd
    assert "ubuntu@pve.example.com:/remote/file" in cmd
```

For `test_transfer_from`, use:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.transfer_from(req, source="/remote/file", destination=Path("/local/file"))

    assert result.return_code == 0
    cmd = result.command
    assert cmd[:3] == ["scp", "-P", "20022"]
    assert "ubuntu@pve.example.com:/remote/file" in cmd
    assert "/local/file" in cmd
```

- [ ] **Step 5: Implement SSH/SCP endpoint use**

In `exec_argv()`, replace:

```python
        host = self.connection_host(request)
```

with:

```python
        host, port = self._ssh_endpoint(request)
```

and add the port option:

```python
        ssh_cmd = ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "-p", str(port)]
```

In `transfer_to()` and `transfer_from()`, replace direct `connection_host()` use with:

```python
        host, port = self._ssh_endpoint(request)
```

and start `scp_cmd` with:

```python
        scp_cmd = ["scp", "-P", str(port)]
```

- [ ] **Step 6: Run Proxmox provider tests**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py -q
```

Expected: PASS.

---

### Task 6: Make Proxmox Teardown Stop Running VMs and Remove NAT Rules

**Files:**
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`

- [ ] **Step 1: Add test for stop-before-delete**

Add:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_stops_running_vm_before_delete(mock_client_cls, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "running"
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls)
    provider = _make_provider()
    req = _make_request()

    result = provider.teardown(req)

    assert result.return_code == 0
    vm_mock.stop.assert_called_once()
    vm_mock.delete.assert_called_once()
```

- [ ] **Step 2: Update existing teardown NAT test to include VM state**

Add before `provider = _make_provider()` in `test_teardown_removes_nat_rules_for_vm`:

```python
    vm_mock.info.return_value.state.value = "stopped"
```

- [ ] **Step 3: Implement stop-before-delete**

Replace the VM delete block in `teardown()`:

```python
        try:
            vm = client.get_vm(name)
            if vm.info().state.value == "running":
                vm.stop()
            vm.delete()
        except VmNotFoundError:
            pass
```

Keep the existing `ProxmoxRoutingManager.list_rules()` and `remove_rules()` logic after delete, so NAT cleanup remains best-effort.

- [ ] **Step 4: Respect --no-cleanup-vm in ProxmoxVmLoadtestPlan**

**File:** `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`

Add a test in `tests/test_proxmox_vm_loadtest_plan.py`:

```python
def test_proxmox_vm_loadtest_plan_skips_destroy_when_no_cleanup(tmp_path) -> None:
    from unittest.mock import MagicMock
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan

    runner = MagicMock()
    runner.paths.workspace_root = "/workspace"
    request = MagicMock()
    request.cleanup_vm = False
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    _, wf = plan._skeleton()
    assert wf.cleanup_tasks == []
```

Then in `ProxmoxVmLoadtestPlan._skeleton()`, replace the hardcoded cleanup_tasks:

```python
            cleanup_tasks=[
                DestroyVm(task_id="vm.loadgen.destroy", title="Destroy loadgen VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
                DestroyVm(task_id="vm.stack.destroy", title="Destroy stack VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
            ],
```

with a conditional:

```python
            cleanup_tasks=(
                [
                    DestroyVm(task_id="vm.loadgen.destroy", title="Destroy loadgen VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
                    DestroyVm(task_id="vm.stack.destroy", title="Destroy stack VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
                ]
                if getattr(r, "cleanup_vm", True)
                else []
            ),
```

Do the same in `run()` for the live `Workflow(cleanup_tasks=[...])`.

- [ ] **Step 5: Run teardown tests**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_teardown_stops_running_vm_before_delete ../workflow-tasks/tests/vm/test_proxmox_provider.py::test_teardown_removes_nat_rules_for_vm -q
uv run pytest tests/test_proxmox_vm_loadtest_plan.py -q
```

Expected: PASS.

---

### Task 7: Publish Non-SSH Proxmox Services for Runner-Local HTTP Access

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/proxmox.py`
- Modify: `tools/workflow-tasks/tests/vm/test_proxmox_provider.py`

- [ ] **Step 1: Add provider method tests for published HTTP endpoint**

Add:

```python
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_publish_port_returns_runner_facing_endpoint(mock_client_cls, mock_routing_cls) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.50"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.50",
            vm_port=30090,
            service="PROMETHEUS",
            host_port=20090,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    endpoint = provider.publish_port(req, service="PROMETHEUS", guest_port=30090)

    assert endpoint == ("pve.example.com", 20090)
    mgr_mock.add_rules.assert_called_once()
```

- [ ] **Step 2: Implement generic `publish_port()` through proxmox-sdk**

Add inside `ProxmoxVmProvider`:

```python
    def publish_port(self, request: VmRequest, *, service: str, guest_port: int) -> tuple[str, int]:
        vm = self._client(request).get_vm(self._vm_name(request))
        guest_ip = vm.wait_for_ip()
        mapping = PortMapping(
            vm_id=int(vm.vm_id),
            vm_name=self._vm_name(request),
            vm_ip=guest_ip,
            vm_port=guest_port,
            service=service,
        )
        [published] = self._routing_manager(request).add_rules([mapping])
        if published.host_port is None:
            raise RuntimeError(f"Proxmox NAT rule for {mapping.vm_name} service {service} has no host port")
        return request.proxmox_host or "", int(published.host_port)
```

- [ ] **Step 3: Add lookup method for existing published HTTP endpoint**

Add:

```python
    def published_endpoint(self, request: VmRequest, *, service: str) -> tuple[str, int]:
        rule = self._published_rule(request, service)
        return request.proxmox_host or "", int(rule.host_port)
```

- [ ] **Step 4: Run provider tests**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm/test_proxmox_provider.py -q
```

Expected: PASS.

---

### Task 8: Use Explicit Proxmox Guest/Public Endpoints in Loadtest Scenario

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`

- [ ] **Step 1: Run impact analysis before scenario edit**

Use the GitNexus MCP `impact` tool with this payload:

```json
{"target":"ProxmoxVmLoadtestPlan.run","direction":"upstream","repo":"mcFaas"}
```

Expected: Report affected flows. Stop if HIGH or CRITICAL.

- [ ] **Step 2: Publish Prometheus endpoint for local report capture**

Import the port constant if not already in the import list:

```python
    TWO_VM_PROMETHEUS_NODE_PORT,
```

After `stack_info` and `loadgen_info` are created, add:

```python
        stack_guest_host = proxmox_orch.guest_host(request.vm)
        prometheus_host, prometheus_port = proxmox_orch.publish_port(
            request.vm,
            service="PROMETHEUS",
            guest_port=TWO_VM_PROMETHEUS_NODE_PORT,
        )
```

- [ ] **Step 3: Use guest IP for loadgen-to-stack k6 target**

Replace:

```python
        control_plane_url = two_vm_control_plane_url(request.vm, host=stack_info.host)
```

with:

```python
        control_plane_url = two_vm_control_plane_url(request.vm, host=stack_guest_host)
```

- [ ] **Step 4: Use published endpoint for runner-local Prometheus client**

Replace:

```python
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )
```

with:

```python
        prom_client = HttpPrometheusClient(
            url=f"http://{prometheus_host}:{prometheus_port}"
        )
```

- [ ] **Step 5: Run targeted controlplane tests**

Run:

```bash
uv run pytest tests/test_proxmox_vm_loadtest_plan.py tests/test_scenario_builders.py -q
```

Expected: PASS.

---

### Task 9: Run Full Relevant Test Set

**Files:**
- Verify only.

- [ ] **Step 1: Run workflow-tasks VM tests**

Run:

```bash
uv run pytest ../workflow-tasks/tests/vm -q
```

Expected: PASS.

- [ ] **Step 2: Run controlplane Proxmox and loadtest tests**

Run:

```bash
uv run pytest tests/test_proxmox_vm_loadtest_plan.py tests/test_e2e_commands.py tests/test_scenario_builders.py tests/test_two_vm_loadtest_runner.py -q
```

Expected: PASS.

- [ ] **Step 3: Run GitNexus change detection**

Use the GitNexus MCP `detect_changes` tool with this payload:

```json
{"scope":"all","repo":"mcFaas"}
```

Expected: Changed symbols match `ProxmoxVmProvider` and `ProxmoxVmLoadtestPlan.run`; affected flows match Proxmox VM loadtest and VM provider usage.

---

### Task 10: Live Proxmox Validation

**Files:**
- Verify only.

- [ ] **Step 1: Clean up existing failed VMs if still present**

Use the tool’s cleanup path or Proxmox UI/API. Do not use raw destructive commands unless explicitly approved.

- [ ] **Step 2: Run a live Proxmox loadtest**

Run:

```bash
uv run controlplane-tool e2e run proxmox-vm-loadtest --lifecycle proxmox
```

Expected:

```text
[ok] Ensure stack VM running (Proxmox)
[ok] Ensure loadgen VM running (Proxmox)
[ok] Install k6 on loadgen VM (Proxmox)
[ok] Run k6 loadtest (Proxmox)
[ok] Fetch k6 results from loadgen VM (Proxmox)
[ok] Capture Prometheus snapshots (Proxmox)
[ok] Write loadtest report (Proxmox)
[ok] Destroy loadgen VM (Proxmox)
[ok] Destroy stack VM (Proxmox)
```

- [ ] **Step 3: Verify NAT rules are removed**

Run a small inspection with `ProxmoxRoutingManager.list_rules()` and assert no rules remain for:

```text
nanofaas-proxmox
nanofaas-proxmox-loadgen
```

Expected: no remaining rules for those VM names.
