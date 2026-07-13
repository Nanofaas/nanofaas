from datetime import UTC, datetime

from controlplane_tool.cli.progress import ConsoleProgressSink
from workflow_tasks.workflow.events import WorkflowEvent


def test_console_progress_reports_task_status_and_elapsed_time() -> None:
    lines: list[str] = []
    times = iter((10.0, 12.5, 20.0, 21.0))
    sink = ConsoleProgressSink(write=lines.append, clock=lambda: next(times))

    sink.emit(_event("task.running", "build.jvm", "Build JVM"))
    sink.emit(_event("task.completed", "build.jvm", "Build JVM"))
    sink.emit(_event("task.running", "helm.deploy", "Deploy Helm"))
    sink.emit(_event("task.failed", "helm.deploy", "Deploy Helm", "timeout"))

    assert lines == [
        "[build.jvm] running  Build JVM",
        "[build.jvm] passed   2.5s",
        "[helm.deploy] running  Deploy Helm",
        "[helm.deploy] failed   1.0s  timeout",
    ]
    assert sink.records == [
        {
            "task_id": "build.jvm",
            "title": "Build JVM",
            "status": "passed",
            "duration_seconds": 2.5,
            "detail": "",
        },
        {
            "task_id": "helm.deploy",
            "title": "Deploy Helm",
            "status": "failed",
            "duration_seconds": 1.0,
            "detail": "timeout",
        },
    ]


def _event(kind: str, task_id: str, title: str, detail: str = "") -> WorkflowEvent:
    return WorkflowEvent(
        kind=kind,
        flow_id="cli.run",
        task_id=task_id,
        title=title,
        detail=detail,
        at=datetime.now(UTC),
    )
