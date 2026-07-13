import json
from pathlib import Path

from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.execution.roles import ExecutionRole
from workflow_tasks.workflows.cli import (
    CliFunction,
    CliWorkflowRequest,
    cli_cleanup_specs,
    cli_task_specs,
)

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans._assembly import workflow_from_specs
from controlplane_tool.plans.validate import _resolve_function


def build_cli_plan(
    config: ScenarioConfig,
    bindings: RoleBindings,
    *,
    cli_role: ExecutionRole = "host",
    endpoint: str = "http://127.0.0.1:8080",
    namespace: str = "nanofaas-e2e",
    repo_root: Path | None = None,
) -> Workflow:
    if config.workflow != "cli":
        raise ValueError("CLI plan requires a cli scenario")
    functions = tuple(
        CliFunction(
            name=resolved.name,
            image=resolved.image,
            payload=json.dumps(json.loads(resolved.payload)["input"], separators=(",", ":")),
            resources=resolved.resources,
        )
        for key in config.functions
        for resolved in (_resolve_function(config, key),)
    )
    request = CliWorkflowRequest(
        functions=functions,
        cli_role=cli_role,
        endpoint=endpoint,
        namespace=namespace,
    )
    root = repo_root or Path.cwd()
    workflow = workflow_from_specs(cli_task_specs(request), bindings, cwd=root)
    workflow.cleanup_tasks = workflow_from_specs(
        cli_cleanup_specs(request), bindings, cwd=root
    ).tasks
    return workflow
