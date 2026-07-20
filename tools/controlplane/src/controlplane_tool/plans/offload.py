"""Assemble the two-instance offload scenario into an executable workflow."""

from pathlib import Path
import urllib.request

from workflow_tasks.components.container import managed_process_resource
from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.workflows.offload import (
    CLOUD_ARGV,
    CLOUD_MANAGEMENT,
    EDGE_ARGV,
    EDGE_MANAGEMENT,
    JAR_PATH,
    OffloadWorkflowRequest,
    START_CLOUD_TASK_ID,
    START_EDGE_TASK_ID,
    offload_cleanup_specs,
    offload_task_specs,
)

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans._assembly import workflow_from_specs
from controlplane_tool.plans.validate import _resolve_function


def _health_probe(management_url: str):
    health_url = f"{management_url}/actuator/health"

    def ready() -> bool:
        try:
            with urllib.request.urlopen(health_url, timeout=1) as response:
                return response.status == 200
        except OSError:
            return False

    return ready


def _control_plane_resource(task_id: str, title: str, argv, management_url: str, root: Path):
    jar_index = argv.index(JAR_PATH)
    absolute = argv[:jar_index] + (str(root / JAR_PATH),) + argv[jar_index + 1 :]
    return managed_process_resource(
        task_id=task_id,
        title=title,
        argv=absolute,
        cwd=root,
        ready=_health_probe(management_url),
    )


def build_offload_plan(
    config: ScenarioConfig,
    bindings: RoleBindings,
    *,
    repo_root: Path | None = None,
) -> Workflow:
    if config.workflow != "offload":
        raise ValueError("offload plan requires an offload scenario")
    request = OffloadWorkflowRequest(
        functions=tuple(_resolve_function(config, key) for key in config.functions),
    )
    root = repo_root or Path.cwd()
    specs = offload_task_specs(request)
    start_ids = {START_CLOUD_TASK_ID, START_EDGE_TASK_ID}
    workflow = workflow_from_specs(
        tuple(spec for spec in specs if spec.task_id not in start_ids),
        bindings,
        cwd=root,
    )
    positions = {spec.task_id: index for index, spec in enumerate(specs)}
    workflow.tasks.insert(
        positions[START_CLOUD_TASK_ID],
        _control_plane_resource(
            START_CLOUD_TASK_ID,
            "Start cloud control plane",
            CLOUD_ARGV,
            CLOUD_MANAGEMENT,
            root,
        ),
    )
    workflow.tasks.insert(
        positions[START_EDGE_TASK_ID],
        _control_plane_resource(
            START_EDGE_TASK_ID,
            "Start edge control plane",
            EDGE_ARGV,
            EDGE_MANAGEMENT,
            root,
        ),
    )
    workflow.cleanup_tasks = workflow_from_specs(
        offload_cleanup_specs(request), bindings, cwd=root
    ).tasks
    return workflow
