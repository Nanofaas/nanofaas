from pathlib import Path
from typing import cast

from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.workflows.loadtest import (
    DEFAULT_PROMETHEUS_QUERIES,
    LoadtestWorkflowRequest,
    build_loadtest_workflow,
)

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
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
    stages: tuple[tuple[str, int], ...] = (("15s", 1), ("30s", 3)),
) -> Workflow:
    if config.workflow != "loadtest":
        raise ValueError("load-test plan requires a loadtest scenario")
    target = _resolve_function(config, config.functions[0])
    dedicated = "loadgen" in environment.roles
    remote = environment.provider != "local"
    root = repo_root or Path.cwd()
    if remote:
        role_target = environment.target("loadgen" if dedicated else "stack")
        home = _home(role_target.user, role_target.home)
        script_path = Path(home) / "nanofaas/tools/controlplane/assets/k6/two-vm-function-invoke.js"
        summary_path = Path(home) / "nanofaas-loadtest/k6-summary.json"
    else:
        script_path = root / "tools/controlplane/assets/k6/two-vm-function-invoke.js"
        summary_path = run_dir / "k6-summary.json"
    return build_loadtest_workflow(
        LoadtestWorkflowRequest(
            control_plane_url=control_plane_url,
            function_name=target.name,
            script_path=script_path,
            summary_path=summary_path,
            run_dir=run_dir,
            stages=stages,
            prometheus_queries=DEFAULT_PROMETHEUS_QUERIES,
            dedicated_loadgen=dedicated,
            fetch_results=remote,
        ),
        bindings,
        prometheus_client=prometheus_client,
        fetcher=cast(RemoteFileFetcher | None, fetcher),
    )
