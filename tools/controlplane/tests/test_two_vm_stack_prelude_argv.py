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


def test_two_vm_stack_prelude_argv_is_resolved_and_pinned() -> None:
    plan = _plan()
    setup = plan._build_setup()  # noqa: SLF001
    tasks = plan._build_stack_prelude_tasks(setup, resolve_host=True)  # noqa: SLF001

    by_id = {t.task_id: list(t.spec.argv) for t in tasks if getattr(t, "spec", None) is not None}

    # No unresolved multipass placeholders survive in any host command.
    for task_id, argv in by_id.items():
        assert not any("<multipass-ip:" in arg for arg in argv), task_id

    # The provision_base ansible command targets the resolved stack IP.
    provision = by_id["vm.provision_base"]
    assert provision[0] == "ansible-playbook"
    assert "10.0.0.9," in provision
    assert provision[-1].endswith("provision-base.yml")
