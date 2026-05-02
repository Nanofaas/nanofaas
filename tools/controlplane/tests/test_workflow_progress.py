from __future__ import annotations

from pathlib import Path

import pytest

from tui_toolkit import bind_workflow_context, bind_workflow_sink, workflow_log
from controlplane_tool.k3s_curl_runner import K3sCurlRunner
from controlplane_tool.workflow_models import WorkflowContext
from controlplane_tool.workflow_progress import WorkflowProgressReporter


def _runner() -> K3sCurlRunner:
    runner = K3sCurlRunner.__new__(K3sCurlRunner)
    runner.repo_root = Path(".")
    runner.namespace = "nanofaas-e2e"
    runner.runtime = "java"
    runner.vm_request = object()
    runner.registry = object()
    runner._shell = object()
    runner._vm = object()
    runner._cached_service_ip = None
    return runner


def test_workflow_progress_reporter_child_emits_balanced_events_with_parent_identity(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="e2e.k3s_junit_curl",
        task_id="tests.run_k3s_curl_checks",
        task_run_id="task-run-123",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        reporter = WorkflowProgressReporter.current()
        assert reporter.flow_id == "e2e.k3s_junit_curl"
        with reporter.child("verify.health", "Verifying control-plane health"):
            workflow_log("health endpoint responded")
            pass

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "log.line",
        "task.completed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.health",
        "verify.health",
        "verify.health",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
    assert fake_sink.events[1].line == "health endpoint responded"


def test_workflow_progress_reporter_child_emits_failed_events_with_parent_identity(fake_sink) -> None:
    context = WorkflowContext(
        flow_id="e2e.k3s_junit_curl",
        task_id="tests.run_k3s_curl_checks",
        task_run_id="task-run-123",
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(context):
        reporter = WorkflowProgressReporter.current()
        with pytest.raises(RuntimeError, match="boom"):
            with reporter.child("verify.prometheus", "Verifying Prometheus metrics"):
                raise RuntimeError("boom")

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.failed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.prometheus",
        "verify.prometheus",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]


def test_verify_existing_stack_emits_balanced_child_events_for_nested_verification_steps(
    fake_sink,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = _runner()
    calls: list[tuple[str, str | None]] = []

    monkeypatch.setattr(
        "controlplane_tool.k3s_curl_runner._selected_functions",
        lambda resolved: ["billing.fn", "analytics.fn"],
    )
    monkeypatch.setattr(runner, "_verify_health", lambda: calls.append(("health", None)))
    monkeypatch.setattr(
        runner,
        "_run_function_workflow",
        lambda fn_key, resolved: calls.append(("function", fn_key)),
    )
    monkeypatch.setattr(
        runner,
        "_verify_prometheus_metrics",
        lambda: calls.append(("prometheus", None)),
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.k3s_junit_curl", task_id="tests.run_k3s_curl_checks")
    ):
        runner.verify_existing_stack(resolved=None)

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
        "task.completed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.phase",
        "verify.health",
        "verify.health",
        "verify.function.billing.fn",
        "verify.function.billing.fn",
        "verify.function.analytics.fn",
        "verify.function.analytics.fn",
        "verify.prometheus",
        "verify.prometheus",
        "verify.phase",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
    assert calls == [
        ("health", None),
        ("function", "billing.fn"),
        ("function", "analytics.fn"),
        ("prometheus", None),
    ]


def test_verify_existing_stack_marks_failed_nested_verification_child(
    fake_sink,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = _runner()

    monkeypatch.setattr(
        "controlplane_tool.k3s_curl_runner._selected_functions",
        lambda resolved: ["billing.fn"],
    )
    monkeypatch.setattr(runner, "_verify_health", lambda: None)
    monkeypatch.setattr(runner, "_run_function_workflow", lambda fn_key, resolved: None)

    def _fail_prometheus() -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(runner, "_verify_prometheus_metrics", _fail_prometheus)

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.k3s_junit_curl", task_id="tests.run_k3s_curl_checks")
    ):
        with pytest.raises(RuntimeError, match="boom"):
            runner.verify_existing_stack(resolved=None)

    assert [event.kind for event in fake_sink.events] == [
        "task.running",
        "task.running",
        "task.completed",
        "task.running",
        "task.completed",
        "task.running",
        "task.failed",
        "task.failed",
    ]
    assert [event.task_id for event in fake_sink.events] == [
        "verify.phase",
        "verify.health",
        "verify.health",
        "verify.function.billing.fn",
        "verify.function.billing.fn",
        "verify.prometheus",
        "verify.prometheus",
        "verify.phase",
    ]
    assert [event.parent_task_id for event in fake_sink.events] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
        "tests.run_k3s_curl_checks",
    ]
