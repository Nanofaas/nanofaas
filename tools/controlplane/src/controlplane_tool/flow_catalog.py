from __future__ import annotations

from typing import Any

from controlplane_tool.infra_flows import (
    build_gradle_action_flow,
    build_pipeline_flow,
    build_vm_flow,
    gradle_action_task_ids,
    pipeline_task_ids,
    vm_flow_task_ids,
)
from controlplane_tool.loadtest_flows import build_loadtest_flow
from controlplane_tool.prefect_models import LocalFlowDefinition
from controlplane_tool.scenario_flows import build_scenario_flow, scenario_task_ids


def _unique_task_ids(task_ids: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for task_id in task_ids:
        if task_id in seen:
            continue
        seen.add(task_id)
        ordered.append(task_id)
    return ordered


def resolve_flow_task_ids(flow_name: str, **kwargs: Any) -> list[str]:
    if flow_name.startswith("build."):
        return gradle_action_task_ids(flow_name.removeprefix("build."))
    if flow_name.startswith("vm."):
        return vm_flow_task_ids(flow_name)
    if flow_name == "infra.pipeline":
        profile = kwargs.get("profile")
        if profile is None:
            raise ValueError("profile is required to resolve infra.pipeline task ids")
        return pipeline_task_ids(profile)
    if flow_name.startswith("e2e."):
        if flow_name == "e2e.all":
            scenarios = list(kwargs.get("scenarios") or [])
            task_ids: list[str] = []
            for scenario in scenarios:
                task_ids.extend(scenario_task_ids(scenario))
            return _unique_task_ids(task_ids)
        return scenario_task_ids(flow_name.removeprefix("e2e."))
    if flow_name.startswith("loadtest."):
        return [
            "loadtest.bootstrap",
            "loadtest.execute_k6",
            "metrics.evaluate_gate",
            "loadtest.write_report",
        ]
    raise ValueError(f"Unsupported flow name: {flow_name}")


def resolve_flow_definition(flow_name: str, **kwargs: Any) -> LocalFlowDefinition[Any]:
    if flow_name.startswith("build."):
        action = flow_name.removeprefix("build.")
        return build_gradle_action_flow(
            action=action,
            profile=kwargs["profile"],
            modules=kwargs.get("modules"),
            extra_gradle_args=kwargs.get("extra_gradle_args", []),
            dry_run=kwargs.get("dry_run", False),
            executor=kwargs.get("executor"),
        )
    if flow_name.startswith("vm."):
        return build_vm_flow(
            flow_name,
            request=kwargs["request"],
            repo_root=kwargs["repo_root"],
            dry_run=kwargs.get("dry_run", False),
            remote_dir=kwargs.get("remote_dir"),
            install_helm=kwargs.get("install_helm", False),
            helm_version=kwargs.get("helm_version", "3.16.4"),
            kubeconfig_path=kwargs.get("kubeconfig_path"),
            k3s_version=kwargs.get("k3s_version"),
            registry=kwargs.get("registry", "localhost:5000"),
            container_name=kwargs.get("container_name", "nanofaas-e2e-registry"),
            orchestrator=kwargs.get("orchestrator"),
        )
    if flow_name == "infra.pipeline":
        return build_pipeline_flow(
            kwargs["profile"],
            adapter=kwargs.get("adapter"),
            runs_root=kwargs.get("runs_root"),
        )
    if flow_name.startswith("e2e."):
        if flow_name == "e2e.all":
            runner = kwargs["runner"]
            only = kwargs.get("only")
            skip = kwargs.get("skip")
            runtime = kwargs.get("runtime", "java")
            vm_request = kwargs.get("vm_request")
            keep_vm = kwargs.get("keep_vm", False)
            namespace = kwargs.get("namespace")
            local_registry = kwargs.get("local_registry", "localhost:5000")
            scenarios = kwargs.get("scenarios") or []
            return LocalFlowDefinition(
                flow_id="e2e.all",
                task_ids=resolve_flow_task_ids("e2e.all", scenarios=scenarios),
                run=lambda: runner.run_all(
                    only=only,
                    skip=skip,
                    runtime=runtime,
                    vm_request=vm_request,
                    keep_vm=keep_vm,
                    namespace=namespace,
                    local_registry=local_registry,
                ),
            )
        return build_scenario_flow(
            flow_name.removeprefix("e2e."),
            repo_root=kwargs["repo_root"],
            request=kwargs.get("request"),
            scenario_file=kwargs.get("scenario_file"),
            namespace=kwargs.get("namespace", "nanofaas-e2e"),
            local_registry=kwargs.get("local_registry", "localhost:5000"),
            runtime=kwargs.get("runtime", "java"),
            skip_cli_build=kwargs.get("skip_cli_build", False),
            release=kwargs.get("release", "nanofaas-host-cli-e2e"),
            noninteractive=kwargs.get("noninteractive", True),
            api_port=kwargs.get("api_port"),
            mgmt_port=kwargs.get("mgmt_port"),
            runtime_adapter=kwargs.get("runtime_adapter"),
            control_plane_modules=kwargs.get("control_plane_modules", "container-deployment-provider"),
            registry_port=kwargs.get("registry_port"),
            control_plane_port=kwargs.get("control_plane_port"),
        )
    if flow_name.startswith("loadtest."):
        if kwargs.get("request") is None:
            raise ValueError(f"{flow_name} requires a loadtest request")
        return build_loadtest_flow(
            flow_name.removeprefix("loadtest."),
            request=kwargs.get("request"),
            adapter=kwargs.get("adapter"),
            runs_root=kwargs.get("runs_root"),
            event_listener=kwargs.get("event_listener"),
        )
    raise ValueError(f"Unsupported flow name: {flow_name}")
