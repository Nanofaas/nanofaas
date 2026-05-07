from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.orchestation.prefect_models import LocalFlowDefinition
from controlplane_tool.infra.runtimes import default_registry_url
from controlplane_tool.scenario.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
)
from controlplane_tool.scenario.components import default_managed_vm_request
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenario_helpers import resolve_scenario as _resolve_scenario

ContainerLocalE2eRunner = None
DeployHostE2eRunner = None


def scenario_task_ids(scenario: str) -> list[str]:
    if scenario in {"container-local", "deploy-host", "cli", "cli-host"}:
        return [f"tests.run_{scenario.replace('-', '_')}"]
    recipe = build_scenario_recipe(scenario)
    return [component.component_id for component in compose_recipe(recipe)]


def build_scenario_flow(
    scenario: str,
    *,
    repo_root: Path,
    request=None,
    event_listener: Callable[[object], None] | None = None,
    scenario_file: Path | None = None,
    namespace: str | None = None,
    local_registry: str = "",
    runtime: str = "java",
    skip_cli_build: bool = False,
    release: str | None = None,
    noninteractive: bool = True,
    api_port: int | None = None,
    mgmt_port: int | None = None,
    runtime_adapter: str | None = None,
    control_plane_modules: str = "container-deployment-provider",
    registry_port: int | None = None,
    control_plane_port: int | None = None,
) -> LocalFlowDefinition[object]:
    flow_id = f"e2e.{scenario.replace('-', '_')}"
    effective_namespace = resolve_scenario_namespace(
        scenario,
        explicit_namespace=namespace,
        resolved_scenario_namespace=None,
    )
    effective_release = resolve_scenario_release(
        scenario,
        explicit_release=release,
    )

    if scenario == "container-local":
        runner_cls = ContainerLocalE2eRunner
        if runner_cls is None:
            from controlplane_tool.e2e.container_local_runner import (
                ContainerLocalE2eRunner as runner_cls,
            )

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: runner_cls(
                repo_root,
                api_port=api_port,
                mgmt_port=mgmt_port,
                runtime_adapter=runtime_adapter,
                control_plane_modules=control_plane_modules,
            ).run(
                scenario_file=scenario_file,
                resolved_scenario=getattr(request, "resolved_scenario", None),
            ),
        )
    if scenario == "deploy-host":
        runner_cls = DeployHostE2eRunner
        if runner_cls is None:
            from controlplane_tool.e2e.deploy_host_runner import DeployHostE2eRunner as runner_cls

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: runner_cls(
                repo_root,
                registry_port=registry_port,
                control_plane_port=control_plane_port,
            ).run(
                scenario_file=scenario_file,
                resolved_scenario=getattr(request, "resolved_scenario", None),
                skip_cli_build=skip_cli_build,
            ),
        )

    if request is not None:
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(request, event_listener=event_listener),
        )

    if scenario == "k3s-junit-curl":
        raise ValueError("scenario 'k3s-junit-curl' requires an executable request")
    if scenario == "cli-stack":
        resolved_scenario = _resolve_scenario(scenario_file)
        effective_scenario = (
            resolved_scenario.model_copy(update={"base_scenario": "cli-stack"})
            if resolved_scenario is not None
            else None
        )
        cli_stack_namespace = resolve_scenario_namespace(
            "cli-stack",
            explicit_namespace=namespace,
            resolved_scenario_namespace=(
                effective_scenario.namespace if effective_scenario is not None else None
            ),
        )
        e2e_request = E2eRequest(
            scenario="cli-stack",
            runtime=runtime,
            scenario_file=scenario_file,
            resolved_scenario=effective_scenario,
            vm=default_managed_vm_request(),
            namespace=cli_stack_namespace,
            local_registry=local_registry or default_registry_url(),
            cleanup_vm=False,
        )
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(e2e_request, event_listener=event_listener),
        )
    if scenario == "cli-host":
        from controlplane_tool.cli_validation.cli_host_runner import CliHostPlatformRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: CliHostPlatformRunner(
                repo_root,
                namespace=effective_namespace,
                release=effective_release,
                local_registry=local_registry or default_registry_url(),
                runtime=runtime,
                skip_cli_build=skip_cli_build,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "helm-stack":
        e2e_request = E2eRequest(
            scenario="helm-stack",
            runtime=runtime,
            vm=default_managed_vm_request(),
            helm_noninteractive=noninteractive,
            namespace=effective_namespace,
            local_registry=local_registry or default_registry_url(),
            cleanup_vm=False,
        )

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(e2e_request, event_listener=event_listener),
        )

    raise ValueError(f"Unsupported scenario flow: {scenario}")
