from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e_runner import E2eRunner
from controlplane_tool.prefect_models import LocalFlowDefinition


def build_scenario_flow(
    scenario: str,
    *,
    repo_root: Path,
    request=None,
    scenario_file: Path | None = None,
    namespace: str = "nanofaas-e2e",
    local_registry: str = "localhost:5000",
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

    task_ids_map = {
        "container-local": ["tests.run_container_local"],
        "deploy-host": ["tests.run_deploy_host"],
        "k3s-curl": [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "k3s.install",
            "k3s.configure_registry",
            "helm.deploy_control_plane",
            "helm.deploy_function_runtime",
            "tests.run_k3s_curl",
        ],
        "k8s-vm": [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "k3s.install",
            "k3s.configure_registry",
            "tests.run_k8s_e2e",
        ],
        "cli": [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "k3s.install",
            "k3s.configure_registry",
            "helm.deploy_control_plane",
            "tests.run_cli_vm",
        ],
        "cli-host": [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "images.build_core",
            "k3s.install",
            "k3s.configure_registry",
            "helm.deploy_control_plane",
            "tests.run_cli_host_platform",
        ],
        "helm-stack": [
            "vm.ensure_running",
            "vm.provision_base",
            "repo.sync_to_vm",
            "registry.ensure_container",
            "k3s.install",
            "k3s.configure_registry",
            "loadtest.run",
            "experiments.autoscaling",
        ],
    }

    if request is not None:
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map.get(scenario, [f"tests.run_{scenario.replace('-', '_')}"]),
            run=lambda: E2eRunner(repo_root).run(request),
        )

    if scenario == "k8s-vm":
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map[scenario],
            run=lambda: None,
        )

    if scenario == "container-local":
        from controlplane_tool.container_local_runner import ContainerLocalE2eRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map[scenario],
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
            task_ids=task_ids_map[scenario],
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
            task_ids=task_ids_map[scenario],
            run=lambda: CliVmRunner(
                repo_root,
                namespace=namespace,
                local_registry=local_registry,
                runtime=runtime,
                skip_cli_build=skip_cli_build,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "cli-host":
        from controlplane_tool.cli_host_runner import CliHostPlatformRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map[scenario],
            run=lambda: CliHostPlatformRunner(
                repo_root,
                namespace=namespace,
                release=release,
                local_registry=local_registry,
                runtime=runtime,
                skip_cli_build=skip_cli_build,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "k3s-curl":
        from controlplane_tool.k3s_curl_runner import K3sCurlRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map[scenario],
            run=lambda: K3sCurlRunner(
                repo_root,
                namespace=namespace,
                local_registry=local_registry,
                runtime=runtime,
            ).run(scenario_file=scenario_file),
        )
    if scenario == "helm-stack":
        from controlplane_tool.helm_stack_runner import HelmStackRunner

        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=task_ids_map[scenario],
            run=lambda: HelmStackRunner(
                repo_root,
                namespace=namespace,
                local_registry=local_registry,
                runtime=runtime,
                noninteractive=noninteractive,
            ).run(),
        )

    raise ValueError(f"Unsupported scenario flow: {scenario}")
