from __future__ import annotations

from dataclasses import replace
from pathlib import Path

from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command
from controlplane_tool.scenario.components.bootstrap import (
    plan_loadtest_install_k6,
    plan_vm_ensure_running,
    plan_vm_provision_base,
)
from controlplane_tool.scenario.components.cleanup import plan_vm_down
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_remote_paths,
    two_vm_target_function,
)


def loadgen_vm_request(context: ScenarioExecutionContext) -> VmRequest:
    explicit_request = getattr(context, "loadgen_vm_request", None)
    if explicit_request is not None:
        return explicit_request

    stack_vm = context.vm_request
    return VmRequest(
        lifecycle=stack_vm.lifecycle,
        name="nanofaas-e2e-loadgen",
        host=stack_vm.host,
        user=stack_vm.user,
        home=stack_vm.home,
        cpus=2,
        memory="2G",
        disk="10G",
    )


def _placeholder(operation_id: str, summary: str) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id=operation_id,
            summary=summary,
            argv=("echo", f"{operation_id}: placeholder"),
        ),
    )


def _loadgen_context(context: ScenarioExecutionContext) -> ScenarioExecutionContext:
    return replace(context, vm_request=loadgen_vm_request(context))


def _remote_home(request: VmRequest) -> str:
    if request.home:
        return request.home
    if request.user == "root":
        return "/root"
    return f"/home/{request.user}"


def _retag_remote_operation(
    operation: RemoteCommandOperation,
    *,
    operation_id: str,
    summary: str,
) -> RemoteCommandOperation:
    return replace(operation, operation_id=operation_id, summary=summary)


def _without_helm_install(operation: RemoteCommandOperation) -> RemoteCommandOperation:
    return replace(
        operation,
        argv=tuple(
            "install_helm=false" if part == "install_helm=true" else part
            for part in operation.argv
        ),
    )


def plan_loadgen_ensure_running(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return tuple(
        _retag_remote_operation(
            operation,
            operation_id="loadgen.ensure_running",
            summary="Ensure loadgen VM is running",
        )
        for operation in plan_vm_ensure_running(_loadgen_context(context))
    )


def plan_loadgen_provision_base(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return tuple(
        _without_helm_install(
            _retag_remote_operation(
                operation,
                operation_id="loadgen.provision_base",
                summary="Provision loadgen base dependencies",
            )
        )
        for operation in plan_vm_provision_base(_loadgen_context(context))
    )


def plan_loadgen_install_k6(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return tuple(
        _retag_remote_operation(
            operation,
            operation_id="loadgen.install_k6",
            summary="Install k6 on loadgen VM",
        )
        for operation in plan_loadtest_install_k6(_loadgen_context(context))
    )


def plan_loadgen_run_k6(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    request = context.request
    remote_home = _remote_home(loadgen_vm_request(context))
    remote_paths = two_vm_remote_paths(
        remote_home,
        payload_name=request.k6_payload.name if request.k6_payload is not None else None,
    )
    return (
        RemoteCommandOperation(
            operation_id="loadgen.run_k6",
            summary="Run k6 from loadgen VM",
            argv=build_k6_command(
                RemoteK6RunConfig(
                    script_path=Path(remote_paths.script_path),
                    summary_path=Path(remote_paths.summary_path),
                    control_plane_url=two_vm_control_plane_url(context.vm_request),
                    function_name=two_vm_target_function(request),
                    payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path is not None else None,
                    stages=two_vm_load_stages(request),
                    custom_script=request.k6_script is not None,
                    vus=request.k6_vus,
                    duration=request.k6_duration,
                )
            ),
        ),
    )


def plan_metrics_prometheus_snapshot(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("metrics.prometheus_snapshot", "Capture Prometheus query snapshots")


def plan_loadtest_write_report(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadtest.write_report", "Write two-VM loadtest report")


def plan_loadgen_down(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return tuple(
        _retag_remote_operation(
            operation,
            operation_id="loadgen.down",
            summary="Tear down loadgen VM",
        )
        for operation in plan_vm_down(_loadgen_context(context))
    )


LOADGEN_ENSURE_RUNNING = ScenarioComponentDefinition(
    component_id="loadgen.ensure_running",
    summary="Ensure loadgen VM is running",
    planner=plan_loadgen_ensure_running,
)

LOADGEN_PROVISION_BASE = ScenarioComponentDefinition(
    component_id="loadgen.provision_base",
    summary="Provision loadgen base dependencies",
    planner=plan_loadgen_provision_base,
)

LOADGEN_INSTALL_K6 = ScenarioComponentDefinition(
    component_id="loadgen.install_k6",
    summary="Install k6 on loadgen VM",
    planner=plan_loadgen_install_k6,
)

LOADGEN_RUN_K6 = ScenarioComponentDefinition(
    component_id="loadgen.run_k6",
    summary="Run k6 from loadgen VM",
    planner=plan_loadgen_run_k6,
)

METRICS_PROMETHEUS_SNAPSHOT = ScenarioComponentDefinition(
    component_id="metrics.prometheus_snapshot",
    summary="Capture Prometheus query snapshots",
    planner=plan_metrics_prometheus_snapshot,
)

LOADTEST_WRITE_REPORT = ScenarioComponentDefinition(
    component_id="loadtest.write_report",
    summary="Write two-VM loadtest report",
    planner=plan_loadtest_write_report,
)

LOADGEN_DOWN = ScenarioComponentDefinition(
    component_id="loadgen.down",
    summary="Tear down loadgen VM",
    planner=plan_loadgen_down,
)
