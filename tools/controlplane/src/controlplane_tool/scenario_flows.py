from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from controlplane_tool.e2e_runner import E2eRunner
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.prefect_models import LocalFlowDefinition
from controlplane_tool.registry_runtime import default_registry_url
from controlplane_tool.scenario_components.environment import default_managed_vm_request
from controlplane_tool.scenario_components.composer import compose_recipe
from controlplane_tool.scenario_components.recipes import build_scenario_recipe


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
    namespace: str = "nanofaas-e2e",
    local_registry: str = "",
    runtime: str = "java",
    skip_cli_build: bool = False,
    release: str = "nanofaas-host-cli-e2e",
    noninteractive: bool = True,
    api_port: int | None = None,
    mgmt_port: int | None = None,
    runtime_adapter: str | None = None,
    control_plane_modules: str = "container-deployment-provider",
    registry_port: int | None = None,
    control_plane_port: int | None = None,
) -> LocalFlowDefinition[object]:
    flow_id = f"e2e.{scenario.replace('-', '_')}"

    if request is not None:
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(request, event_listener=event_listener),
        )

    if scenario == "k3s-junit-curl":
        raise ValueError("scenario 'k3s-junit-curl' requires an executable request")

    if scenario == "container-local":
        from controlplane_tool.container_local_runner import ContainerLocalE2eRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: ContainerLocalE2eRunner(
                repo_root,
                api_port=api_port,
                mgmt_port=mgmt_port,
                runtime_adapter=runtime_adapter,
                control_plane_modules=control_plane_modules,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "deploy-host":
        from controlplane_tool.deploy_host_runner import DeployHostE2eRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: DeployHostE2eRunner(
                repo_root,
                registry_port=registry_port,
                control_plane_port=control_plane_port,
            ).run(
                scenario_file=scenario_file,
                skip_cli_build=skip_cli_build,
            ),
        )
    if scenario == "cli":
        from controlplane_tool.cli_vm_runner import CliVmRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: CliVmRunner(
                repo_root,
                namespace=namespace,
                local_registry=local_registry or default_registry_url(),
                runtime=runtime,
                skip_cli_build=skip_cli_build,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "cli-stack":
        from controlplane_tool.cli_stack_runner import CliStackRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: CliStackRunner(
                repo_root,
                namespace=namespace,
                local_registry=local_registry or default_registry_url(),
                runtime=runtime,
                skip_cli_build=skip_cli_build,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "cli-host":
        from controlplane_tool.cli_host_runner import CliHostPlatformRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: CliHostPlatformRunner(
                repo_root,
                namespace=namespace,
                release=release,
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
            namespace=namespace,
            local_registry=local_registry or default_registry_url(),
            cleanup_vm=False,
        )

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(e2e_request, event_listener=event_listener),
        )

    raise ValueError(f"Unsupported scenario flow: {scenario}")
