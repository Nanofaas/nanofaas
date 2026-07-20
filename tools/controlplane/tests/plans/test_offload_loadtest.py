from dataclasses import dataclass, field
from pathlib import Path

import pytest
import yaml

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.offload_loadtest import build_offload_loadtest_plan
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.tasks import FetchVmResults, RunK6
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult

REPO_ROOT = Path(__file__).resolve().parents[4]

SCENARIO = ScenarioConfig(
    workflow="offload-loadtest",
    backend="k8s",
    functions=["word-stats-java", "json-transform-java"],
)


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


def _local_environment() -> EnvironmentConfig:
    return EnvironmentConfig(provider="local")


def _external_environment() -> EnvironmentConfig:
    return EnvironmentConfig.model_validate(
        {
            "provider": "external",
            "roles": {
                "stack": {"host": "edge.example"},
                "cloud": {"host": "cloud.example"},
                "loadgen": {"host": "loadgen.example"},
            },
        }
    )


def _bindings(executor: RecordingExecutor) -> RoleBindings:
    return RoleBindings(host=executor, stack=executor, loadgen=executor, cloud=executor)


def test_rejects_non_offload_loadtest_scenario() -> None:
    with pytest.raises(ValueError, match="offload-loadtest"):
        build_offload_loadtest_plan(
            ScenarioConfig(workflow="loadtest", functions=["word-stats-java"]),
            _local_environment(),
            _bindings(RecordingExecutor()),
            run_dir=Path("/tmp/run"),
        )


def test_rejects_wrong_function_count() -> None:
    with pytest.raises(ValueError, match="exactly two functions"):
        build_offload_loadtest_plan(
            ScenarioConfig(
                workflow="offload-loadtest", backend="k8s", functions=["word-stats-java"]
            ),
            _local_environment(),
            _bindings(RecordingExecutor()),
            run_dir=Path("/tmp/run"),
        )


def test_deployment_then_registration_then_k6_then_evaluation_ordering(tmp_path: Path) -> None:
    workflow = build_offload_loadtest_plan(
        SCENARIO,
        _local_environment(),
        _bindings(RecordingExecutor()),
        run_dir=tmp_path,
        repo_root=REPO_ROOT,
    )

    ids = [task.task_id for task in workflow.tasks]
    last_cloud_deploy = max(i for i, t in enumerate(ids) if t.startswith("cloud."))
    first_edge_deploy = min(
        i for i, t in enumerate(ids) if t == "helm.deploy.control-plane"
    )
    first_registration = min(
        i for i, t in enumerate(ids) if t.startswith("offload-loadtest.register.")
    )
    run_k6_index = ids.index("offload-loadtest.run_k6")
    evaluate_index = ids.index("offload-loadtest.evaluate_conservation")

    assert last_cloud_deploy < first_edge_deploy
    assert first_edge_deploy < first_registration
    assert first_registration < run_k6_index
    assert run_k6_index < evaluate_index
    assert ids[-1] == "offload-loadtest.evaluate_conservation"


def test_local_provider_runs_k6_on_stack_without_fetch(tmp_path: Path) -> None:
    workflow = build_offload_loadtest_plan(
        SCENARIO,
        _local_environment(),
        _bindings(RecordingExecutor()),
        run_dir=tmp_path,
        repo_root=REPO_ROOT,
    )

    run_k6 = next(task for task in workflow.tasks if isinstance(task, RunK6))
    assert run_k6.runner._role == "stack"  # noqa: SLF001
    assert not any(isinstance(task, FetchVmResults) for task in workflow.tasks)


def test_dedicated_loadgen_runs_k6_on_loadgen_and_fetches_results(tmp_path: Path) -> None:
    workflow = build_offload_loadtest_plan(
        SCENARIO,
        _external_environment(),
        _bindings(RecordingExecutor()),
        run_dir=tmp_path,
        repo_root=REPO_ROOT,
        fetcher=object(),
    )

    run_k6 = next(task for task in workflow.tasks if isinstance(task, RunK6))
    assert run_k6.runner._role == "loadgen"  # noqa: SLF001
    assert any(isinstance(task, FetchVmResults) for task in workflow.tasks)


def test_dedicated_loadgen_requires_a_fetcher(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="fetcher is required"):
        build_offload_loadtest_plan(
            SCENARIO,
            _external_environment(),
            _bindings(RecordingExecutor()),
            run_dir=tmp_path,
            repo_root=REPO_ROOT,
        )


def test_edge_offload_target_points_at_the_cloud_role(tmp_path: Path) -> None:
    workflow = build_offload_loadtest_plan(
        SCENARIO,
        _external_environment(),
        _bindings(RecordingExecutor()),
        run_dir=tmp_path,
        repo_root=REPO_ROOT,
        fetcher=object(),
    )

    helm = next(task for task in workflow.tasks if task.task_id == "helm.deploy.control-plane")
    joined = " ".join(helm.spec.argv)
    assert "].value=http://cloud.example:30080" in joined


def test_cleanup_covers_both_control_planes(tmp_path: Path) -> None:
    workflow = build_offload_loadtest_plan(
        SCENARIO,
        _local_environment(),
        _bindings(RecordingExecutor()),
        run_dir=tmp_path,
        repo_root=REPO_ROOT,
    )

    cleanup_ids = {task.task_id for task in workflow.cleanup_tasks}
    assert "functions.delete.word-stats-java" in cleanup_ids
    assert "functions.delete.json-transform-java" in cleanup_ids
    assert "helm.uninstall.control-plane" in cleanup_ids
    assert "cloud.functions.delete.word-stats-java" in cleanup_ids
    assert "cloud.helm.uninstall.control-plane" in cleanup_ids
    assert "cloud.functions.delete.json-transform-java" not in cleanup_ids


def test_scenario_file_parses_with_two_ordered_functions() -> None:
    payload = yaml.safe_load(
        (REPO_ROOT / "tools/controlplane/scenarios-v2/offload-loadtest.yaml").read_text()
    )
    config = ScenarioConfig.model_validate(payload)
    assert config.workflow == "offload-loadtest"
    assert config.backend == "k8s"
    assert config.functions == ["word-stats-java", "json-transform-java"]
