from contextlib import contextmanager
from threading import Thread

from controlplane_tool.console import (
    bind_workflow_sink,
    fail,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
)


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[tuple[str, str, str]] = []

    def phase(self, label: str) -> None:
        self.events.append(("phase", label, ""))

    def step(self, label: str, detail: str = "") -> None:
        self.events.append(("step", label, detail))

    def success(self, label: str, detail: str = "") -> None:
        self.events.append(("success", label, detail))

    def warning(self, label: str) -> None:
        self.events.append(("warning", label, ""))

    def skip(self, label: str) -> None:
        self.events.append(("skip", label, ""))

    def fail(self, label: str, detail: str = "") -> None:
        self.events.append(("fail", label, detail))

    def log(self, message: str, stream: str = "stdout") -> None:
        self.events.append(("log", stream, message))

    @contextmanager
    def status(self, label: str):
        self.events.append(("status-start", label, ""))
        try:
            yield
        finally:
            self.events.append(("status-end", label, ""))


def test_bind_workflow_sink_routes_console_helpers() -> None:
    sink = _FakeSink()

    with bind_workflow_sink(sink):
        phase("Build")
        step("Compile", "profile=k8s")
        warning("Using cached dependencies")
        skip("Skip optional image build")
        with status("Waiting for readiness"):
            pass
        success("Workflow completed", "exit code 0")
        fail("Workflow failed", "exit code 1")

    assert sink.events == [
        ("phase", "Build", ""),
        ("step", "Compile", "profile=k8s"),
        ("warning", "Using cached dependencies", ""),
        ("skip", "Skip optional image build", ""),
        ("status-start", "Waiting for readiness", ""),
        ("status-end", "Waiting for readiness", ""),
        ("success", "Workflow completed", "exit code 0"),
        ("fail", "Workflow failed", "exit code 1"),
    ]


def test_workflow_log_from_background_thread_uses_bound_sink() -> None:
    sink = _FakeSink()

    with bind_workflow_sink(sink):
        thread = Thread(target=lambda: workflow_log("stream line", stream="stdout"))
        thread.start()
        thread.join()

    assert ("log", "stdout", "stream line") in sink.events
