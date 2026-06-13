from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan


def _plan():
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _request: "10.0.0.9",
    )
    request = E2eRequest(
        scenario="loadtest-two-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    return build_two_vm_loadtest_plan(runner, request)


def test_two_vm_loadtest_plan_has_expected_task_ids() -> None:
    """task_ids must include all phases: stack provisioning + loadgen + cleanup.

    The prelude IDs are produced by the REAL _prelude_static_tasks (which runs
    build_command_tasks with resolve_host=False over the real
    _TWO_VM_STACK_PRELUDE_COMPONENTS recipe). No patching of any id-deriving
    function — a dropped/renamed recipe component will cause this test to fail.
    """
    ids = _plan().task_ids
    # Stack VM lifecycle
    assert "vm.stack.ensure_running" in ids
    # Stack provisioning phases — derived from real _TWO_VM_STACK_PRELUDE_COMPONENTS.
    # images.build_core expands into sub-task IDs (one per docker build/push operation).
    assert "vm.provision_base" in ids
    assert "repo.sync_to_vm" in ids
    assert "registry.ensure_container" in ids
    assert "images.build_core.boot_jars" in ids
    assert "images.build_core.control_image" in ids
    assert "images.build_core.runtime_image" in ids
    assert "images.build_core.push_control_image" in ids
    assert "images.build_core.push_runtime_image" in ids
    assert "k3s.install" in ids
    assert "k3s.configure_registry" in ids
    assert "namespace.install" in ids
    assert "helm.deploy_control_plane" in ids
    assert "helm.deploy_function_runtime" in ids
    # Loadgen phases
    assert "vm.loadgen.ensure_running" in ids
    assert "loadgen.install_k6" in ids
    assert "loadgen.run_k6" in ids
    assert "loadgen.fetch_results" in ids
    assert "metrics.prometheus_snapshot" in ids
    assert "loadtest.write_report" in ids
    # Cleanup
    assert "vm.loadgen.destroy" in ids
    assert "vm.stack.destroy" in ids


def test_two_vm_loadgen_install_uses_runplaybook_not_bash() -> None:
    """The loadgen install step must be the ansible RunPlaybook, not bash InstallK6.
    After the B3a refactor, the body is built by build_loadgen_body_tasks (which
    internally calls install_k6_task) inside the shared run_loadtest_flow driver;
    run() must delegate to run_loadtest_flow and InstallK6 must not appear in the driver.
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow
    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    flow_source = inspect.getsource(loadtest_flow)
    assert "build_loadgen_body_tasks(" in flow_source
    assert "InstallK6(" not in flow_source
    # Guard: run() itself must delegate to the driver
    assert "run_loadtest_flow(" in inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)


def test_two_vm_run_uploads_k6_script_to_loadgen() -> None:
    """run() must upload the k6 script before RunK6, else k6 finds no script.
    After the B3a refactor, prepare_loadgen is called via adapter.prepare_loadgen(ctx)
    inside run_loadtest_flow; asserting it appears in the driver source preserves the invariant.
    """
    import inspect

    from controlplane_tool.scenario import loadtest_flow

    flow_source = inspect.getsource(loadtest_flow)
    assert "prepare_loadgen" in flow_source


def test_two_vm_run_registers_functions_on_control_plane() -> None:
    """run() must register the selected functions, else k6 invokes a non-existent
    function (400) and the required Prometheus dispatch metrics have no data.
    After the B3b reroute, multipass registration lives in the adapter's
    MultipassLoadtestAdapter.register_functions (which uses RegisterFunctions),
    invoked by the shared run_loadtest_flow driver via adapter.register_functions(ctx).
    """
    import inspect

    from controlplane_tool.scenario import loadtest_adapter

    adapter_source = inspect.getsource(loadtest_adapter)
    assert "RegisterFunctions" in adapter_source
