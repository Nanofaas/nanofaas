from __future__ import annotations

from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation


def _placeholder(operation_id: str, summary: str) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id=operation_id,
            summary=summary,
            argv=("echo", f"{operation_id}: placeholder"),
        ),
    )


def plan_loadgen_ensure_running(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadgen.ensure_running", "Ensure loadgen VM is running")


def plan_loadgen_provision_base(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadgen.provision_base", "Provision loadgen base dependencies")


def plan_loadgen_install_k6(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadgen.install_k6", "Install k6 on loadgen VM")


def plan_loadgen_run_k6(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadgen.run_k6", "Run k6 from loadgen VM")


def plan_metrics_prometheus_snapshot(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("metrics.prometheus_snapshot", "Capture Prometheus query snapshots")


def plan_loadtest_write_report(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadtest.write_report", "Write two-VM loadtest report")


def plan_loadgen_down(_: object) -> tuple[ScenarioOperation, ...]:
    return _placeholder("loadgen.down", "Tear down loadgen VM")


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
