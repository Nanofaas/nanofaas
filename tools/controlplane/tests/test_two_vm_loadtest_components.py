from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.components.operations import RemoteCommandOperation
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
import controlplane_tool.scenario.components.two_vm_loadtest as two_vm_loadtest


def _context(
    *,
    stack_vm: VmRequest | None = None,
    loadgen_vm: VmRequest | None = None,
):
    return resolve_scenario_environment(
        Path("/repo"),
        E2eRequest(
            scenario="two-vm-loadtest",
            vm=stack_vm or VmRequest(lifecycle="multipass", name="stack-vm"),
            loadgen_vm=loadgen_vm,
        ),
    )


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


def test_plan_loadgen_ensure_running_uses_loadgen_vm_request() -> None:
    context = _context(
        loadgen_vm=VmRequest(
            lifecycle="multipass",
            name="loadgen-vm",
            cpus=3,
            memory="4G",
            disk="20G",
        ),
    )

    operations = two_vm_loadtest.plan_loadgen_ensure_running(context)

    assert len(operations) == 1
    operation = operations[0]
    assert isinstance(operation, RemoteCommandOperation)
    assert operation.operation_id == "loadgen.ensure_running"
    assert operation.summary == "Ensure loadgen VM is running"
    assert operation.argv == (
        "multipass",
        "launch",
        "--name",
        "loadgen-vm",
        "--cpus",
        "3",
        "--memory",
        "4G",
        "--disk",
        "20G",
    )


def test_plan_loadgen_provision_base_uses_loadgen_inventory_without_helm() -> None:
    context = _context(loadgen_vm=VmRequest(lifecycle="multipass", name="loadgen-vm"))

    operations = two_vm_loadtest.plan_loadgen_provision_base(context)

    assert len(operations) == 1
    operation = operations[0]
    assert isinstance(operation, RemoteCommandOperation)
    assert operation.operation_id == "loadgen.provision_base"
    assert operation.summary == "Provision loadgen base dependencies"
    assert "provision-base.yml" in operation.argv[-1]
    assert "<multipass-ip:loadgen-vm>," in operation.argv
    assert "install_helm=false" in operation.argv
    assert "install_helm=true" not in operation.argv


def test_plan_loadgen_install_k6_uses_loadgen_inventory() -> None:
    context = _context(loadgen_vm=VmRequest(lifecycle="multipass", name="loadgen-vm"))

    operations = two_vm_loadtest.plan_loadgen_install_k6(context)

    assert len(operations) == 1
    operation = operations[0]
    assert isinstance(operation, RemoteCommandOperation)
    assert operation.operation_id == "loadgen.install_k6"
    assert operation.summary == "Install k6 on loadgen VM"
    assert "install-k6.yml" in operation.argv[-1]
    assert "<multipass-ip:loadgen-vm>," in operation.argv


def test_external_loadgen_planners_use_loadgen_host_not_stack_host() -> None:
    context = _context(
        stack_vm=VmRequest(lifecycle="external", host="stack.example", user="stack-user"),
        loadgen_vm=VmRequest(lifecycle="external", host="loadgen.example", user="load-user"),
    )

    ensure_operation = two_vm_loadtest.plan_loadgen_ensure_running(context)[0]
    provision_operation = two_vm_loadtest.plan_loadgen_provision_base(context)[0]
    install_operation = two_vm_loadtest.plan_loadgen_install_k6(context)[0]

    assert isinstance(ensure_operation, RemoteCommandOperation)
    assert ensure_operation.argv == ("ssh", "load-user@loadgen.example", "true")
    assert "loadgen.example," in provision_operation.argv
    assert "-u" in provision_operation.argv
    assert provision_operation.argv[provision_operation.argv.index("-u") + 1] == "load-user"
    assert "stack.example," not in provision_operation.argv
    assert "loadgen.example," in install_operation.argv
    assert "stack.example," not in install_operation.argv
