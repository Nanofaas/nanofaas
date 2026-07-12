from dataclasses import dataclass, field
from pathlib import Path

from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.models import PrometheusQuery
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult
from workflow_tasks.workflows.loadtest import (
    LoadtestWorkflowRequest,
    build_loadtest_workflow,
)


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


class NoopPrometheus:
    def query_range(self, *args, **kwargs):
        return [{"timestamp": "2026-01-01T00:00:00Z", "value": 1.0}]


def request(
    tmp_path: Path, *, dedicated: bool, fetch_results: bool | None = None
) -> LoadtestWorkflowRequest:
    return LoadtestWorkflowRequest(
        control_plane_url="http://stack:30080",
        function_name="word-stats-java",
        script_path=Path("assets/k6/invoke.js"),
        summary_path=tmp_path / "k6-summary.json",
        run_dir=tmp_path,
        stages=(("5s", 1),),
        prometheus_queries=(PrometheusQuery("dispatch", "function_dispatch_total", True),),
        dedicated_loadgen=dedicated,
        fetch_results=dedicated if fetch_results is None else fetch_results,
    )


def test_shared_stack_and_loadgen_use_stack_role(tmp_path: Path) -> None:
    host = RecordingExecutor()
    stack = RecordingExecutor()
    workflow = build_loadtest_workflow(
        request(tmp_path, dedicated=False),
        RoleBindings(host=host, stack=stack),
        prometheus_client=NoopPrometheus(),
    )

    assert workflow.task_ids == [
        "loadgen.preflight",
        "loadgen.prepare",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "metrics.evaluate_gate",
    ]
    assert workflow.tasks[0].spec.role == "stack"
    assert workflow.tasks[1].spec.role == "stack"


def test_dedicated_loadgen_uses_loadgen_role(tmp_path: Path) -> None:
    workflow = build_loadtest_workflow(
        request(tmp_path, dedicated=True),
        RoleBindings(
            host=RecordingExecutor(),
            stack=RecordingExecutor(),
            loadgen=RecordingExecutor(),
        ),
        prometheus_client=NoopPrometheus(),
        fetcher=object(),
    )

    assert workflow.tasks[0].spec.role == "loadgen"
    assert workflow.tasks[1].spec.role == "loadgen"
    assert workflow.task_ids[3] == "loadgen.fetch_results"


def test_dedicated_loadgen_requires_a_fetcher(tmp_path: Path) -> None:
    import pytest

    with pytest.raises(ValueError, match="fetcher"):
        build_loadtest_workflow(
            request(tmp_path, dedicated=True),
            RoleBindings(
                host=RecordingExecutor(),
                stack=RecordingExecutor(),
                loadgen=RecordingExecutor(),
            ),
            prometheus_client=NoopPrometheus(),
        )


def test_remote_shared_role_can_fetch_results_without_a_dedicated_loadgen(
    tmp_path: Path,
) -> None:
    workflow = build_loadtest_workflow(
        request(tmp_path, dedicated=False, fetch_results=True),
        RoleBindings(host=RecordingExecutor(), stack=RecordingExecutor()),
        prometheus_client=NoopPrometheus(),
        fetcher=object(),
    )

    assert workflow.tasks[0].spec.role == "stack"
    assert workflow.task_ids[3] == "loadgen.fetch_results"
