from dataclasses import dataclass, field, replace
from pathlib import Path

from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.autoscaling import RunK6WithReplicaWatch
from workflow_tasks.loadtest.models import PrometheusQuery
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult
from workflow_tasks.workflows.loadtest import (
    LoadtestWorkflowRequest,
    build_loadtest_workflow,
    default_prometheus_queries,
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


def test_default_prometheus_queries_use_exported_metrics_and_filters() -> None:
    queries = {query.name: query for query in default_prometheus_queries('word-"stats')}

    assert queries["function_dispatch_total"].required is True
    assert queries["function_success_total"].required is True
    assert queries["function_latency_count"].required is True
    assert queries["function_latency_sum"].required is True
    assert queries["process_cpu_usage"].required is True
    assert queries["jvm_heap_used_bytes"].required is True
    assert queries["function_dispatch_total"].expr == (
        'function_dispatch_total{function="word-\\"stats"}'
    )
    assert queries["function_retry_total"].expr.startswith("function_retry_total{")
    assert queries["function_timeout_total"].expr.startswith("function_timeout_total{")
    assert queries["function_queue_rejected_total"].expr.startswith(
        "function_queue_rejected_total{"
    )
    assert queries["function_latency_sum"].expr.startswith(
        "function_latency_ms_seconds_sum{"
    )
    assert queries["function_queue_wait_count"].expr.startswith(
        "function_queue_wait_ms_seconds_count{"
    )
    assert queries["process_cpu_usage"].expr == (
        'process_cpu_usage{app="nanofaas-control-plane"}'
    )
    assert queries["jvm_heap_used_bytes"].expr == (
        'jvm_memory_used_bytes{app="nanofaas-control-plane",area="heap"}'
    )


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
        "loadtest.write_summary",
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


def test_autoscaling_wraps_k6_and_verifies_replica_lifecycle(tmp_path: Path) -> None:
    workflow = build_loadtest_workflow(
        replace(request(tmp_path, dedicated=False), autoscaling=True),
        RoleBindings(host=RecordingExecutor(), stack=RecordingExecutor()),
        prometheus_client=NoopPrometheus(),
    )

    run = next(task for task in workflow.tasks if task.task_id == "loadgen.run_k6")
    assert isinstance(run, RunK6WithReplicaWatch)
    assert "autoscaling.verify_replicas" in workflow.task_ids
