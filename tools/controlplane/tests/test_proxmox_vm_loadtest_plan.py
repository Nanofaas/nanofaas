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
    from workflow_tasks.shell import RecordingShell
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
    from workflow_tasks.shell import RecordingShell
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
        # B3b: canonical execution-order emission via run_loadtest_flow names the
        # stack-ensure step "vm.stack.ensure_running" (matching two-vm), not the
        # legacy bespoke "vm.ensure_running". The load-bearing invariant —
        # functions.register < vm.stack.publish_ports < loadgen.install_k6 — holds.
        "vm.stack.ensure_running",
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
    from workflow_tasks.shell import RecordingShell
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
    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.loadtest_flow import RunContext, _destroy_tasks
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
    # B3b: cleanup is driven by the unified driver's _destroy_tasks, which returns
    # no DestroyVm tasks when cleanup_vm is False.
    assert _destroy_tasks(plan._adapter(), RunContext(), request) == []


def test_e2e_runner_plan_returns_proxmox_vm_loadtest_plan(tmp_path) -> None:
    from pathlib import Path
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan
    from workflow_tasks.shell import RecordingShell
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

    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario import loadtest_flow
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

        # B3b: the driver resolves proxmox connectivity (resolve_host=True) before
        # building the prelude, so the orch must answer the SSH-endpoint queries.
        def remote_project_dir(self, request):
            return f"/home/{request.user or 'ubuntu'}/nanofaas"

        def ssh_endpoint(self, request):
            return "10.0.0.10", 2222

        def ssh_private_key_path(self, request):
            return None

        def teardown(self, request):
            torn_down.append(request.name)

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmoxVmOrchestrator,
    )

    # B3b: run() routes through run_loadtest_flow, which builds the prelude via the
    # shared driver (loadtest_flow._build_prelude_tasks). Make the prelude FAIL via
    # a synthetic honest Task; the unified driver's emitting failure-cleanup path
    # must trigger adapter.cleanup_on_failure -> proxmox teardown of both VMs.
    def fake_build_prelude_tasks(runner, request, setup, recipe, connectivity,
                                 special_handler=None, context_selector=None):
        return [
            CallableTask(
                task_id="prelude.fail",
                title="Fail prelude",
                action=lambda: (_ for _ in ()).throw(RuntimeError("prelude exploded")),
            )
        ]

    monkeypatch.setattr(loadtest_flow, "_build_prelude_tasks", fake_build_prelude_tasks)

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

    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario import loadtest_flow
    from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def remote_project_dir(self, request):
            return f"/home/{request.user or 'ubuntu'}/nanofaas"

        def ssh_endpoint(self, request):
            return "127.0.0.1", 2222

        def ssh_private_key_path(self, request):
            return None

        def teardown(self, request):
            return None

    class FakeTwoVmLoadtestRunner:
        def __init__(self, repo_root, vm):
            self.repo_root = repo_root
            self.vm = vm

        def _create_run_dir(self):
            return Path("/tmp/proxmox-run")

        def prepare_loadgen(self, request, remote_paths):
            # Adapter.prepare_loadgen delegates here to upload the k6 script /
            # create the loadgen run dirs; no-op for the plan-level fakes.
            return None

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
    # B3b: run() routes through run_loadtest_flow; patch the driver-owned symbols.
    monkeypatch.setattr(loadtest_flow, "EnsureVmRunning", FakeEnsureVmRunning)
    monkeypatch.setattr(loadtest_flow, "DestroyVm", FakeTask)

    def fake_build_loadgen_body_tasks(inputs):
        return [FakeTask(task_id=tid, title=title) for tid, title in zip(inputs.task_ids, inputs.titles)]

    monkeypatch.setattr("workflow_tasks.build_loadgen_body_tasks", fake_build_loadgen_body_tasks)

    # The prelude is a single synthetic no-op Task (bypasses real prelude / SSH
    # resolution). run() must execute it, so the tail events start AFTER it.
    def fake_build_prelude_tasks(runner, request, setup, recipe, connectivity,
                                 special_handler=None, context_selector=None):
        return [
            CallableTask(
                task_id="prelude.noop",
                title="Prelude no-op",
                action=lambda: None,
            )
        ]

    monkeypatch.setattr(loadtest_flow, "_build_prelude_tasks", fake_build_prelude_tasks)

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
    assert tail_running_events[0].step_index == 1


def test_proxmox_vm_loadtest_uses_separate_lifecycle_credentials_for_loadgen(
    monkeypatch,
    tmp_path,
) -> None:
    from pathlib import Path
    from types import SimpleNamespace

    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario import loadtest_flow
    import controlplane_tool.infra.vm_lifecycle_adapters as vm_lifecycle_adapters
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    adapter_credentials: list[str] = []
    ensure_lifecycles: list[tuple[str, str]] = []

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def remote_project_dir(self, request):
            return f"/home/{request.user or 'ubuntu'}/nanofaas"

        def ssh_endpoint(self, request):
            return "127.0.0.1", 2222

        def ssh_private_key_path(self, request):
            return None

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

        def prepare_loadgen(self, request, remote_paths):
            # Adapter.prepare_loadgen delegates here to upload the k6 script /
            # create the loadgen run dirs; no-op for the plan-level fakes.
            return None

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
    # B3b: lifecycles + ensure tasks are now built inside the shared driver
    # (loadtest_flow) and the proxmox adapter (which imports ProxmoxVmAdapter from
    # controlplane_tool.infra.vm_lifecycle_adapters). Patch them where the driver
    # and adapter resolve them.
    monkeypatch.setattr(vm_lifecycle_adapters, "ProxmoxVmAdapter", fake_proxmox_adapter)
    monkeypatch.setattr(loadtest_flow, "EnsureVmRunning", FakeEnsureVmRunning)

    def fake_build_loadgen_body_tasks(inputs):
        return [FakeTask(task_id=tid, title=title) for tid, title in zip(inputs.task_ids, inputs.titles)]

    monkeypatch.setattr("workflow_tasks.build_loadgen_body_tasks", fake_build_loadgen_body_tasks)
    # This test exercises the lifecycle credentials, not the prelude; use an empty
    # prelude so run() skips real SSH-resolving prelude building.
    monkeypatch.setattr(
        loadtest_flow,
        "_build_prelude_tasks",
        lambda runner, request, setup, recipe, connectivity, special_handler=None, context_selector=None: [],
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


def test_proxmox_event_sequence_is_pinned(monkeypatch, tmp_path) -> None:
    """Characterization test: pins the EXACT ScenarioStepEvent sequence emitted by
    ProxmoxVmLoadtestPlan.run(event_listener=...) BEFORE any refactor.

    The expected sequence was captured from the unchanged production code and must
    NOT be adjusted to match a refactored version — it IS the contract.
    """
    from pathlib import Path
    from types import SimpleNamespace

    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario import loadtest_flow
    from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def remote_project_dir(self, request):
            return f"/home/{request.user or 'ubuntu'}/nanofaas"

        def ssh_endpoint(self, request):
            return "127.0.0.1", 2222

        def ssh_private_key_path(self, request):
            return None

        def teardown(self, request):
            return None

    class FakeTwoVmLoadtestRunner:
        def __init__(self, repo_root, vm):
            self.repo_root = repo_root
            self.vm = vm

        def _create_run_dir(self):
            return Path("/tmp/proxmox-run")

        def prepare_loadgen(self, request, remote_paths):
            # Adapter.prepare_loadgen delegates here to upload the k6 script /
            # create the loadgen run dirs; no-op for the plan-level fakes.
            return None

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
    # B3b: run() routes through run_loadtest_flow; patch the driver-owned symbols.
    monkeypatch.setattr(loadtest_flow, "EnsureVmRunning", FakeEnsureVmRunning)
    monkeypatch.setattr(loadtest_flow, "DestroyVm", FakeTask)

    def fake_build_loadgen_body_tasks(inputs):
        return [FakeTask(task_id=tid, title=title) for tid, title in zip(inputs.task_ids, inputs.titles)]

    monkeypatch.setattr("workflow_tasks.build_loadgen_body_tasks", fake_build_loadgen_body_tasks)

    def fake_build_prelude_tasks(runner, request, setup, recipe, connectivity,
                                 special_handler=None, context_selector=None):
        return [
            CallableTask(
                task_id="prelude.noop",
                title="Prelude no-op",
                action=lambda: None,
            )
        ]

    monkeypatch.setattr(loadtest_flow, "_build_prelude_tasks", fake_build_prelude_tasks)

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

    seq = [(e.step_index, e.step.step_id, e.status) for e in events]
    assert {e.total_steps for e in events} == {len(plan.task_ids)}
    # B3b: canonical execution-order emission produced by run_loadtest_flow's
    # emitting path (ensure-stack -> prelude -> ensure-loadgen -> publish-ports ->
    # loadgen body), with a single ScenarioStepEvent pair per task in execution
    # order (no separate prelude-then-tail re-emission). The stack VM is ensured
    # before the prelude because building the prelude resolves the proxmox SSH
    # endpoint (get_vm), which requires the VM to exist. See the B3b plan:
    # docs/superpowers/plans/*loadtest-scenario-unification* (Task 6).
    assert seq == [
        (1, "vm.stack.ensure_running", "running"),
        (1, "vm.stack.ensure_running", "success"),
        (2, "prelude.noop", "running"),
        (2, "prelude.noop", "success"),
        (3, "vm.loadgen.ensure_running", "running"),
        (3, "vm.loadgen.ensure_running", "success"),
        (4, "vm.stack.publish_ports", "running"),
        (4, "vm.stack.publish_ports", "success"),
        (5, "loadgen.install_k6", "running"),
        (5, "loadgen.install_k6", "success"),
        (6, "loadgen.run_k6", "running"),
        (6, "loadgen.run_k6", "success"),
        (7, "loadgen.fetch_results", "running"),
        (7, "loadgen.fetch_results", "success"),
        (8, "metrics.prometheus_snapshot", "running"),
        (8, "metrics.prometheus_snapshot", "success"),
        (9, "loadtest.write_report", "running"),
        (9, "loadtest.write_report", "success"),
    ]


def test_proxmox_tail_failure_tears_down_vms(monkeypatch, tmp_path) -> None:
    """Characterization test: pins teardown order + raised error when a TAIL task fails.

    When ``cleanup_vm=True`` and a tail task raises, ``_run_tail_tasks`` runs
    the cleanup_tasks (DestroyVm action wrappers) which call
    ``proxmox_orch.teardown`` in loadgen-first, stack-second order.  The
    original exception is re-raised unwrapped (no message formatting added by
    the tail path).
    """
    from pathlib import Path
    from types import SimpleNamespace

    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario import loadtest_flow
    from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
    import controlplane_tool.scenario.scenarios.proxmox_vm_loadtest as proxmox_plan

    destroy_calls: list[str] = []

    class FakeProxmoxVmOrchestrator:
        def __init__(self, repo_root):
            self.repo_root = repo_root

        def publish_port(self, request, *, service, guest_port):
            return "127.0.0.1", 30090

        def remote_project_dir(self, request):
            return f"/home/{request.user or 'ubuntu'}/nanofaas"

        def ssh_endpoint(self, request):
            return "127.0.0.1", 2222

        def ssh_private_key_path(self, request):
            return None

        def teardown(self, request):
            # Called only in the else-branch of _destroy_loadgen/_destroy_stack
            # (when loadgen_info / stack_info is NOT yet in state).
            destroy_calls.append(("teardown", request.name))

    class FakeTwoVmLoadtestRunner:
        def __init__(self, repo_root, vm):
            self.repo_root = repo_root
            self.vm = vm

        def _create_run_dir(self):
            return Path("/tmp/proxmox-run")

        def prepare_loadgen(self, request, remote_paths):
            # Adapter.prepare_loadgen delegates here to upload the k6 script /
            # create the loadgen run dirs; no-op for the plan-level fakes.
            return None

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

    class FakeDestroyVm:
        """DestroyVm replacement that records which VM was destroyed."""

        def __init__(self, *, task_id, title, lifecycle=None, info=None, **kwargs):
            self.task_id = task_id
            self.title = title

        def run(self):
            # task_id is e.g. "vm.loadgen.destroy" or "vm.stack.destroy"
            destroy_calls.append(self.task_id)

    class FailingFakeTask:
        """A FakeTask whose .run() raises RuntimeError("boom")."""

        def __init__(self, *, task_id, title, **kwargs):
            self.task_id = task_id
            self.title = title
            self.result = SimpleNamespace(started_at=1.0, ended_at=2.0)

        def run(self):
            raise RuntimeError("boom")

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmoxVmOrchestrator,
    )
    monkeypatch.setattr(
        "controlplane_tool.e2e.two_vm_loadtest_runner.TwoVmLoadtestRunner",
        FakeTwoVmLoadtestRunner,
    )
    # B3b: run() routes through run_loadtest_flow; patch the driver-owned symbols.
    monkeypatch.setattr(loadtest_flow, "EnsureVmRunning", FakeEnsureVmRunning)
    monkeypatch.setattr(loadtest_flow, "DestroyVm", FakeDestroyVm)

    # Make the loadgen.run_k6 task raise; all others are silent no-ops.
    def fake_build_loadgen_body_tasks(inputs):
        tasks = []
        for tid, title in zip(inputs.task_ids, inputs.titles):
            if tid == "loadgen.run_k6":
                tasks.append(FailingFakeTask(task_id=tid, title=title))
            else:
                tasks.append(FakeTask(task_id=tid, title=title))
        return tasks

    monkeypatch.setattr("workflow_tasks.build_loadgen_body_tasks", fake_build_loadgen_body_tasks)

    # Silent no-op prelude (bypass real SSH resolution).
    monkeypatch.setattr(
        loadtest_flow,
        "_build_prelude_tasks",
        lambda runner, request, setup, recipe, connectivity, special_handler=None, context_selector=None: [
            CallableTask(task_id="prelude.noop", title="Prelude no-op", action=lambda: None)
        ],
    )

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
        cleanup_vm=True,
    )
    plan = proxmox_plan.ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )

    with pytest.raises(RuntimeError) as exc_info:
        plan.run(event_listener=lambda e: None)

    # The tail path re-raises the original exception without any wrapping.
    assert str(exc_info.value) == "boom"
    # cleanup_tasks ran DestroyVm in loadgen-first, stack-second order (pinned).
    # Both VMs have state entries (ensure tasks ran before run_k6 failed), so
    # _destroy_loadgen/_destroy_stack take the DestroyVm branch, not teardown().
    assert destroy_calls == ["vm.loadgen.destroy", "vm.stack.destroy"]


def test_proxmox_loadgen_install_uses_runplaybook_not_bash() -> None:
    """Verify the loadgen body is built via the shared builder (which uses install_k6_task /
    ansible-based install internally), not by constructing InstallK6 directly.

    B3b: proxmox now routes run() through run_loadtest_flow, so the loadgen body is
    built by the shared driver (loadtest_flow._build_loadgen_body). Asserting against
    the driver source preserves the invariant (build_loadgen_body_tasks present,
    InstallK6 construction absent)."""
    import inspect

    from controlplane_tool.scenario import loadtest_flow

    source = inspect.getsource(loadtest_flow._build_loadgen_body)
    assert "build_loadgen_body_tasks(" in source
    assert "InstallK6(" not in source
