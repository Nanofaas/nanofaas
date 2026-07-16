from __future__ import annotations

from collections.abc import Callable, Iterator
from contextlib import contextmanager
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

import pytest
from rich.table import Table
from workflow_tasks import WorkflowEvent, bind_workflow_sink, step, workflow_log

import controlplane_tool.tui.app as tui_app
import controlplane_tool.tui.workflow_controller as workflow_controller_module
from controlplane_tool.tui import NanofaasTUI
from controlplane_tool.tui.workflow import TuiWorkflowSink
from controlplane_tool.tui.workflow_controller import TuiWorkflowController


class ScriptedChooser:
    def __init__(self, answers: Iterator[str]) -> None:
        self._answers = answers
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def __call__(self, message: str, **kwargs: Any) -> str:
        self.calls.append((message, kwargs))
        return next(self._answers)


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
        def model_dump(self, *, by_alias: bool) -> dict[str, object]:
            assert by_alias is True
            return {"workflow": "validate", "x-function": "echo"}

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
        "cli.yaml",
        "loadtest.yaml",
    ]
    assert loaded_paths == [tmp_path / "scenarios-v2" / "validate-container.yaml"]
    assert json.loads(str(frame_calls[0]["body"])) == {
        "workflow": "validate",
        "x-function": "echo",
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
    checked: list[str] = []

    def which(name: str) -> str | None:
        checked.append(name)
        return f"/usr/bin/{name}" if name in available else None

    monkeypatch.setattr(tui_app.shutil, "which", which)
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

    assert checked == ["docker", "ssh"]
    assert frame_calls == [
        {
            "title": "Doctor",
            "body": expected,
            "breadcrumb": "Main / Tools / Doctor",
            "footer_hint": "Press Enter to continue",
        }
    ]
    assert input_stream.read_calls == [1]
    assert console.calls == [("clear", None), ("print", frame), ("clear", None)]


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
    assert frame_calls[0]["body"] == "invalid scenario"
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
    assert frame_calls[0]["body"] == "preview failed"
    assert controller.calls == []
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
