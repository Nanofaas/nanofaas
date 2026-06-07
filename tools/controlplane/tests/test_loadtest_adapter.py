from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.scenario.loadtest_adapter import (
    InstallEndpoint,
    MultipassLoadtestAdapter,
)
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext


def test_install_endpoint_fields() -> None:
    ep = InstallEndpoint(host="1.2.3.4", user="ubuntu", private_key=Path("/k"), port=None)
    assert (ep.host, ep.user, ep.private_key, ep.port) == ("1.2.3.4", "ubuntu", Path("/k"), None)


def test_multipass_adapter_title_suffix_is_empty() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    assert adapter.title_suffix == ""


def test_multipass_adapter_extra_steps_are_empty() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    ctx = RunContext()
    assert adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx) == []
    assert adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx) == []
    assert adapter.extra_step_ids(FlowPhase.AFTER_STACK_READY) == []
    assert adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN) == []


def test_multipass_adapter_noop_capabilities() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    ctx = RunContext()
    assert adapter.emits_step_events() is False
    assert adapter.cleanup_on_failure(RuntimeError("x")) == []
    assert adapter.prelude_special_handler(ctx) is None
    assert adapter.prelude_context_selector(ctx) is None
    assert adapter.extra_step_titles(FlowPhase.AFTER_STACK_READY) == []
    assert adapter.extra_step_titles(FlowPhase.BEFORE_LOADGEN) == []


def test_multipass_adapter_register_functions_is_callable() -> None:
    """register_functions must exist and be callable (behavior tested via driver in Task 4)."""
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    assert callable(adapter.register_functions)


def test_multipass_connectivity_for_returns_static_connectivity() -> None:
    from controlplane_tool.scenario.connectivity import MultipassConnectivity

    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    assert isinstance(adapter.connectivity, MultipassConnectivity)
    # Same static MultipassConnectivity regardless of ctx/resolve_host.
    assert adapter.connectivity_for(None, resolve_host=False) is adapter.connectivity
    assert adapter.connectivity_for(RunContext(), resolve_host=True) is adapter.connectivity


# ── ProxmoxLoadtestAdapter ──────────────────────────────────────────────────


class _FakeProxmoxVmOrchestrator:
    """Minimal proxmox orchestrator double mirroring the fakes in
    test_proxmox_vm_loadtest_plan.py."""

    def __init__(self, repo_root=None) -> None:
        self.repo_root = repo_root
        self.torn_down: list[str] = []
        self.published: list[tuple[str, int]] = []

    def remote_project_dir(self, request):
        return "/home/ubuntu/nanofaas"

    def ssh_endpoint(self, request):
        return "10.0.0.10", 2222

    def ssh_private_key_path(self, request):
        return Path("/tmp/proxmox_key")

    def publish_port(self, request, *, service, guest_port):
        self.published.append((service, guest_port))
        return "127.0.0.1", 30090

    def teardown(self, request):
        self.torn_down.append(request.name)


def _proxmox_adapter(orch, *, cleanup_vm=True):
    from controlplane_tool.scenario.loadtest_adapter import ProxmoxLoadtestAdapter

    request = SimpleNamespace(
        scenario="proxmox-vm-loadtest",
        cleanup_vm=cleanup_vm,
        vm=SimpleNamespace(name="proxmox-stack", user="ubuntu"),
        loadgen_vm=SimpleNamespace(name="proxmox-loadgen", user="ubuntu"),
    )
    runner = SimpleNamespace(
        paths=SimpleNamespace(workspace_root=Path("/repo")),
        manifest_root=None,
    )
    adapter = ProxmoxLoadtestAdapter(runner=runner, request=request)
    adapter._proxmox_orch = orch  # inject the fake (no live proxmox)
    return adapter


def test_proxmox_adapter_title_suffix() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    assert adapter.title_suffix == " (Proxmox)"


def test_proxmox_adapter_emits_step_events() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    assert adapter.emits_step_events() is True


def test_proxmox_adapter_register_functions_is_noop() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    assert adapter.register_functions(RunContext()) is None


def test_proxmox_adapter_extra_step_ids_and_titles() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    assert adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN) == ["vm.stack.publish_ports"]
    assert adapter.extra_step_ids(FlowPhase.AFTER_STACK_READY) == []
    assert adapter.extra_step_titles(FlowPhase.BEFORE_LOADGEN) == ["Publish Proxmox NAT ports"]
    assert adapter.extra_step_titles(FlowPhase.AFTER_STACK_READY) == []


def test_proxmox_adapter_extra_steps_after_stack_ready_empty() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    assert adapter.extra_steps(FlowPhase.AFTER_STACK_READY, RunContext()) == []


def test_proxmox_connectivity_for_placeholder_when_not_resolving() -> None:
    from controlplane_tool.scenario.connectivity import ProxmoxConnectivity

    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    conn = adapter.connectivity_for(None, resolve_host=False)
    assert isinstance(conn, ProxmoxConnectivity)
    assert conn.host == "<proxmox-host>"
    assert conn.port == 0
    assert conn.key is None
    assert conn.remote_dir_value == "/home/ubuntu/nanofaas"


def test_proxmox_connectivity_for_resolves_endpoint() -> None:
    from controlplane_tool.scenario.connectivity import ProxmoxConnectivity

    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    conn = adapter.connectivity_for(RunContext(), resolve_host=True)
    assert isinstance(conn, ProxmoxConnectivity)
    assert conn.host == "10.0.0.10"
    assert conn.port == 2222
    assert conn.key == Path("/tmp/proxmox_key")


def test_proxmox_loadgen_install_endpoint_includes_port() -> None:
    adapter = _proxmox_adapter(_FakeProxmoxVmOrchestrator())
    ep = adapter.loadgen_install_endpoint(RunContext())
    assert ep.host == "10.0.0.10"
    assert ep.port == 2222
    assert ep.user == "ubuntu"
    assert ep.private_key == Path("/tmp/proxmox_key")


def test_proxmox_before_loadgen_publishes_prometheus_and_sets_url() -> None:
    orch = _FakeProxmoxVmOrchestrator()
    adapter = _proxmox_adapter(orch)
    ctx = RunContext()
    steps = adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx)
    assert [s.task_id for s in steps] == ["vm.stack.publish_ports"]
    steps[0].run()
    assert ("PROMETHEUS", 30090) in orch.published
    assert ctx.prometheus_url == "http://127.0.0.1:30090"
    assert adapter.prometheus_url(ctx) == "http://127.0.0.1:30090"


def test_proxmox_cleanup_on_failure_tears_down_loadgen_then_stack() -> None:
    orch = _FakeProxmoxVmOrchestrator()
    adapter = _proxmox_adapter(orch, cleanup_vm=True)
    errors = adapter.cleanup_on_failure(RuntimeError("boom"))
    assert errors == []
    assert orch.torn_down == ["proxmox-loadgen", "proxmox-stack"]


def test_proxmox_cleanup_on_failure_respects_cleanup_vm_false() -> None:
    orch = _FakeProxmoxVmOrchestrator()
    adapter = _proxmox_adapter(orch, cleanup_vm=False)
    assert adapter.cleanup_on_failure(RuntimeError("boom")) == []
    assert orch.torn_down == []
