from pathlib import Path

import pytest

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.offload import build_offload_plan
from workflow_tasks.core.resource_task import ResourceTask
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import TaskResult


REPO_ROOT = Path(__file__).resolve().parents[4]


class _RecordingExecutor:
    def run(self, spec, **kwargs):  # noqa: ANN001, ANN003
        return TaskResult(return_code=0, stdout="", stderr="")


def _bindings() -> RoleBindings:
    return RoleBindings(host=_RecordingExecutor(), stack=_RecordingExecutor())


def _scenario() -> ScenarioConfig:
    return ScenarioConfig(workflow="offload", functions=["word-stats-java"])


def test_offload_scenario_rejects_a_backend() -> None:
    with pytest.raises(ValueError, match="backend"):
        ScenarioConfig(workflow="offload", backend="container", functions=["word-stats-java"])


def test_plan_starts_cloud_then_edge_as_managed_resources() -> None:
    workflow = build_offload_plan(_scenario(), _bindings(), repo_root=REPO_ROOT)

    resources = [task for task in workflow.tasks if isinstance(task, ResourceTask)]
    assert [task.task_id for task in resources] == ["offload.start.cloud", "offload.start.edge"]
    ids = [task.task_id for task in workflow.tasks]
    assert ids.index("offload.start.cloud") < ids.index("offload.start.edge")
    # both processes start before any registration or check
    first_register = next(i for i, name in enumerate(ids) if name.startswith("offload.register"))
    assert ids.index("offload.start.edge") < first_register


def test_plan_ends_with_the_no_fallback_negative_check_and_cleans_up() -> None:
    workflow = build_offload_plan(_scenario(), _bindings(), repo_root=REPO_ROOT)

    ids = [task.task_id for task in workflow.tasks]
    assert ids[-1] == "offload.verify.remote-missing.word-stats-java"
    cleanup_ids = {task.task_id for task in workflow.cleanup_tasks}
    assert cleanup_ids == {
        "offload.delete.edge.word-stats-java",
        "offload.delete.cloud.word-stats-java",
    }


def test_offload_scenario_file_parses() -> None:
    import yaml

    payload = yaml.safe_load(
        (REPO_ROOT / "tools/controlplane/scenarios-v2/validate-offload.yaml").read_text()
    )
    config = ScenarioConfig.model_validate(payload)
    assert config.workflow == "offload"
    assert config.functions == ["word-stats-java"]
