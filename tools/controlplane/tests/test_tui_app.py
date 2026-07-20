from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager, nullcontext
from io import StringIO
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

import pytest
from rich.console import Console
from rich.table import Table
from workflow_tasks import WorkflowEvent, bind_workflow_sink, step, workflow_log

import controlplane_tool.tui.app as tui_app
import controlplane_tool.tui.workflow_controller as workflow_controller_module
from controlplane_tool.cli.preflight import PreflightError
from controlplane_tool.tui import NanofaasTUI
from controlplane_tool.tui.workflow import TuiWorkflowSink
from controlplane_tool.tui.workflow_controller import TuiWorkflowController


class ScriptedChooser:
    def __init__(self, answers: Iterator[str | type[KeyboardInterrupt]]) -> None:
        self._answers = answers
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def __call__(self, message: str, **kwargs: Any) -> str:
        self.calls.append((message, kwargs))
        answer = next(self._answers)
        if isinstance(answer, str):
            return answer
        raise KeyboardInterrupt


class FakeWorkflow:
    def __init__(
        self,
        *,
        error: Exception | None = None,
        on_run: Callable[[], None] | None = None,
    ) -> None:
        self.tasks = [SimpleNamespace(task_id="prepare", title="Prepare environment")]
        self.phase_titles = ["Prepare environment", "Run checks"]
        self.keep_infrastructure = False
        self.run_calls = 0
        self._error = error
        self._on_run = on_run

    def run(self) -> None:
        self.run_calls += 1
        if self._on_run is not None:
            self._on_run()
        if self._error is not None:
            raise self._error


class RecordingSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str) -> Iterator[None]:
        yield


class RecordingController:
    def __init__(self, *, error: Exception | None = None) -> None:
        self.calls: list[dict[str, object]] = []
        self._error = error
        self.sink = RecordingSink()

    def run_live_workflow(self, **kwargs: object) -> None:
        self.calls.append(kwargs)
        action = kwargs["action"]
        assert callable(action)
        with bind_workflow_sink(self.sink):
            try:
                action(None, self.sink)
            except Exception:
                if self._error is None:
                    raise
        if self._error is not None:
            raise self._error


class RecordingConsole:
    def __init__(self) -> None:
        self.calls: list[tuple[str, object | None]] = []

    def clear(self) -> None:
        self.calls.append(("clear", None))

    def print(self, renderable: object) -> None:
        self.calls.append(("print", renderable))


class RecordingInput:
    def __init__(self, *, tty: bool) -> None:
        self.tty = tty
        self.read_calls: list[int] = []

    def isatty(self) -> bool:
        return self.tty

    def read(self, size: int = -1) -> str:
        self.read_calls.append(size)
        return "\n"


def _install_paths(monkeypatch: pytest.MonkeyPatch, root: Path) -> Path:
    environment_dir = root / "environments"
    environment_dir.mkdir(parents=True)
    environment_path = environment_dir / "local.yaml"
    environment_path.write_text("provider: local\n", encoding="utf-8")
    (root / "scenarios-v2").mkdir()
    monkeypatch.setattr(
        tui_app,
        "default_tool_paths",
        lambda: SimpleNamespace(tool_root=root, workspace_root=root),
    )
    return environment_path


def _install_workflow_helpers(
    monkeypatch: pytest.MonkeyPatch,
    *,
    environment_path: Path,
    workflow: FakeWorkflow,
    scenario_kind: str = "cli",
    provider: str = "local",
    calls: list[tuple[Any, ...]] | None = None,
) -> None:
    call_log = calls if calls is not None else []
    scenario = SimpleNamespace(workflow=scenario_kind)
    environment = SimpleNamespace(provider=provider)

    def load_scenario(path: Path) -> object:
        call_log.append(("scenario", path))
        return scenario

    def load_environment(path: Path) -> object:
        call_log.append(("environment", path))
        assert path == environment_path
        return environment

    def build_workflow(
        loaded_scenario: object,
        loaded_environment: object,
        **kwargs: object,
    ) -> FakeWorkflow:
        call_log.append(("workflow", loaded_scenario, loaded_environment, kwargs))
        return workflow

    monkeypatch.setattr(tui_app, "_scenario", load_scenario)
    monkeypatch.setattr(tui_app, "_environment", load_environment)
    monkeypatch.setattr(tui_app, "_workflow", build_workflow)
    monkeypatch.setattr(
        tui_app,
        "preflight_control_plane",
        lambda *_args, **_kwargs: None,
    )


def test_tui_exits_from_the_main_menu() -> None:
    calls: list[str] = []

    def choose(message: str, **kwargs: object) -> str:
        calls.append(message)
        return "exit"

    NanofaasTUI(choose=choose).run()

    assert calls == ["What would you like to do?"]


def test_tui_dispatches_a_stable_scenario_filename() -> None:
    answers = iter(["cli", "validate", "back", "exit"])
    dispatched: list[str] = []

    def choose(message: str, **kwargs: object) -> str:
        return next(answers)

    dispatch: Callable[[str], None] = dispatched.append
    NanofaasTUI(choose=choose, dispatch_scenario=dispatch).run()

    assert dispatched == ["cli.yaml"]


def test_tools_inspect_selects_only_stable_scenarios_and_renders_validated_json(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _install_paths(monkeypatch, tmp_path)
    loaded_paths: list[Path] = []

    class Scenario:
        def model_dump_json(self, *, by_alias: bool, indent: int) -> str:
            assert by_alias is True
            assert indent == 2
            return json.dumps(
                {
                    "workflow": "validate",
                    "x-function": "echo",
                    "detail": "[/bold] literal [/]",
                },
                indent=indent,
            )

    def load_scenario(path: Path) -> Scenario:
        loaded_paths.append(path)
        return Scenario()

    monkeypatch.setattr(tui_app, "_scenario", load_scenario)
    frame = object()
    frame_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        tui_app,
        "render_screen_frame",
        lambda **kwargs: frame_calls.append(kwargs) or frame,
    )
    console = RecordingConsole()
    chooser = ScriptedChooser(iter(["inspect", "validate-container.yaml", "back"]))

    NanofaasTUI(
        choose=chooser,
        console=console,
        input_stream=RecordingInput(tty=False),
    )._dispatch_section("tools")

    scenario_choices = chooser.calls[1][1]["choices"]
    assert [choice.value for choice in scenario_choices] == [
        "validate-container.yaml",
        "validate-k8s.yaml",
        "validate-offload.yaml",
        "cli.yaml",
        "loadtest.yaml",
        "offload-loadtest.yaml",
    ]
    assert loaded_paths == [tmp_path / "scenarios-v2" / "validate-container.yaml"]
    assert json.loads(str(frame_calls[0]["body"])) == {
        "workflow": "validate",
        "x-function": "echo",
        "detail": "[/bold] literal [/]",
    }
    assert frame_calls[0]["title"] == "Inspect scenario"
    assert frame_calls[0]["breadcrumb"] == "Main / Tools / Inspect scenario"
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


@pytest.mark.parametrize(
    ("available", "expected"),
    [
        ({"docker", "ssh"}, "ok"),
        (set(), "missing executables: docker, ssh"),
    ],
)
def test_tools_doctor_reuses_cli_prerequisites_inside_shared_chrome(
    monkeypatch: pytest.MonkeyPatch,
    available: set[str],
    expected: str,
) -> None:
    missing = [name for name in ("docker", "ssh") if name not in available]
    shared_check = MagicMock(return_value=missing)
    monkeypatch.setattr(
        tui_app,
        "diagnostics",
        SimpleNamespace(missing_executables=shared_check),
        raising=False,
    )
    monkeypatch.setattr(
        tui_app,
        "shutil",
        SimpleNamespace(
            which=lambda _name: (_ for _ in ()).throw(
                AssertionError("TUI duplicated the executable check")
            )
        ),
        raising=False,
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        tui_app,
        "render_screen_frame",
        lambda **kwargs: frame_calls.append(kwargs) or frame,
    )
    console = RecordingConsole()
    input_stream = RecordingInput(tty=True)
    chooser = ScriptedChooser(iter(["doctor", "back"]))

    NanofaasTUI(
        choose=chooser,
        console=console,
        input_stream=input_stream,
    )._dispatch_section("tools")

    shared_check.assert_called_once_with()
    assert len(frame_calls) == 1
    assert str(frame_calls[0].pop("body")) == expected
    assert frame_calls[0] == {
        "title": "Doctor",
        "breadcrumb": "Main / Tools / Doctor",
        "footer_hint": "Press Enter to continue",
    }
    assert input_stream.read_calls == [1]
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


def test_static_plain_text_is_rendered_literally_without_rich_markup() -> None:
    output = StringIO()
    console = Console(file=output, width=100, color_system=None)
    body = "[/bold] literal [/]"

    NanofaasTUI(
        console=console,
        input_stream=StringIO(),
    )._show_static("Error", "Main / Error", body)

    assert body in output.getvalue()


def test_plan_uses_cli_helpers_and_renders_without_running(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    helper_calls: list[tuple[Any, ...]] = []
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
        calls=helper_calls,
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []

    def render_frame(**kwargs: object) -> object:
        frame_calls.append(kwargs)
        return frame

    monkeypatch.setattr(tui_app, "render_screen_frame", render_frame)
    console = RecordingConsole()
    input_stream = RecordingInput(tty=False)
    chooser = ScriptedChooser(iter([str(environment_path), "plan"]))

    NanofaasTUI(
        choose=chooser,
        controller=RecordingController(),
        console=console,
        input_stream=input_stream,
    )._workflow_menu("cli.yaml")

    assert [call[0] for call in helper_calls] == ["scenario", "environment", "workflow"]
    assert helper_calls[0] == ("scenario", tmp_path / "scenarios-v2" / "cli.yaml")
    assert workflow.run_calls == 0
    assert len(frame_calls) == 1
    assert frame_calls[0]["title"] == "CLI"
    assert isinstance(frame_calls[0]["body"], Table)
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]
    assert input_stream.read_calls == []


def test_static_plan_acknowledges_only_for_an_input_tty(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=FakeWorkflow(),
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []

    def render_frame(**kwargs: object) -> object:
        frame_calls.append(kwargs)
        return frame

    monkeypatch.setattr(tui_app, "render_screen_frame", render_frame)
    console = RecordingConsole()
    input_stream = RecordingInput(tty=True)

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "plan"])),
        controller=RecordingController(),
        console=console,
        input_stream=input_stream,
    )._workflow_menu("cli.yaml")

    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]
    assert frame_calls[0]["footer_hint"] == "Press Enter to continue"
    assert input_stream.read_calls == [1]


def test_configuration_error_uses_the_shared_branded_static_view(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    monkeypatch.setattr(
        tui_app,
        "_scenario",
        lambda _path: (_ for _ in ()).throw(ValueError("invalid scenario")),
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        tui_app,
        "render_screen_frame",
        lambda **kwargs: frame_calls.append(kwargs) or frame,
    )
    console = RecordingConsole()

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "plan"])),
        console=console,
        input_stream=RecordingInput(tty=False),
    )._workflow_menu("cli.yaml")

    assert frame_calls[0]["title"] == "Configuration error"
    assert frame_calls[0]["breadcrumb"] == "Main / CLI"
    assert str(frame_calls[0]["body"]) == "invalid scenario"
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


def test_run_preview_error_uses_static_view_without_starting_live_dashboard(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=FakeWorkflow(),
    )
    monkeypatch.setattr(
        tui_app,
        "_workflow",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("preview failed")),
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        tui_app,
        "render_screen_frame",
        lambda **kwargs: frame_calls.append(kwargs) or frame,
    )
    console = RecordingConsole()
    controller = RecordingController()

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "run", "cleanup"])),
        controller=controller,
        console=console,
        input_stream=RecordingInput(tty=False),
    )._workflow_menu("cli.yaml")

    assert frame_calls[0]["title"] == "Preview error"
    assert str(frame_calls[0]["body"]) == "preview failed"
    assert controller.calls == []
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


def test_failed_cli_preflight_uses_static_view_without_starting_workflow(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
    )
    preflight_calls: list[tuple[object, object, str]] = []

    def fail_preflight(
        scenario: object,
        environment: object,
        *,
        base_url: str,
    ) -> None:
        preflight_calls.append((scenario, environment, base_url))
        raise PreflightError("control plane unavailable")

    monkeypatch.setattr(
        tui_app,
        "preflight_control_plane",
        fail_preflight,
    )
    frame = object()
    frame_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        tui_app,
        "render_screen_frame",
        lambda **kwargs: frame_calls.append(kwargs) or frame,
    )
    console = RecordingConsole()
    controller = RecordingController()

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "run", "cleanup"])),
        controller=controller,
        console=console,
        input_stream=RecordingInput(tty=False),
    )._workflow_menu("cli.yaml")

    assert len(preflight_calls) == 1
    assert preflight_calls[0][2] == "http://127.0.0.1:8080"
    assert frame_calls[0]["title"] == "Preflight error"
    assert str(frame_calls[0]["body"]) == "control plane unavailable"
    assert controller.calls == []
    assert workflow.run_calls == 0
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


def test_run_passes_phase_titles_and_exact_summary_to_live_controller(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
    )
    controller = RecordingController()

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "run", "cleanup"])),
        controller=controller,
    )._workflow_menu("cli.yaml")

    assert len(controller.calls) == 1
    assert controller.calls[0]["title"] == "CLI"
    assert controller.calls[0]["planned_steps"] == workflow.phase_titles
    assert controller.calls[0]["summary_lines"] == [
        "Scenario: cli.yaml",
        "Environment: local.yaml",
        "Provision: no",
        "Cleanup: cleanup",
    ]
    assert workflow.run_calls == 1


def test_loadtest_resolves_urls_before_building_workflow(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    calls: list[tuple[Any, ...]] = []
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
        scenario_kind="loadtest",
        calls=calls,
    )

    def resolve(environment: object, **kwargs: object) -> tuple[str, str]:
        calls.append(("resolve", environment, kwargs))
        return "http://control-plane", "http://prometheus"

    monkeypatch.setattr(tui_app, "resolve_loadtest_urls", resolve)

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "plan"])),
        controller=RecordingController(),
        console=RecordingConsole(),
        input_stream=RecordingInput(tty=False),
    )._workflow_menu("loadtest.yaml")

    call_names = [call[0] for call in calls]
    assert call_names == ["scenario", "environment", "resolve", "workflow"]
    assert calls[2][2] == {"dry_run": True}
    assert calls[3][3]["control_plane_url"] == "http://control-plane"
    assert calls[3][3]["prometheus_url"] == "http://prometheus"


def test_local_run_never_offers_or_enters_provisioning(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=FakeWorkflow(),
    )
    provision_calls: list[object] = []
    monkeypatch.setattr(
        tui_app,
        "provision_environment",
        lambda *args, **kwargs: provision_calls.append((args, kwargs)),
    )
    chooser = ScriptedChooser(iter([str(environment_path), "run", "cleanup"]))

    NanofaasTUI(choose=chooser, controller=RecordingController())._workflow_menu("cli.yaml")

    assert "Provision environment?" not in [message for message, _ in chooser.calls]
    assert provision_calls == []


def test_non_local_run_enters_existing_provisioning_context(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
        provider="multipass",
    )
    lifecycle: list[tuple[Any, ...]] = []

    @contextmanager
    def provision(*args: object, **kwargs: object) -> Iterator[None]:
        lifecycle.append(("enter", args, kwargs))
        yield
        lifecycle.append(("exit",))

    monkeypatch.setattr(tui_app, "provision_environment", provision)

    NanofaasTUI(
        choose=ScriptedChooser(
            iter([str(environment_path), "run", "provision", "cleanup"])
        ),
        controller=RecordingController(),
    )._workflow_menu("cli.yaml")

    assert [event[0] for event in lifecycle] == ["enter", "exit"]
    assert workflow.run_calls == 1


def test_nonlocal_loadtest_runs_provision_build_and_cleanup_inside_live_sink(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    events: list[tuple[Any, ...]] = []
    preview = FakeWorkflow()
    workflow = FakeWorkflow(on_run=lambda: events.append(("run",)))
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=preview,
        scenario_kind="loadtest",
        provider="multipass",
        calls=events,
    )

    build_count = 0

    def resolve(environment: object, **kwargs: object) -> tuple[str, str]:
        label = "resolve-preview" if kwargs["dry_run"] else "resolve-live"
        events.append((label,))
        return "http://control-plane", "http://prometheus"

    def build(*args: object, **kwargs: object) -> FakeWorkflow:
        nonlocal build_count
        build_count += 1
        label = "workflow-preview" if build_count == 1 else "workflow-live"
        events.append((label,))
        return preview if build_count == 1 else workflow

    @contextmanager
    def provision(*args: object, **kwargs: object) -> Iterator[None]:
        events.append(("provision-enter",))
        step("Provision stack")
        yield
        events.append(("provision-cleanup",))
        workflow_log("cleanup complete")
        events.append(("provision-exit",))

    class OrderingController:
        def __init__(self) -> None:
            self.sink = RecordingSink()

        def run_live_workflow(self, **kwargs: object) -> None:
            events.append(("live",))
            action = kwargs["action"]
            assert callable(action)
            assert kwargs["planned_steps"] == preview.phase_titles
            with bind_workflow_sink(self.sink):
                action(None, self.sink)
            events.append(("live-final",))

    controller = OrderingController()
    monkeypatch.setattr(tui_app, "resolve_loadtest_urls", resolve)
    monkeypatch.setattr(tui_app, "_workflow", build)
    monkeypatch.setattr(tui_app, "provision_environment", provision)

    NanofaasTUI(
        choose=ScriptedChooser(
            iter([str(environment_path), "run", "provision", "cleanup"])
        ),
        controller=controller,
    )._workflow_menu("loadtest.yaml")

    assert [event[0] for event in events] == [
        "scenario",
        "environment",
        "resolve-preview",
        "workflow-preview",
        "live",
        "provision-enter",
        "resolve-live",
        "workflow-live",
        "run",
        "provision-cleanup",
        "provision-exit",
        "live-final",
    ]
    assert preview.run_calls == 0
    assert workflow.run_calls == 1
    assert [(event.kind, event.title, event.line) for event in controller.sink.events] == [
        ("task.running", "Provision stack", ""),
        ("log.line", "", "cleanup complete"),
    ]


def test_provision_cleanup_error_reaches_real_controller_dashboard_and_acknowledgment(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
        provider="multipass",
    )

    @contextmanager
    def provision(*args: object, **kwargs: object) -> Iterator[None]:
        step("Provision stack")
        try:
            yield
        finally:
            workflow_log("cleanup started")
            raise RuntimeError("cleanup failed")

    emitted: list[WorkflowEvent] = []
    original_emit = TuiWorkflowSink.emit

    def record_emit(self: TuiWorkflowSink, event: WorkflowEvent) -> None:
        emitted.append(event)
        original_emit(self, event)

    live = MagicMock()
    live.__enter__.return_value = live
    live.__exit__.return_value = False
    listener = MagicMock()
    listener.input_is_tty = True
    monkeypatch.setattr(tui_app, "provision_environment", provision)
    monkeypatch.setattr(TuiWorkflowSink, "emit", record_emit)
    monkeypatch.setattr(workflow_controller_module, "Live", MagicMock(return_value=live))
    monkeypatch.setattr(
        workflow_controller_module,
        "WorkflowKeyListener",
        MagicMock(return_value=listener),
    )
    controller = TuiWorkflowController(console=MagicMock())

    NanofaasTUI(
        choose=ScriptedChooser(
            iter([str(environment_path), "run", "provision", "cleanup"])
        ),
        controller=controller,
    )._workflow_menu("cli.yaml")

    assert [(event.kind, event.title, event.line) for event in emitted[:2]] == [
        ("task.running", "Provision stack", ""),
        ("log.line", "", "cleanup started"),
    ]
    assert emitted[-1].kind == "task.failed"
    assert emitted[-1].detail == "cleanup failed"
    listener.wait_for_acknowledgment.assert_called_once_with()
    assert workflow.run_calls == 1


def test_keep_applies_to_provisioning_and_workflow_cleanup(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    preview = FakeWorkflow()
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=preview,
        provider="multipass",
    )
    workflows = iter([preview, workflow])
    monkeypatch.setattr(tui_app, "_workflow", lambda *args, **kwargs: next(workflows))
    provision_kwargs: list[dict[str, object]] = []

    @contextmanager
    def provision(*args: object, **kwargs: object) -> Iterator[None]:
        provision_kwargs.append(kwargs)
        yield

    monkeypatch.setattr(tui_app, "provision_environment", provision)

    NanofaasTUI(
        choose=ScriptedChooser(iter([str(environment_path), "run", "provision", "keep"])),
        controller=RecordingController(),
    )._workflow_menu("cli.yaml")

    assert provision_kwargs[0]["keep"] is True
    assert preview.keep_infrastructure is False
    assert preview.run_calls == 0
    assert workflow.keep_infrastructure is True
    assert workflow.run_calls == 1


def test_reselecting_local_environment_resets_remote_provisioning_choice(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    local_path = _install_paths(monkeypatch, tmp_path)
    remote_path = local_path.parent / "remote.yaml"
    remote_path.write_text("provider: multipass\n", encoding="utf-8")
    workflow = FakeWorkflow()
    monkeypatch.setattr(tui_app, "_scenario", lambda _path: SimpleNamespace(workflow="cli"))
    monkeypatch.setattr(
        tui_app,
        "_environment",
        lambda path: SimpleNamespace(
            provider="multipass" if path == remote_path else "local"
        ),
    )
    monkeypatch.setattr(tui_app, "_workflow", lambda *args, **kwargs: workflow)
    monkeypatch.setattr(
        tui_app,
        "preflight_control_plane",
        lambda *_args, **_kwargs: None,
    )
    provision_calls: list[tuple[tuple[object, ...], dict[str, object]]] = []
    monkeypatch.setattr(
        tui_app,
        "provision_environment",
        lambda *args, **kwargs: provision_calls.append((args, kwargs)) or nullcontext(),
    )
    controller = RecordingController()
    chooser = ScriptedChooser(
        iter(
            [
                str(remote_path),
                "run",
                "provision",
                "back",
                "back",
                "back",
                str(local_path),
                "run",
                "cleanup",
            ]
        )
    )

    NanofaasTUI(choose=chooser, controller=controller)._workflow_menu("cli.yaml")

    assert provision_calls == []
    assert workflow.run_calls == 1
    assert controller.calls[0]["summary_lines"] == [
        "Scenario: cli.yaml",
        "Environment: local.yaml",
        "Provision: no",
        "Cleanup: cleanup",
    ]


def test_workflow_failure_returns_to_the_previous_submenu(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    workflow = FakeWorkflow(error=RuntimeError("workflow failed"))
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
    )
    chooser = ScriptedChooser(
        iter(["cli", "validate", str(environment_path), "run", "cleanup", "back", "exit"])
    )

    NanofaasTUI(choose=chooser, controller=RecordingController()).run()

    assert [message for message, _ in chooser.calls].count("CLI") == 2
    assert chooser.calls[-1][0] == "What would you like to do?"


def test_environment_selection_offers_only_available_executable_yaml_configs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    environment_dir = environment_path.parent
    (environment_dir / "azure.yaml").write_text("provider: azure\n", encoding="utf-8")
    (environment_dir / "azure.yaml.example").write_text("example\n", encoding="utf-8")
    (environment_dir / "proxmox.example.yaml").write_text("example\n", encoding="utf-8")
    chooser = ScriptedChooser(iter([str(environment_path)]))

    selected = NanofaasTUI(choose=chooser)._select_environment()

    assert selected == environment_path
    offered = chooser.calls[0][1]["choices"]
    assert [choice.value for choice in offered] == [
        str(environment_dir / "azure.yaml"),
        str(environment_path),
    ]
    assert all(".example" not in choice.value for choice in offered)


def test_environment_selection_offers_provider_setup_when_templates_exist(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    environment_dir = environment_path.parent
    (environment_dir / "azure.yaml.example").write_text("example\n", encoding="utf-8")
    (environment_dir / "proxmox.yaml.example").write_text(
        "example\n", encoding="utf-8"
    )
    chooser = ScriptedChooser(iter([str(environment_path)]))

    NanofaasTUI(choose=chooser)._select_environment()

    offered = chooser.calls[0][1]["choices"]
    assert [choice.value for choice in offered] == [
        str(environment_path),
        "setup:azure",
        "setup:proxmox",
    ]
    assert [choice.title for choice in offered[1:]] == [
        "Azure (setup required)",
        "Proxmox (setup required)",
    ]
    assert all(".yaml.example" not in choice.value for choice in offered)


def test_environment_selection_suppresses_provider_setup_for_executable_yaml(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    environment_dir = environment_path.parent
    for provider in ("azure", "proxmox"):
        (environment_dir / f"{provider}.yaml.example").write_text(
            "example\n", encoding="utf-8"
        )
        (environment_dir / f"{provider}.yaml").write_text(
            f"provider: {provider}\n", encoding="utf-8"
        )
    chooser = ScriptedChooser(iter([str(environment_path)]))

    NanofaasTUI(choose=chooser)._select_environment()

    offered = chooser.calls[0][1]["choices"]
    assert [choice.value for choice in offered] == [
        str(environment_dir / "azure.yaml"),
        str(environment_path),
        str(environment_dir / "proxmox.yaml"),
    ]
    assert all(not choice.value.startswith("setup:") for choice in offered)


def test_provider_setup_ignores_directories_at_file_boundaries(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    environment_dir = environment_path.parent
    (environment_dir / "azure.yaml.example").write_text("example\n", encoding="utf-8")
    (environment_dir / "azure.yaml").mkdir()
    (environment_dir / "proxmox.yaml.example").mkdir()
    chooser = ScriptedChooser(iter([str(environment_path)]))

    NanofaasTUI(choose=chooser)._select_environment()

    offered_values = [choice.value for choice in chooser.calls[0][1]["choices"]]
    assert offered_values == [str(environment_path), "setup:azure"]
    assert str(environment_dir / "azure.yaml") not in offered_values
    assert "setup:proxmox" not in offered_values


def test_provider_setup_guidance_returns_to_rebuilt_environment_picker(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    environment_dir = environment_path.parent
    (environment_dir / "azure.yaml.example").write_text("example\n", encoding="utf-8")
    (environment_dir / "proxmox.yaml.example").write_text(
        "example\n", encoding="utf-8"
    )
    chooser = ScriptedChooser(
        iter(["setup:azure", "setup:proxmox", str(environment_path)])
    )
    screens: list[tuple[str, str, str]] = []
    tui = NanofaasTUI(choose=chooser)

    def show_static(title: str, breadcrumb: str, body: str) -> None:
        screens.append((title, breadcrumb, body))
        if title == "Azure setup required":
            (environment_dir / "azure.yaml").write_text(
                "provider: azure\n", encoding="utf-8"
            )

    monkeypatch.setattr(
        tui,
        "_show_static",
        show_static,
    )

    selected = tui._select_environment()

    assert selected == environment_path
    assert len(chooser.calls) == 3
    assert all(call[0] == "Environment" for call in chooser.calls)
    second_picker_values = [
        choice.value for choice in chooser.calls[1][1]["choices"]
    ]
    assert str(environment_dir / "azure.yaml") in second_picker_values
    assert "setup:azure" not in second_picker_values
    assert "setup:proxmox" in second_picker_values
    assert [screen[1] for screen in screens] == [
        "Main / Environment",
        "Main / Environment",
    ]
    azure_body = screens[0][2]
    assert (
        "cp tools/controlplane/environments/azure.yaml.example "
        "tools/controlplane/environments/azure.yaml" in azure_body
    )
    assert all(value in azure_body for value in ("provider", "ssh_key_path", "az login"))
    proxmox_body = screens[1][2]
    assert (
        "cp tools/controlplane/environments/proxmox.yaml.example "
        "tools/controlplane/environments/proxmox.yaml" in proxmox_body
    )
    assert all(
        value in proxmox_body
        for value in (
            "host",
            "node",
            "template_id",
            "ssh_key_path",
            "password_env",
            "PROXMOX_PASSWORD",
        )
    )


def test_provider_setup_never_loads_template_as_executable_yaml(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    (environment_path.parent / "azure.yaml.example").write_text(
        "provider: azure\n", encoding="utf-8"
    )
    loaded_paths: list[Path] = []
    workflow = FakeWorkflow()
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=workflow,
        calls=[],
    )

    def load_environment(path: Path) -> object:
        loaded_paths.append(path)
        return SimpleNamespace(provider="local")

    monkeypatch.setattr(tui_app, "_environment", load_environment)
    chooser = ScriptedChooser(iter(["setup:azure", str(environment_path), "plan"]))
    tui = NanofaasTUI(choose=chooser)
    monkeypatch.setattr(tui, "_show_static", lambda *args, **kwargs: None)

    tui._workflow_menu("cli.yaml")

    assert loaded_paths == [environment_path]
    assert all(not path.name.endswith(".yaml.example") for path in loaded_paths)


@pytest.mark.parametrize(
    ("provider", "answers", "expected_messages"),
    [
        ("local", ["back"], ["Environment"]),
        (
            "local",
            ["environment", "back", "back"],
            ["Environment", "Action", "Environment"],
        ),
        (
            "multipass",
            ["environment", "run", "back", "back", "back"],
            ["Environment", "Action", "Provision environment?", "Action", "Environment"],
        ),
        (
            "multipass",
            ["environment", "run", "existing", "back", "back", "back", "back"],
            [
                "Environment",
                "Action",
                "Provision environment?",
                "Cleanup policy",
                "Provision environment?",
                "Action",
                "Environment",
            ],
        ),
        (
            "local",
            ["environment", "run", "back", "back", "back"],
            ["Environment", "Action", "Cleanup policy", "Action", "Environment"],
        ),
    ],
    ids=[
        "environment-to-scenario",
        "action-to-environment",
        "provision-to-action",
        "nonlocal-cleanup-to-provision",
        "local-cleanup-to-action",
    ],
)
def test_workflow_back_navigation_returns_to_exact_parent(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    provider: str,
    answers: list[str],
    expected_messages: list[str],
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=FakeWorkflow(),
        provider=provider,
    )
    resolved_answers = iter(
        str(environment_path) if answer == "environment" else answer for answer in answers
    )
    chooser = ScriptedChooser(resolved_answers)

    NanofaasTUI(choose=chooser, controller=RecordingController())._workflow_menu("cli.yaml")

    assert [message for message, _ in chooser.calls] == expected_messages
    assert all(kwargs["include_back"] is True for _, kwargs in chooser.calls)
    assert all(kwargs["escape_value"] == "back" for _, kwargs in chooser.calls)


def test_environment_back_returns_to_the_scenario_submenu(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _install_paths(monkeypatch, tmp_path)
    chooser = ScriptedChooser(iter(["cli", "validate", "back", "back", "exit"]))

    NanofaasTUI(choose=chooser).run()

    assert [message for message, _ in chooser.calls] == [
        "What would you like to do?",
        "CLI",
        "Environment",
        "CLI",
        "What would you like to do?",
    ]


@pytest.mark.parametrize(
    ("provider", "answers", "expected_messages"),
    [
        ("local", [KeyboardInterrupt], ["Environment"]),
        ("local", ["environment", KeyboardInterrupt], ["Environment", "Action"]),
        (
            "multipass",
            ["environment", "run", KeyboardInterrupt],
            ["Environment", "Action", "Provision environment?"],
        ),
        (
            "multipass",
            ["environment", "run", "existing", KeyboardInterrupt],
            ["Environment", "Action", "Provision environment?", "Cleanup policy"],
        ),
        (
            "local",
            ["environment", "run", KeyboardInterrupt],
            ["Environment", "Action", "Cleanup policy"],
        ),
    ],
    ids=["environment", "action", "provision", "nonlocal-cleanup", "local-cleanup"],
)
def test_ctrl_c_from_every_workflow_depth_propagates_to_exit(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    provider: str,
    answers: list[str | type[KeyboardInterrupt]],
    expected_messages: list[str],
) -> None:
    environment_path = _install_paths(monkeypatch, tmp_path)
    _install_workflow_helpers(
        monkeypatch,
        environment_path=environment_path,
        workflow=FakeWorkflow(),
        provider=provider,
    )
    resolved_answers = iter([
        "cli",
        "validate",
        *(
            str(environment_path) if answer == "environment" else answer
            for answer in answers
        ),
    ])
    chooser = ScriptedChooser(resolved_answers)

    NanofaasTUI(choose=chooser, controller=RecordingController()).run()

    assert [message for message, _ in chooser.calls] == [
        "What would you like to do?",
        "CLI",
        *expected_messages,
    ]
