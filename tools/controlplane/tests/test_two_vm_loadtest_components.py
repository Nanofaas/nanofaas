from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
import controlplane_tool.scenario.components.two_vm_loadtest as two_vm_loadtest


def test_resolved_two_vm_context_exposes_explicit_loadgen_vm_request() -> None:
    stack_vm = VmRequest(lifecycle="multipass", name="stack-vm")
    loadgen_vm = VmRequest(
        lifecycle="multipass",
        name="loadgen-vm",
        cpus=3,
        memory="4G",
        disk="20G",
    )

    context = resolve_scenario_environment(
        Path("/repo"),
        E2eRequest(
            scenario="two-vm-loadtest",
            vm=stack_vm,
            loadgen_vm=loadgen_vm,
        ),
    )

    assert context.loadgen_vm_request == loadgen_vm
    assert two_vm_loadtest.loadgen_vm_request(context) == loadgen_vm


def test_loadgen_vm_request_defaults_from_stack_vm_when_context_has_no_explicit_loadgen() -> None:
    stack_vm = VmRequest(
        lifecycle="external",
        host="stack.example",
        user="dev",
        home="/srv/dev",
        cpus=8,
        memory="16G",
        disk="40G",
    )
    context = resolve_scenario_environment(
        Path("/repo"),
        E2eRequest(scenario="two-vm-loadtest", vm=stack_vm),
    )

    loadgen_vm = two_vm_loadtest.loadgen_vm_request(context)

    assert loadgen_vm == VmRequest(
        lifecycle="external",
        name="nanofaas-e2e-loadgen",
        host="stack.example",
        user="dev",
        home="/srv/dev",
        cpus=2,
        memory="2G",
        disk="10G",
    )
