from collections.abc import Mapping
from dataclasses import replace
from pathlib import Path
from typing import cast

from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.workflows.loadtest import (
    LoadtestWorkflowRequest,
    build_loadtest_workflow,
    default_prometheus_queries,
)
from workflow_tasks.workflows.validate import (
    ValidateWorkflowRequest,
    k8s_deployment_specs,
    registration_specs,
    validate_cleanup_specs,
)

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans._assembly import workflow_from_specs
from controlplane_tool.plans.validate import _resolve_function


def _home(user: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    return "/root" if user == "root" else f"/home/{user}"


def build_loadtest_plan(
    config: ScenarioConfig,
    environment: EnvironmentConfig,
    bindings: RoleBindings,
    *,
    control_plane_url: str,
    prometheus_client: PrometheusClient,
    run_dir: Path,
    fetcher: RemoteFileFetcher | object | None = None,
    repo_root: Path | None = None,
    stages: tuple[tuple[str, int], ...] | None = None,
    prebuilt_control_plane_image: str | None = None,
    prebuilt_function_images: Mapping[str, str] | None = None,
) -> Workflow:
    if config.workflow != "loadtest":
        raise ValueError("load-test plan requires a loadtest scenario")
    scaling_config: dict[str, object] | None = None
    if config.autoscaling:
        scaling_config = {
            "strategy": "INTERNAL",
            "minReplicas": 0,
            "maxReplicas": 5,
            "metrics": [{"type": "in_flight", "target": "2"}],
        }
    functions = tuple(_resolve_function(config, key) for key in config.functions)
    prebuilt = prebuilt_control_plane_image is not None or prebuilt_function_images is not None
    if prebuilt:
        if prebuilt_function_images is None:
            raise ValueError("prebuilt function images are required in prebuilt mode")
        missing = [
            function.key for function in functions if not prebuilt_function_images.get(function.key)
        ]
        if missing:
            raise ValueError("missing prebuilt function images: " + ", ".join(missing))
        functions = tuple(
            replace(function, image=prebuilt_function_images[function.key])
            for function in functions
        )
    if scaling_config is not None:
        functions = tuple(
            replace(
                function,
                scaling_config=scaling_config,
                timeout_ms=30000,
                concurrency=4,
                queue_size=100,
            )
            for function in functions
        )
    target = functions[0]
    dedicated = "loadgen" in environment.roles
    remote = environment.provider != "local"
    root = repo_root or Path.cwd()
    if remote:
        role_target = environment.target("loadgen" if dedicated else "stack")
        home = _home(role_target.user, role_target.home)
        script_name = "autoscaling.js" if config.autoscaling else "two-vm-function-invoke.js"
        script_path = Path(home) / f"nanofaas/tools/controlplane/assets/k6/{script_name}"
        summary_path = Path(home) / "nanofaas-loadtest/k6-summary.json"
    else:
        script_name = "autoscaling.js" if config.autoscaling else "two-vm-function-invoke.js"
        script_path = root / f"tools/controlplane/assets/k6/{script_name}"
        summary_path = run_dir / "k6-summary.json"
    deployment = ValidateWorkflowRequest(
        backend="k8s",
        build=config.build,
        functions=functions,
        additional_modules=("autoscaler", "async-queue", "sync-queue")
        if config.autoscaling
        else (),
        build_images=not prebuilt,
        control_plane_image=prebuilt_control_plane_image,
    )
    stack = workflow_from_specs(
        k8s_deployment_specs(
            deployment,
            expose_node_ports=True,
            metrics_profile="advanced",
        )
        + registration_specs(deployment),
        bindings,
        cwd=root,
    )
    load = build_loadtest_workflow(
        LoadtestWorkflowRequest(
            control_plane_url=control_plane_url,
            function_name=target.name,
            script_path=script_path,
            summary_path=summary_path,
            run_dir=run_dir,
            stages=stages or (
                (("10s", 10), ("20s", 20), ("90s", 20), ("10s", 0))
                if config.autoscaling
                else (("15s", 1), ("30s", 3))
            ),
            prometheus_queries=default_prometheus_queries(target.name),
            dedicated_loadgen=dedicated,
            fetch_results=remote,
            autoscaling=config.autoscaling,
        ),
        bindings,
        prometheus_client=prometheus_client,
        fetcher=cast(RemoteFileFetcher | None, fetcher),
    )
    stack.tasks.extend(load.tasks)
    stack.cleanup_tasks = workflow_from_specs(
        validate_cleanup_specs(deployment), bindings, cwd=root
    ).tasks
    return stack
