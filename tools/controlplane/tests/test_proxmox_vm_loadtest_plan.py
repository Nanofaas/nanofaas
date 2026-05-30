"""Tests for proxmox-vm-loadtest scenario registration."""
from __future__ import annotations

import pytest


def test_proxmox_vm_loadtest_in_scenario_catalog() -> None:
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("proxmox-vm-loadtest")
    assert scenario.name == "proxmox-vm-loadtest"
    assert scenario.requires_vm is True
    assert scenario.grouped_phases is True


def test_proxmox_vm_loadtest_in_vm_backed_scenarios() -> None:
    from controlplane_tool.core.models import VM_BACKED_SCENARIOS
    assert "proxmox-vm-loadtest" in VM_BACKED_SCENARIOS


def test_build_proxmox_vm_loadtest_plan_returns_correct_type(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan, ProxmoxVmLoadtestPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    assert isinstance(plan, ProxmoxVmLoadtestPlan)


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


def test_proxmox_vm_loadtest_plan_phase_titles_count(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    assert len(plan.phase_titles) == len(plan.task_ids)


def test_proxmox_vm_loadtest_plan_skips_destroy_when_no_cleanup(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
        cleanup_vm=False,
    )
    plan = build_proxmox_vm_loadtest_plan(runner=runner, request=request)
    _, wf = plan._skeleton()
    assert wf.cleanup_tasks == []


def test_e2e_runner_plan_returns_proxmox_vm_loadtest_plan(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    plan = runner.plan(request)
    assert isinstance(plan, ProxmoxVmLoadtestPlan)
    assert "loadgen.run_k6" in plan.task_ids
    assert "vm.stack.ensure_running" in plan.task_ids


def test_proxmox_vm_loadtest_cleans_up_vms_and_nat_when_prelude_fails(monkeypatch, tmp_path) -> None:
    from pathlib import Path
    from types import SimpleNamespace

    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan

    torn_down: list[str] = []

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        # run() ensures the stack VM (silent prerequisite) before building the
        # honest prelude tasks; these stubs satisfy the EnsureVmRunning path.
        def ensure_running(self, request):
            return SimpleNamespace()

        def connection_host(self, request):
            return "10.0.0.10"

        def teardown(self, request):
            torn_down.append(request.name)

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmoxVmOrchestrator,
    )

    # Make the honest prelude FAIL via a synthetic honest Task (bypasses real
    # _build_prelude_tasks / SSH resolution): run() must run the honest prelude
    # tasks, so the failure here proves it does — and trigger NAT/VM cleanup.
    def fake_build_prelude_tasks(self, proxmox_orch, stack_request, *, resolve_host=True):
        return [
            CallableTask(
                task_id="prelude.fail",
                title="Fail prelude",
                action=lambda: (_ for _ in ()).throw(RuntimeError("prelude exploded")),
            )
        ]

    monkeypatch.setattr(
        ProxmoxVmLoadtestPlan, "_build_prelude_tasks", fake_build_prelude_tasks
    )

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
        cleanup_vm=True,
    )
    plan = ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )

    with pytest.raises(RuntimeError, match="prelude exploded"):
        plan.run()

    assert torn_down == ["proxmox-loadgen", "proxmox-stack"]


def test_proxmox_vm_loadtest_tail_events_start_after_prelude(monkeypatch, tmp_path) -> None:
    from pathlib import Path
    from types import SimpleNamespace

    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def teardown(self, request):
            return None

    class FakeTwoVmLoadtestRunner:
        def __init__(self, repo_root, vm):
            self.repo_root = repo_root
            self.vm = vm

        def _create_run_dir(self):
            return Path("/tmp/proxmox-run")

    class FakeEnsureVmRunning:
        def __init__(self, *, task_id, title, lifecycle, config):
            self.task_id = task_id
            self.title = title

        def run(self):
            return SimpleNamespace(host="10.0.0.10", home="/home/ubuntu")

    class FakeTask:
        def __init__(self, *, task_id, title, **kwargs):
            self.task_id = task_id
            self.title = title
            self.result = SimpleNamespace(started_at=1.0, ended_at=2.0)

        def run(self):
            return Path("/tmp/proxmox-result")

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmoxVmOrchestrator,
    )
    monkeypatch.setattr(
        "controlplane_tool.e2e.two_vm_loadtest_runner.TwoVmLoadtestRunner",
        FakeTwoVmLoadtestRunner,
    )
    monkeypatch.setattr(proxmox_plan, "EnsureVmRunning", FakeEnsureVmRunning)
    monkeypatch.setattr(proxmox_plan, "InstallK6", FakeTask)
    monkeypatch.setattr(proxmox_plan, "RunK6", FakeTask)
    monkeypatch.setattr(proxmox_plan, "FetchVmResults", FakeTask)
    monkeypatch.setattr(proxmox_plan, "CapturePrometheusSnapshot", FakeTask)
    monkeypatch.setattr(proxmox_plan, "WriteK6Report", FakeTask)
    monkeypatch.setattr(proxmox_plan, "DestroyVm", FakeTask)

    # The honest prelude is a single synthetic no-op Task (bypasses real
    # _build_prelude_tasks / SSH resolution). run() must execute it, so the tail
    # events start AFTER it.
    def fake_build_prelude_tasks(self, proxmox_orch, stack_request, *, resolve_host=True):
        return [
            CallableTask(
                task_id="prelude.noop",
                title="Prelude no-op",
                action=lambda: None,
            )
        ]

    monkeypatch.setattr(
        proxmox_plan.ProxmoxVmLoadtestPlan,
        "_build_prelude_tasks",
        fake_build_prelude_tasks,
    )

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
        cleanup_vm=False,
    )
    plan = proxmox_plan.ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )
    events = []

    plan.run(event_listener=events.append)

    tail_running_events = [
        event
        for event in events
        if event.status == "running" and event.step.step_id.startswith(("vm.stack.", "vm.loadgen.", "loadgen.", "metrics.", "loadtest."))
    ]
    assert events
    assert {event.total_steps for event in events} == {len(plan.task_ids)}
    assert tail_running_events
    assert tail_running_events[0].step.step_id == "vm.stack.ensure_running"
    assert tail_running_events[0].step_index == 2


def test_proxmox_vm_loadtest_uses_separate_lifecycle_credentials_for_loadgen(
    monkeypatch,
    tmp_path,
) -> None:
    from pathlib import Path
    from types import SimpleNamespace

    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    adapter_credentials: list[str] = []
    ensure_lifecycles: list[tuple[str, str]] = []

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def teardown(self, request):
            return None

    class FakeLifecycle:
        def __init__(self, credentials):
            self.credentials = credentials

    def fake_proxmox_adapter(orchestrator, *, credentials=None):
        assert credentials is not None
        adapter_credentials.append(credentials.name)
        return FakeLifecycle(credentials)

    class FakeTwoVmLoadtestRunner:
        def __init__(self, repo_root, vm):
            self.repo_root = repo_root
            self.vm = vm

        def _create_run_dir(self):
            return Path("/tmp/proxmox-run")

    class FakeEnsureVmRunning:
        def __init__(self, *, task_id, title, lifecycle, config):
            self.task_id = task_id
            self.title = title
            self.lifecycle = lifecycle
            self.config = config

        def run(self):
            ensure_lifecycles.append((self.config.name, self.lifecycle.credentials.name))
            return SimpleNamespace(host="10.0.0.10", home="/home/ubuntu")

    class FakeTask:
        def __init__(self, *, task_id, title, **kwargs):
            self.task_id = task_id
            self.title = title
            self.result = SimpleNamespace(started_at=1.0, ended_at=2.0)

        def run(self):
            return Path("/tmp/proxmox-result")

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmoxVmOrchestrator,
    )
    monkeypatch.setattr(
        "controlplane_tool.e2e.two_vm_loadtest_runner.TwoVmLoadtestRunner",
        FakeTwoVmLoadtestRunner,
    )
    monkeypatch.setattr(proxmox_plan, "ProxmoxVmAdapter", fake_proxmox_adapter)
    monkeypatch.setattr(proxmox_plan, "EnsureVmRunning", FakeEnsureVmRunning)
    monkeypatch.setattr(proxmox_plan, "InstallK6", FakeTask)
    monkeypatch.setattr(proxmox_plan, "RunK6", FakeTask)
    monkeypatch.setattr(proxmox_plan, "FetchVmResults", FakeTask)
    monkeypatch.setattr(proxmox_plan, "CapturePrometheusSnapshot", FakeTask)
    monkeypatch.setattr(proxmox_plan, "WriteK6Report", FakeTask)
    # This test exercises the tail lifecycle credentials, not the prelude; use an
    # empty honest prelude so run() skips real SSH-resolving prelude building.
    monkeypatch.setattr(
        proxmox_plan.ProxmoxVmLoadtestPlan,
        "_build_prelude_tasks",
        lambda self, proxmox_orch, stack_request, *, resolve_host=True: [],
    )

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(
            lifecycle="proxmox",
            name="proxmox-stack",
            proxmox_ssh_key_path="/tmp/stack_key",
        ),
        loadgen_vm=VmRequest(
            lifecycle="proxmox",
            name="proxmox-loadgen",
            proxmox_ssh_key_path="/tmp/loadgen_key",
        ),
        cleanup_vm=False,
    )
    plan = proxmox_plan.ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )

    plan.run()

    assert adapter_credentials == ["proxmox-stack", "proxmox-loadgen"]
    assert ("proxmox-stack", "proxmox-stack") in ensure_lifecycles
    assert ("proxmox-loadgen", "proxmox-loadgen") in ensure_lifecycles
