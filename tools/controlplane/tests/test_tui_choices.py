from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

from rich.console import Console

from workflow_tasks import bind_workflow_sink
from controlplane_tool.e2e.e2e_runner import ScenarioPlanStep, ScenarioStepEvent
from controlplane_tool.core.models import (
    CliTestConfig,
    ControlPlaneConfig,
    LoadtestConfig,
    MetricsConfig,
    Profile,
    ScenarioSelectionConfig,
    TestsConfig,
)
from controlplane_tool.building.module_catalog import module_choices
from controlplane_tool.orchestation.prefect_models import FlowRunResult, LocalFlowDefinition
from controlplane_tool.tui import DEFAULT_REQUIRED_METRICS, build_profile_interactive
import controlplane_tool.tui.workflow_controller as tui_wfc
from controlplane_tool.tui.app import NanofaasTUI
from controlplane_tool.tui.workflow import TuiWorkflowSink, WorkflowDashboard
from controlplane_tool.tui.workflow_controller import TuiWorkflowController


def test_module_catalog_has_descriptions() -> None:
    choices = module_choices()
    assert choices
    for module in choices:
        assert module.name
        assert module.description


def test_profile_wizard_selectors_supply_descriptions_for_every_entry(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(["java", "native", "quick", "preset", "k3s-junit-curl", "demo-java", "cli-stack"])
    confirm_answers = iter([True, True, True, True, True, True])
    captured_selects: list[tuple[str, list[object]]] = []
    captured_checkboxes: list[tuple[str, list[object]]] = []

    def fake_select(*args, **kwargs):  # noqa: ANN001
        captured_selects.append((str(args[0]), list(kwargs["choices"])))
        return _Prompt(next(select_answers))

    def fake_checkbox(*args, **kwargs):  # noqa: ANN001
        captured_checkboxes.append((str(args[0]), list(kwargs["choices"])))
        return _Prompt(["autoscaler"])

    monkeypatch.setattr(tui.questionary, "select", fake_select)
    monkeypatch.setattr(tui.questionary, "checkbox", fake_checkbox)
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "text",
        lambda *args, **kwargs: _Prompt("word-stats-java,json-transform-java"),
    )

    build_profile_interactive(profile_name="wizard-demo")

    assert [message for message, _ in captured_selects] == [
        "Control plane implementation:",
        "Java building mode:",
        "Loadtest profile:",
        "Default E2E selection type:",
        "Base E2E scenario:",
        "Function preset:",
        "Default CLI validation scenario:",
    ]
    assert [message for message, _ in captured_checkboxes] == [
        "Select control-plane modules:",
    ]
    for _, choices in captured_selects + captured_checkboxes:
        descriptions = [getattr(choice, "description", None) for choice in choices]
        assert all(description and len(description) >= 48 for description in descriptions)


def test_profile_wizard_scenario_file_selector_supplies_descriptions(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(
        [
            "java",
            "native",
            "quick",
            "scenario-file",
            "tools/controlplane/scenarios/k8s-demo-java.toml",
        ]
    )
    confirm_answers = iter([True, True, True, True, True, False])
    captured_selects: list[tuple[str, list[object]]] = []

    def fake_select(*args, **kwargs):  # noqa: ANN001
        captured_selects.append((str(args[0]), list(kwargs["choices"])))
        return _Prompt(next(select_answers))

    monkeypatch.setattr(tui.questionary, "select", fake_select)
    monkeypatch.setattr(tui.questionary, "checkbox", lambda *args, **kwargs: _Prompt(["autoscaler"]))
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "text",
        lambda *args, **kwargs: _Prompt("word-stats-java,json-transform-java"),
    )

    build_profile_interactive(profile_name="wizard-file")

    scenario_file_choices = dict(captured_selects)["Scenario file:"]
    descriptions = [getattr(choice, "description", None) for choice in scenario_file_choices]
    assert all(description and len(description) >= 48 for description in descriptions)


def test_default_required_metrics_match_control_plane_metrics() -> None:
    assert "function_dispatch_total" in DEFAULT_REQUIRED_METRICS
    assert "function_success_total" in DEFAULT_REQUIRED_METRICS
    assert "function_warm_start_total" in DEFAULT_REQUIRED_METRICS
    assert "function_latency_ms" in DEFAULT_REQUIRED_METRICS
    assert "function_queue_wait_ms" in DEFAULT_REQUIRED_METRICS
    assert "function_e2e_latency_ms" in DEFAULT_REQUIRED_METRICS
    assert "function_cold_start_total" not in DEFAULT_REQUIRED_METRICS


class _Prompt:
    def __init__(self, value: object) -> None:
        self._value = value

    def ask(self) -> object:
        return self._value


def test_tui_no_longer_prompts_for_prometheus_url(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(["java", "native", "smoke"])
    confirm_answers = iter([True, True, True, True, False, False])
    text_calls: list[str] = []

    monkeypatch.setattr(
        tui.questionary,
        "select",
        lambda *args, **kwargs: _Prompt(next(select_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "checkbox",
        lambda *args, **kwargs: _Prompt(["autoscaler"]),
    )
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )

    def _record_text(*args, **kwargs):
        prompt = args[0] if args else ""
        text_calls.append(str(prompt))
        return _Prompt("")

    monkeypatch.setattr(tui.questionary, "text", _record_text)

    profile = build_profile_interactive(profile_name="dev")

    assert profile.tests.metrics is True
    assert profile.loadtest.default_load_profile == "smoke"
    assert profile.metrics.prometheus_url is None
    assert profile.metrics.strict_required is False
    assert text_calls == []


def test_tui_can_save_default_function_preset(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(["java", "native", "quick", "preset", "k3s-junit-curl", "demo-java"])
    confirm_answers = iter([True, True, True, True, True, False])

    monkeypatch.setattr(
        tui.questionary,
        "select",
        lambda *args, **kwargs: _Prompt(next(select_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "checkbox",
        lambda *args, **kwargs: _Prompt(["autoscaler"]),
    )
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "text",
        lambda *args, **kwargs: _Prompt("word-stats-java,json-transform-java"),
    )

    profile = build_profile_interactive(profile_name="demo-java")

    assert profile.scenario.function_preset == "demo-java"
    assert profile.scenario.base_scenario == "k3s-junit-curl"


def test_tui_can_save_default_cli_test_scenario(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(
        ["java", "native", "quick", "preset", "k3s-junit-curl", "demo-java", "cli-stack"]
    )
    confirm_answers = iter([True, True, True, True, True, True])

    monkeypatch.setattr(
        tui.questionary,
        "select",
        lambda *args, **kwargs: _Prompt(next(select_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "checkbox",
        lambda *args, **kwargs: _Prompt(["autoscaler"]),
    )
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "text",
        lambda *args, **kwargs: _Prompt("word-stats-java,json-transform-java"),
    )

    profile = build_profile_interactive(profile_name="demo-java")

    assert profile.cli_test.default_scenario == "cli-stack"


def test_tui_can_save_cli_stack_as_default_cli_test_scenario(monkeypatch) -> None:
    import controlplane_tool.tui as tui

    select_answers = iter(
        ["java", "native", "quick", "preset", "k3s-junit-curl", "demo-java", "cli-stack"]
    )
    confirm_answers = iter([True, True, True, True, True, True])

    monkeypatch.setattr(
        tui.questionary,
        "select",
        lambda *args, **kwargs: _Prompt(next(select_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "checkbox",
        lambda *args, **kwargs: _Prompt(["autoscaler"]),
    )
    monkeypatch.setattr(
        tui.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(confirm_answers)),
    )
    monkeypatch.setattr(
        tui.questionary,
        "text",
        lambda *args, **kwargs: _Prompt("word-stats-java,json-transform-java"),
    )

    profile = build_profile_interactive(profile_name="demo-java")

    assert profile.cli_test.default_scenario == "cli-stack"


def test_profile_view_shows_behavioral_defaults(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    profile = Profile(
        name="demo-java",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        modules=["autoscaler", "building-metadata"],
        tests=TestsConfig(
            enabled=True,
            api=True,
            e2e_mockk8s=False,
            metrics=True,
            load_profile="smoke",
        ),
        metrics=MetricsConfig(
            required=["function_dispatch_total", "function_latency_ms"],
            strict_required=True,
        ),
        scenario=ScenarioSelectionConfig(
            base_scenario="k3s-junit-curl",
            function_preset="demo-java",
            namespace="nanofaas-e2e",
            local_registry="localhost:5000",
        ),
        loadtest=LoadtestConfig(
            default_load_profile="smoke",
            metrics_gate_mode="warn",
            function_preset="demo-java",
        ),
        cli_test=CliTestConfig(default_scenario="cli-stack"),
    )
    recording_console = Console(record=True, width=160)

    monkeypatch.setattr(tui_app, "console", recording_console)

    tui_app._show_profile_table(profile)
    text = recording_console.export_text()

    assert "scenario.base_scenario" in text
    assert "cli_test.default_scenario" in text
    assert "loadtest.default_load_profile" in text
    assert "loadtest.metrics_gate_mode" in text
    assert "metrics.strict_required" in text
    assert "metrics.required" in text


def test_tui_cli_e2e_menu_offers_cli_stack_runner(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["cli-stack", "default"])
    captured: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        captured["title"] = title
        captured["summary_lines"] = summary_lines
        captured["planned_steps"] = planned_steps
        return None

    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert captured["title"] == "CLI E2E"
    assert captured["summary_lines"] == [
        "Runner: cli-stack",
        "Mode: canonical self-bootstrapping VM-backed CLI stack",
        "Selection: built-in default",
    ]
    assert "Build nanofaas-cli installDist in VM" in captured["planned_steps"]
    assert "Verify cli-stack status fails" in captured["planned_steps"]


def test_tui_cli_stack_default_selection_resolves_request_and_passes_it_to_flow(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["cli-stack", "default"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Build nanofaas-cli installDist in VM"),
            SimpleNamespace(summary="Verify cli-stack status fails"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(flow_id="e2e.cli_stack", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["summary_lines"] = summary_lines
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert called["scenario"] == "cli-stack"
    assert called["flow_id"] == "e2e.cli_stack"
    assert callable(called["event_listener"])
    assert called["request"].scenario == "cli-stack"
    assert called["request"].function_preset == "demo-java"
    assert called["request"].saved_profile is None
    assert called["request"].scenario_file is None
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-java",
        "json-transform-java",
    ]
    assert called["summary_lines"] == [
        "Runner: cli-stack",
        "Mode: canonical self-bootstrapping VM-backed CLI stack",
        "Selection: built-in default",
    ]
    assert called["planned_steps"] == [
        "Build nanofaas-cli installDist in VM",
        "Verify cli-stack status fails",
    ]


def test_tui_cli_stack_can_use_javascript_preset(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["cli-stack", "preset", "demo-javascript"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Build nanofaas-cli installDist in VM"),
            SimpleNamespace(summary="Verify cli-stack status fails"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.cli_stack", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert called["scenario"] == "cli-stack"
    assert called["request"].scenario == "cli-stack"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_cli_stack_can_use_saved_profile(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["cli-stack", "saved-profile", "demo-javascript"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Build nanofaas-cli installDist in VM"),
            SimpleNamespace(summary="Verify cli-stack status fails"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.cli_stack", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert called["scenario"] == "cli-stack"
    assert called["request"].scenario == "cli-stack"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].saved_profile == "demo-javascript"
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_cli_stack_can_use_scenario_file(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner
    from controlplane_tool.workspace.paths import resolve_workspace_path

    answers = iter(
        [
            "cli-stack",
            "scenario-file",
            "tools/controlplane/scenarios/k8s-demo-javascript.toml",
        ]
    )
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Build nanofaas-cli installDist in VM"),
            SimpleNamespace(summary="Verify cli-stack status fails"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.cli_stack", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert called["scenario"] == "cli-stack"
    assert called["request"].scenario == "cli-stack"
    assert called["request"].scenario_file == resolve_workspace_path(
        Path("tools/controlplane/scenarios/k8s-demo-javascript.toml")
    )
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_deploy_host_can_use_javascript_preset(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["preset", "demo-javascript"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.deploy_host", task_ids=["deploy-host.building"], run=lambda: "ok")

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["summary_lines"] = summary_lines
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_deploy_host()

    assert called["title"] == "E2E Scenarios"
    assert called["scenario"] == "deploy-host"
    assert called["flow_id"] == "e2e.deploy_host"
    assert called["request"].scenario == "deploy-host"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].saved_profile is None
    assert called["request"].scenario_file is None
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]
    assert called["summary_lines"] == [
        "Scenario: deploy-host",
        "Mode: host-side building/push/register compatibility path",
        "Function preset: demo-javascript",
    ]
    assert called["planned_steps"] == ["Build", "Deploy", "Verify"]


def test_tui_deploy_host_can_use_saved_profile(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["saved-profile", "demo-javascript"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.deploy_host", task_ids=["deploy-host.building"], run=lambda: "ok")

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["summary_lines"] = summary_lines
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_deploy_host()

    assert called["scenario"] == "deploy-host"
    assert called["flow_id"] == "e2e.deploy_host"
    assert called["request"].scenario == "deploy-host"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].saved_profile == "demo-javascript"
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]
    assert called["summary_lines"] == [
        "Scenario: deploy-host",
        "Mode: host-side building/push/register compatibility path",
        "Saved profile: demo-javascript",
    ]


def test_tui_deploy_host_can_use_scenario_file(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    from controlplane_tool.workspace.paths import resolve_workspace_path

    answers = iter(
        [
            "scenario-file",
            "tools/controlplane/scenarios/k8s-demo-javascript.toml",
        ]
    )
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.deploy_host", task_ids=["deploy-host.building"], run=lambda: "ok")

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_deploy_host()

    assert called["scenario"] == "deploy-host"
    assert called["flow_id"] == "e2e.deploy_host"
    assert called["request"].scenario == "deploy-host"
    assert called["request"].scenario_file == resolve_workspace_path(
        Path("tools/controlplane/scenarios/k8s-demo-javascript.toml")
    )
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_container_local_can_use_single_javascript_function(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["function", "word-stats-javascript"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(
            flow_id="e2e.container_local",
            task_ids=["container-local.building"],
            run=lambda: "ok",
        )

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["summary_lines"] = summary_lines
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_container_local()

    assert called["title"] == "E2E Scenarios"
    assert called["scenario"] == "container-local"
    assert called["flow_id"] == "e2e.container_local"
    assert called["request"].scenario == "container-local"
    assert called["request"].function_preset is None
    assert called["request"].functions == ["word-stats-javascript"]
    assert called["request"].saved_profile is None
    assert called["request"].scenario_file is None
    assert called["request"].resolved_scenario.function_keys == ["word-stats-javascript"]
    assert called["summary_lines"] == [
        "Scenario: container-local",
        "Mode: local managed DEPLOYMENT path",
        "Function: word-stats-javascript",
    ]
    assert called["planned_steps"] == ["Build", "Deploy", "Verify"]


def test_tui_container_local_can_use_compatible_scenario_file(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    from controlplane_tool.workspace.paths import resolve_workspace_path

    answers = iter(
        [
            "scenario-file",
            "tools/controlplane/scenarios/container-local-smoke.toml",
        ]
    )
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(
            flow_id="e2e.container_local",
            task_ids=["container-local.building"],
            run=lambda: "ok",
        )

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["summary_lines"] = summary_lines
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_container_local()

    assert called["scenario"] == "container-local"
    assert called["flow_id"] == "e2e.container_local"
    assert called["request"].scenario == "container-local"
    assert called["request"].scenario_file == resolve_workspace_path(
        Path("tools/controlplane/scenarios/container-local-smoke.toml")
    )
    assert called["request"].functions == ["word-stats-java"]
    assert called["request"].resolved_scenario.function_keys == ["word-stats-java"]
    assert called["summary_lines"] == [
        "Scenario: container-local",
        "Mode: local managed DEPLOYMENT path",
        "Scenario file: tools/controlplane/scenarios/container-local-smoke.toml",
    ]


def test_tui_container_local_warns_when_no_compatible_saved_profiles(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["saved-profile", "function", "word-stats-javascript"])
    warnings: list[str] = []
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))
    monkeypatch.setattr(tui_app, "warning", warnings.append)
    monkeypatch.setattr(tui_app, "saved_profile_choices", lambda target: [])

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(
            flow_id="e2e.container_local",
            task_ids=["container-local.building"],
            run=lambda: "ok",
        )

    def fake_run_shared_flow(self, flow, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow.flow_id
        called["result"] = flow.run()
        return called["result"]

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_shared_flow", fake_run_shared_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_container_local()

    assert warnings == ["No compatible saved profiles found for container-local."]
    assert called["scenario"] == "container-local"
    assert called["flow_id"] == "e2e.container_local"
    assert called["request"].functions == ["word-stats-javascript"]
    assert called["request"].resolved_scenario.function_keys == ["word-stats-javascript"]


def test_tui_cli_e2e_menu_describes_host_platform_as_compatibility_path(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["host-platform"])
    captured: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        captured["title"] = title
        captured["summary_lines"] = summary_lines
        captured["planned_steps"] = planned_steps
        return None

    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert captured["title"] == "CLI E2E"
    assert captured["summary_lines"] == [
        "Runner: host-platform",
        "Mode: compatibility path; platform-only on host vs cluster",
    ]


def test_tui_e2e_menu_marks_vm_scenarios_as_self_bootstrapping(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}
    answers = iter(["container-local", "back"])

    def fake_select(*args, **kwargs):  # noqa: ANN001
        captured["choices"] = [choice.title for choice in kwargs["choices"]]
        return _Prompt(next(answers))

    monkeypatch.setattr(tui_app.questionary, "select", fake_select)
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())
    monkeypatch.setattr(NanofaasTUI, "_run_container_local", lambda self: None)

    NanofaasTUI()._e2e_menu()

    assert "k3s-junit-curl — self-bootstrapping VM stack with curl + JUnit verification" in captured["choices"]
    assert "helm-stack — self-bootstrapping VM stack for Helm compatibility" in captured["choices"]
    assert "two-vm-loadtest — Helm stack with dedicated k6 load generator VM" in captured["choices"]


def test_tui_e2e_menu_routes_two_vm_loadtest_to_vm_runner(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["two-vm-loadtest", "back"])
    called: dict[str, object] = {}

    def fake_select(*args, **kwargs):  # noqa: ANN001
        return _Prompt(next(answers))

    monkeypatch.setattr(tui_app.questionary, "select", fake_select)
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())
    monkeypatch.setattr(
        NanofaasTUI,
        "_run_vm_e2e_scenario",
        lambda self, scenario: called.update({"scenario": scenario}),
    )

    NanofaasTUI()._e2e_menu()

    assert called["scenario"] == "two-vm-loadtest"


def test_tui_submenus_include_back_entries(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, list[object]] = {}

    def fake_select(message, **kwargs):  # noqa: ANN001
        captured[str(message)] = list(kwargs["choices"])
        return _Prompt("back")

    monkeypatch.setattr(tui_app.questionary, "select", fake_select)
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())

    NanofaasTUI()._build_menu()
    NanofaasTUI()._vm_menu()
    NanofaasTUI()._registry_menu()
    NanofaasTUI()._loadtest_menu()
    NanofaasTUI()._functions_menu()
    NanofaasTUI()._profile_menu()

    assert any(getattr(choice, "value", None) == "back" for choice in captured["Action:"])
    assert any(getattr(choice, "value", None) == "back" for choice in captured["View:"])


def test_tui_described_selectors_include_back_entries(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}

    def fake_described_select(message, choices, include_back=False):  # noqa: ANN001
        captured[message] = {"choices": choices, "include_back": include_back}
        return "back"

    monkeypatch.setattr(tui_app, "_select_described_value", fake_described_select)

    NanofaasTUI()._e2e_menu()
    NanofaasTUI()._cli_e2e_menu()

    assert captured["Scenario:"]["include_back"] is True
    assert captured["Runner:"]["include_back"] is True


def test_tui_back_selection_returns_before_followup_prompts(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    text_prompts: list[str] = []
    confirm_prompts: list[str] = []

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())
    monkeypatch.setattr(
        tui_app.questionary,
        "select",
        lambda *args, **kwargs: _Prompt("back"),
    )
    monkeypatch.setattr(
        tui_app.questionary,
        "text",
        lambda *args, **kwargs: text_prompts.append(str(args[0] if args else "")) or _Prompt(""),
    )
    monkeypatch.setattr(
        tui_app.questionary,
        "confirm",
        lambda *args, **kwargs: confirm_prompts.append(str(args[0] if args else "")) or _Prompt(False),
    )

    NanofaasTUI()._build_menu()
    NanofaasTUI()._vm_menu()
    NanofaasTUI()._loadtest_menu()
    NanofaasTUI()._functions_menu()
    NanofaasTUI()._profile_menu()

    assert text_prompts == []
    assert confirm_prompts == []


def test_tui_build_menu_logs_gradle_output_before_raising_on_nonzero_result(monkeypatch) -> None:
    import pytest
    import controlplane_tool.cli.commands as cli_commands
    import controlplane_tool.tui.app as tui_app

    answers = iter(["jar", "core"])
    log_lines: list[str] = []

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(answers))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: False)
    monkeypatch.setattr(
        cli_commands.GradleCommandExecutor,
        "execute",
        lambda self, **kwargs: SimpleNamespace(
            command=["./gradlew", ":control-plane:bootJar"],
            return_code=17,
            dry_run=False,
            stdout="gradle stdout",
            stderr="gradle stderr",
        ),
    )

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: log_lines.append(message))
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    with pytest.raises(RuntimeError, match="gradle stderr"):
        NanofaasTUI()._build_menu()

    assert "gradle stdout" in log_lines
    assert "gradle stderr" in log_lines


def _completed_flow_result(flow_id: str, result=None) -> FlowRunResult:
    now = datetime.now(UTC)
    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id="flow-run-1",
        orchestrator_backend="prefect-local",
        started_at=now,
        finished_at=now,
        result=result,
    )


def test_tui_vm_menu_runs_vm_flow_via_runtime(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import tui_toolkit.pickers as tui_widgets

    answers = iter(["provision-base", "multipass", "nanofaas-e2e", "ubuntu", False, False])
    called: dict[str, object] = {}

    _ask_fn = lambda prompt_fn: next(answers)  # noqa: E731
    monkeypatch.setattr(tui_app, "_ask", _ask_fn)
    monkeypatch.setattr(tui_widgets, "_ask", _ask_fn)

    def fake_build_vm_flow(flow_id, **kwargs):  # noqa: ANN001
        called["built_flow_id"] = flow_id
        called["dry_run"] = kwargs["dry_run"]
        return LocalFlowDefinition(flow_id=flow_id, task_ids=[flow_id], run=lambda: SimpleNamespace(stdout="", stderr="", return_code=0))

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["flow_result"] = flow()
        return _completed_flow_result(flow_id, called["flow_result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["planned_steps"] = planned_steps
        return action(SimpleNamespace(append_log=lambda message: None), SimpleNamespace(_update=lambda: None))

    monkeypatch.setattr(tui_app, "build_vm_flow", fake_build_vm_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._vm_menu()

    assert called["built_flow_id"] == "vm.provision_base"
    assert called["flow_id"] == "vm.provision_base"
    assert called["dry_run"] is False
    assert called["title"] == "VM Management"


def test_tui_vm_menu_raises_when_shared_flow_returns_nonzero_command_result(monkeypatch) -> None:
    import pytest
    import controlplane_tool.tui.app as tui_app
    import tui_toolkit.pickers as tui_widgets

    answers = iter(["up", "multipass", "nanofaas-e2e", "ubuntu", False])

    _ask_fn = lambda prompt_fn: next(answers)  # noqa: E731
    monkeypatch.setattr(tui_app, "_ask", _ask_fn)
    monkeypatch.setattr(tui_widgets, "_ask", _ask_fn)

    def fake_build_vm_flow(flow_id, **kwargs):  # noqa: ANN001
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=[flow_id],
            run=lambda: SimpleNamespace(stdout="", stderr="vm failed", return_code=17),
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        return _completed_flow_result(flow_id, flow())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_vm_flow", fake_build_vm_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    with pytest.raises(RuntimeError, match="vm failed"):
        NanofaasTUI()._vm_menu()


def test_tui_vm_menu_logs_stdout_stderr_before_raising_on_nonzero_result(monkeypatch) -> None:
    import pytest
    import controlplane_tool.tui.app as tui_app
    import tui_toolkit.pickers as tui_widgets

    answers = iter(["up", "multipass", "nanofaas-e2e", "ubuntu", False])
    log_lines: list[str] = []

    _ask_fn = lambda prompt_fn: next(answers)  # noqa: E731
    monkeypatch.setattr(tui_app, "_ask", _ask_fn)
    monkeypatch.setattr(tui_widgets, "_ask", _ask_fn)

    def fake_build_vm_flow(flow_id, **kwargs):  # noqa: ANN001
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=[flow_id],
            run=lambda: SimpleNamespace(stdout="vm stdout", stderr="vm stderr", return_code=17),
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        return _completed_flow_result(flow_id, flow())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: log_lines.append(message))
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_vm_flow", fake_build_vm_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    with pytest.raises(RuntimeError, match="vm stderr"):
        NanofaasTUI()._vm_menu()

    assert "vm stdout" in log_lines
    assert "vm stderr" in log_lines


def test_tui_main_menu_no_longer_includes_registry_entry() -> None:
    assert not any(
        choice.value == "registry"
        for choice in NanofaasTUI._MAIN_MENU
        if hasattr(choice, "value")
    )


def test_environment_menu_contains_vm_and_registry(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["include_back"] = include_back
        return "back"

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)

    NanofaasTUI()._environment_menu()

    assert [choice.value for choice in captured["choices"]] == ["vm", "registry"]
    assert captured["include_back"] is True


def test_environment_menu_entries_have_helpful_descriptions(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        return "back"

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)

    NanofaasTUI()._environment_menu()

    descriptions = [choice.description for choice in captured["choices"]]
    assert captured["message"] == "Action:"
    assert all(description and len(description) >= 48 for description in descriptions)


def test_primary_tui_menus_supply_descriptions_for_every_entry(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: list[tuple[str, list[object]]] = []

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return "back"

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)

    app = NanofaasTUI()
    app._build_menu()
    app._environment_menu()
    app._validation_menu()
    app._vm_menu()
    app._registry_menu()
    app._loadtest_menu()
    app._functions_menu()
    app._profile_menu()

    assert [message for message, _ in captured] == [
        "Action:",
        "Action:",
        "Action:",
        "Action:",
        "Action:",
        "Action:",
        "View:",
        "Action:",
    ]
    for _, choices in captured:
        descriptions = [getattr(choice, "description", None) for choice in choices]
        assert all(description and len(description) >= 48 for description in descriptions)


def test_loadtest_tui_descriptions_explain_mock_fixture_execution(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    primary = {choice.value: choice.description for choice in tui_app._MAIN_MENU_CHOICES}
    actions = {choice.value: choice.description for choice in tui_app._LOADTEST_ACTION_CHOICES}

    assert "mock Kubernetes API" in primary["loadtest"]
    assert "LOCAL fixture functions" in actions["run"]
    assert "not Kubernetes pods" in actions["run"]
    assert "mock Kubernetes API" in actions["plan"]
    assert "not Kubernetes pods" in actions["plan"]

    monkeypatch.setattr(tui_app, "load_profile", lambda name: (_ for _ in ()).throw(RuntimeError))
    saved_description = tui_app._saved_profile_description("demo-javascript")
    assert "mock Kubernetes API" in saved_description
    assert "LOCAL fixture functions" in saved_description


def test_followup_tui_selectors_supply_descriptions_for_every_entry(monkeypatch) -> None:
    import controlplane_tool.workspace.profiles as profiles
    import controlplane_tool.tui.app as tui_app

    captured: list[tuple[str, list[object]]] = []
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: True)
    monkeypatch.setattr(profiles, "list_profiles", lambda root=None: ["demo-java"])

    app = NanofaasTUI()

    build_answers = iter(["jar", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(build_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._build_menu()

    vm_answers = iter(["up", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(vm_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._vm_menu()

    loadtest_answers = iter(["run", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(loadtest_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._loadtest_menu()

    functions_answers = iter(["show", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(functions_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._functions_menu()

    profile_show_answers = iter(["show", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(profile_show_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._profile_menu()

    profile_delete_answers = iter(["delete", "back", "back"])

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured.append((message, list(choices)))
        return next(profile_delete_answers)

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)
    app._profile_menu()

    messages = [message for message, _ in captured]
    assert messages.count("Profile:") >= 2
    assert "Lifecycle:" in messages
    assert "Function:" in messages
    assert "Profile to delete:" in messages
    for _, choices in captured:
        descriptions = [getattr(choice, "description", None) for choice in choices]
        assert all(description and len(description) >= 48 for description in descriptions)


def test_validation_menu_contains_platform_cli_and_host_paths(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["include_back"] = include_back
        return "back"

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)

    NanofaasTUI()._validation_menu()

    assert [choice.value for choice in captured["choices"]] == ["platform", "cli", "host"]
    assert captured["include_back"] is True


def test_validation_menu_routes_host_path_to_deploy_host(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    calls: list[str] = []

    answers = iter(["host", "back"])

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(answers))
    monkeypatch.setattr(NanofaasTUI, "_run_deploy_host", lambda self: calls.append("deploy-host"))

    NanofaasTUI()._validation_menu()

    assert calls == ["deploy-host"]


def test_tui_main_menu_uses_shared_picker(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    captured: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "header", lambda: None)
    monkeypatch.setattr(tui_app.console, "print", lambda *args, **kwargs: None)

    def fake_select_value(message, *, choices, default=None, include_back=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["default"] = default
        captured["include_back"] = include_back
        return "exit"

    monkeypatch.setattr(tui_app, "_select_value", fake_select_value)

    NanofaasTUI().run()

    assert captured["message"] == "What would you like to do?"
    assert any(getattr(choice, "value", None) == "building" for choice in captured["choices"])
    assert captured["include_back"] is False


def test_tui_function_catalog_waits_for_acknowledge_after_static_views(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["all", "presets", "show", "word-stats-java", "back"])
    acknowledgements: list[str] = []

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(answers))
    monkeypatch.setattr(tui_app.console, "print", lambda *args, **kwargs: None)

    def fake_press_any_key_to_continue(message, style=None):  # noqa: ANN001
        acknowledgements.append(message)
        return _Prompt(True)

    monkeypatch.setattr(
        tui_app.questionary,
        "press_any_key_to_continue",
        fake_press_any_key_to_continue,
    )

    app = NanofaasTUI()
    app._functions_menu()

    assert acknowledgements == [
        "Press any key to return to the catalog.",
        "Press any key to return to the catalog.",
        "Press any key to return to the catalog.",
    ]


def test_tui_function_catalog_lists_dynamic_functions(monkeypatch, capsys) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["all", "back"])

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(answers))
    monkeypatch.setattr(tui_app, "_acknowledge_static_view", lambda *args, **kwargs: None)

    NanofaasTUI()._functions_menu()

    assert "roman-numeral-go" in capsys.readouterr().out


def test_tui_function_details_show_dynamic_metadata(monkeypatch, capsys) -> None:
    import controlplane_tool.tui.app as tui_app

    answers = iter(["show", "roman-numeral-go", "back"])

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(answers))
    monkeypatch.setattr(tui_app, "_acknowledge_static_view", lambda *args, **kwargs: None)

    NanofaasTUI()._functions_menu()

    output = capsys.readouterr().out
    assert "roman-numeral-go" in output
    assert "Go roman numeral conversion demo." in output
    assert "localhost:5000/nanofaas/go-roman-numeral:e2e" in output
    assert "examples/go/roman-numeral" in output


def test_tui_other_static_views_wait_for_acknowledge(monkeypatch, tmp_path: Path) -> None:
    import controlplane_tool.cli.commands as cli_commands
    import controlplane_tool.workspace.profiles as profiles
    import controlplane_tool.tui.app as tui_app

    acknowledgements: list[str] = []
    generic_message = "Press any key to return to the previous menu."

    monkeypatch.setattr(
        tui_app,
        "_acknowledge_static_view",
        lambda message=generic_message: acknowledgements.append(message),
    )
    monkeypatch.setattr(tui_app.console, "print", lambda *args, **kwargs: None)
    monkeypatch.setattr(tui_app, "step", lambda *args, **kwargs: None)
    monkeypatch.setattr(tui_app, "success", lambda *args, **kwargs: None)
    monkeypatch.setattr(tui_app, "warning", lambda *args, **kwargs: None)

    build_selects = iter(["building", "core", "back"])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(build_selects))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: True)
    monkeypatch.setattr(
        cli_commands.GradleCommandExecutor,
        "execute",
        lambda self, **kwargs: SimpleNamespace(command=["./gradlew", "building"]),
    )
    NanofaasTUI()._build_menu()

    vm_selects = iter(["up", "multipass", "back"])
    vm_asks = iter(["nanofaas-e2e", "ubuntu", True])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(vm_selects))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(vm_asks))
    monkeypatch.setattr(tui_app, "build_vm_flow", lambda *args, **kwargs: object())
    monkeypatch.setattr(
        TuiWorkflowController,
        "run_shared_flow",
        lambda self, flow, allow_none_result=False, on_result=None: SimpleNamespace(
            command=["controlplane-tool", "vm", "up"]
        ),
    )
    NanofaasTUI()._vm_menu()

    loadtest_selects = iter(["plan", "demo-java", "back"])
    loadtest_asks = iter([True])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(loadtest_selects))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(loadtest_asks))
    monkeypatch.setattr(tui_app, "list_profiles", lambda: ["demo-java"])
    monkeypatch.setattr(tui_app, "load_profile", lambda name: SimpleNamespace(name=name))
    monkeypatch.setattr(
        tui_app,
        "build_loadtest_request",
        lambda profile: SimpleNamespace(
            load_profile="quick",
            scenario="k3s-junit-curl",
            metrics_gate="warn",
            runs_root=Path("/tmp/loadtest"),
        ),
    )
    NanofaasTUI()._loadtest_menu()

    monkeypatch.setattr(profiles, "list_profiles", lambda root=None: ["demo-java"])
    monkeypatch.setattr(
        profiles,
        "load_profile",
        lambda name, root=None: SimpleNamespace(
            name=name,
            control_plane=None,
            modules=[],
            tests=None,
            scenario=None,
            cli_test=None,
            loadtest=None,
            metrics=None,
        ),
    )

    profile_show_selects = iter(["show", "demo-java", "back"])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(profile_show_selects))
    NanofaasTUI()._profile_menu()

    profile_new_selects = iter(["new", "back"])
    profile_new_asks = iter(["demo-java"])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(profile_new_selects))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(profile_new_asks))
    monkeypatch.setattr(
        NanofaasTUI,
        "_build_profile_interactive",
        lambda self, name: SimpleNamespace(name=name),
    )
    monkeypatch.setattr(
        profiles,
        "save_profile",
        lambda profile, root=None, prefect=None: tmp_path / f"{profile.name}.toml",
    )
    NanofaasTUI()._profile_menu()

    monkeypatch.setattr(profiles, "list_profiles", lambda root=None: [])
    profile_delete_empty_selects = iter(["delete", "back"])
    monkeypatch.setattr(
        tui_app,
        "_select_value",
        lambda *args, **kwargs: next(profile_delete_empty_selects),
    )
    NanofaasTUI()._profile_menu()

    monkeypatch.setattr(profiles, "list_profiles", lambda root=None: ["demo-java"])
    monkeypatch.setattr(
        profiles,
        "profile_path",
        lambda name, root=None: tmp_path / f"{name}.toml",
    )
    profile_delete_selects = iter(["delete", "demo-java", "back"])
    profile_delete_asks = iter([True])
    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(profile_delete_selects))
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(profile_delete_asks))
    NanofaasTUI()._profile_menu()

    assert acknowledgements == [
        generic_message,
        generic_message,
        generic_message,
        generic_message,
        generic_message,
        generic_message,
        generic_message,
    ]


def test_k3s_scenario_file_choices_only_return_compatible_manifests(monkeypatch) -> None:
    import controlplane_tool.tui.selection as selection
    import controlplane_tool.tui.app as tui_app

    fake_paths = SimpleNamespace(
        workspace_root=Path("/repo"),
        scenarios_dir=Path("/repo/tools/controlplane/scenarios"),
    )

    monkeypatch.setattr(tui_app, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(
        Path,
        "glob",
        lambda self, pattern: iter(
            [
                fake_paths.scenarios_dir / "k8s-demo-javascript.toml",
                fake_paths.scenarios_dir / "k8s-demo-all.toml",
                fake_paths.scenarios_dir / "broken.toml",
            ]
        ),
    )

    def fake_load(path: Path):  # noqa: ANN001
        if path.name == "k8s-demo-javascript.toml":
            return SimpleNamespace(
                base_scenario="k3s-junit-curl",
                name="k8s-demo-javascript",
                function_keys=["word-stats-javascript", "json-transform-javascript"],
                functions=[
                    SimpleNamespace(
                        key="word-stats-javascript",
                        runtime="javascript",
                        image="localhost:5000/nanofaas/javascript-word-stats:e2e",
                        example_dir=Path("examples/javascript/word-stats"),
                    ),
                    SimpleNamespace(
                        key="json-transform-javascript",
                        runtime="javascript",
                        image="localhost:5000/nanofaas/javascript-json-transform:e2e",
                        example_dir=Path("examples/javascript/json-transform"),
                    ),
                ],
            )
        if path.name == "k8s-demo-all.toml":
            return SimpleNamespace(
                base_scenario="helm-stack",
                name="k8s-demo-all",
                function_keys=["word-stats-java"],
                functions=[
                    SimpleNamespace(
                        key="word-stats-java",
                        runtime="java",
                        image="localhost:5000/nanofaas/java-word-stats:e2e",
                        example_dir=Path("examples/java/word-stats"),
                    ),
                ],
            )
        raise ValueError("invalid manifest")

    monkeypatch.setattr(selection, "load_scenario_file", fake_load)
    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)

    values = [
        choice.value
        for choice in tui_app.scenario_file_choices(tui_app.K3S_SELECTION_TARGET)
    ]
    assert values == ["tools/controlplane/scenarios/k8s-demo-javascript.toml"]


def test_k3s_saved_profile_choices_only_return_compatible_profiles(monkeypatch) -> None:
    import controlplane_tool.tui.selection as selection
    import controlplane_tool.tui.app as tui_app

    monkeypatch.setattr(
        selection,
        "list_profiles",
        lambda: ["demo-javascript", "fixture-only", "generic"],
    )

    def fake_load_profile(name: str):  # noqa: ANN001
        if name == "demo-javascript":
            return SimpleNamespace(
                scenario=SimpleNamespace(
                    base_scenario="k3s-junit-curl",
                    function_preset="demo-javascript",
                    functions=[],
                    scenario_file=None,
                )
            )
        if name == "fixture-only":
            return SimpleNamespace(
                scenario=SimpleNamespace(
                    base_scenario="k3s-junit-curl",
                    function_preset="metrics-smoke",
                    functions=[],
                    scenario_file=None,
                )
            )
        return SimpleNamespace(
            scenario=SimpleNamespace(
                base_scenario=None,
                function_preset=None,
                functions=[],
                scenario_file=None,
            )
        )

    monkeypatch.setattr(selection, "load_profile", fake_load_profile)

    values = [
        choice.value
        for choice in tui_app.saved_profile_choices(tui_app.K3S_SELECTION_TARGET)
    ]
    assert values == ["demo-javascript"]


def test_tui_k3s_junit_curl_dry_run_plan_waits_for_acknowledge(monkeypatch) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands
    import controlplane_tool.e2e.e2e_runner as e2e_runner
    import controlplane_tool.tui.app as tui_app

    acknowledgements: list[str] = []
    answers = iter(["nanofaas-e2e", "java", True, "default", True])

    monkeypatch.setattr(
        tui_app,
        "_acknowledge_static_view",
        lambda message="Press any key to return to the previous menu.": acknowledgements.append(message),
    )
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))
    monkeypatch.setattr(tui_app, "step", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        e2e_commands,
        "_resolve_run_request",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class _FakePlan:
        steps = [
            SimpleNamespace(name="Ensure VM is running", status="pending"),
            SimpleNamespace(name="Run k3s-junit-curl verification", status="pending"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert acknowledgements == ["Press any key to return to the previous menu."]


def test_platform_validation_menu_returns_to_scenario_picker_after_dry_run(monkeypatch) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands
    import controlplane_tool.e2e.e2e_runner as e2e_runner
    import controlplane_tool.tui.app as tui_app

    scenario_answers = iter(["k3s-junit-curl", "back"])
    selection_source_answers = iter(["default"])
    ask_answers = iter(["nanofaas-e2e", "java", True, True])
    prompts: list[str] = []

    def fake_select_described_value(message, choices, include_back=False):  # noqa: ANN001
        prompts.append(message)
        if message == "Scenario:":
            return next(scenario_answers)
        return next(selection_source_answers)

    monkeypatch.setattr(tui_app, "_select_described_value", fake_select_described_value)
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())
    monkeypatch.setattr(
        tui_app.questionary,
        "text",
        lambda *args, **kwargs: _Prompt(next(ask_answers)),
    )
    monkeypatch.setattr(
        tui_app.questionary,
        "select",
        lambda *args, **kwargs: _Prompt(next(ask_answers)),
    )
    monkeypatch.setattr(
        tui_app.questionary,
        "confirm",
        lambda *args, **kwargs: _Prompt(next(ask_answers)),
    )
    monkeypatch.setattr(
        tui_app,
        "_acknowledge_static_view",
        lambda message="Press any key to return to the previous menu.": None,
    )
    monkeypatch.setattr(tui_app, "step", lambda *args, **kwargs: None)
    monkeypatch.setattr(
        e2e_commands,
        "_resolve_run_request",
        lambda **kwargs: SimpleNamespace(**kwargs),
    )

    class _FakePlan:
        steps = [SimpleNamespace(name="Ensure VM is running", status="pending")]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    NanofaasTUI()._platform_validation_menu()

    assert prompts == ["Scenario:", "Selection source:", "Scenario:"]


def test_tui_registry_menu_starts_local_registry(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import tui_toolkit.pickers as tui_widgets
    from controlplane_tool.infra.runtimes import default_registry_url

    answers = iter(["start"])
    called: dict[str, object] = {}

    _ask_fn = lambda prompt_fn: next(answers)  # noqa: E731
    monkeypatch.setattr(tui_app, "_ask", _ask_fn)
    monkeypatch.setattr(tui_widgets, "_ask", _ask_fn)
    monkeypatch.setattr(tui_app, "ensure_local_registry", lambda **kwargs: called.update(kwargs) or object())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["planned_steps"] = planned_steps
        return action(SimpleNamespace(append_log=lambda message: None), SimpleNamespace(_update=lambda: None))

    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._registry_menu()

    assert called["title"] == "Registry"
    assert called["registry"] == default_registry_url()


def test_tui_loadtest_menu_runs_shared_loadtest_flow_via_runtime(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import tui_toolkit.pickers as tui_widgets

    called: dict[str, object] = {}

    _ask_fn = lambda prompt_fn: "run"  # noqa: E731
    monkeypatch.setattr(tui_app, "_ask", _ask_fn)
    monkeypatch.setattr(tui_widgets, "_ask", _ask_fn)
    monkeypatch.setattr(tui_app, "list_profiles", lambda: [])
    monkeypatch.setattr(NanofaasTUI, "_build_profile_interactive", lambda self, name: SimpleNamespace(name=name))
    monkeypatch.setattr(
        tui_app,
        "build_loadtest_request",
        lambda profile: SimpleNamespace(
            name="demo-loadtest",
            profile=SimpleNamespace(name="default"),
            scenario=SimpleNamespace(name="k3s-junit-curl"),
            load_profile=SimpleNamespace(name="quick"),
        ),
    )

    run_result = SimpleNamespace(
        final_status="passed",
        run_dir=Path("/tmp/loadtest-run"),
    )

    def fake_build_loadtest_flow(load_profile_name, **kwargs):  # noqa: ANN001
        called["built_flow_id"] = f"loadtest.{load_profile_name}"
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(
            flow_id=f"loadtest.{load_profile_name}",
            task_ids=["loadtest.bootstrap"],
            run=lambda: run_result,
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        return _completed_flow_result(flow_id, flow())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_loadtest_flow", fake_build_loadtest_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._loadtest_menu()

    assert called["built_flow_id"] == "loadtest.quick"
    assert called["flow_id"] == "loadtest.quick"
    assert called["title"] == "Load Testing"


def test_tui_k3s_junit_curl_scenario_runs_shared_flow_not_direct_execute(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["nanofaas-e2e", "java", True, "default", False])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())
    monkeypatch.setattr(
        e2e_runner.E2eRunner,
        "execute",
        lambda self, plan, event_listener=None: (_ for _ in ()).throw(AssertionError("direct execute must not be called")),
    )

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert called["scenario"] == "k3s-junit-curl"
    assert called["flow_id"] == "e2e.k3s_junit_curl"
    assert callable(called["event_listener"])
    assert called["request"].cleanup_vm is True
    assert called["request"].function_preset == "demo-java"
    assert called["request"].scenario_file is None
    assert called["request"].saved_profile is None
    assert called["request"].scenario_source == "built-in default"
    assert called["request"].resolved_scenario is not None
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-java",
        "json-transform-java",
    ]
    assert called["planned_steps"] == ["Ensure VM is running", "Run k3s-junit-curl verification"]


def test_tui_k3s_junit_curl_scenario_can_use_javascript_preset(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["nanofaas-e2e", "java", True, "preset", "demo-javascript", False])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert called["scenario"] == "k3s-junit-curl"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].scenario_file is None
    assert called["request"].saved_profile is None
    assert called["request"].scenario_source == "explicit CLI override"
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_k3s_junit_curl_scenario_can_use_scenario_file(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner
    from controlplane_tool.workspace.paths import resolve_workspace_path

    answers = iter(
        [
            "nanofaas-e2e",
            "java",
            True,
            "scenario-file",
            "tools/controlplane/scenarios/k8s-demo-javascript.toml",
            False,
        ]
    )
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert called["scenario"] == "k3s-junit-curl"
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].saved_profile is None
    assert called["request"].scenario_file == resolve_workspace_path(
        Path("tools/controlplane/scenarios/k8s-demo-javascript.toml")
    )
    assert "scenario file:" in called["request"].scenario_source.lower()
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_k3s_junit_curl_scenario_can_use_saved_profile(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(["nanofaas-e2e", "java", True, "saved-profile", "demo-javascript", False])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert called["scenario"] == "k3s-junit-curl"
    assert called["request"].saved_profile == "demo-javascript"
    assert called["request"].scenario_file is None
    assert called["request"].function_preset == "demo-javascript"
    assert called["request"].scenario_source == "saved profile: demo-javascript"
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_tui_k3s_junit_curl_warns_when_no_compatible_scenario_files(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(
        [
            "nanofaas-e2e",
            "java",
            True,
            "scenario-file",
            "preset",
            "demo-javascript",
            False,
        ]
    )
    warnings: list[str] = []
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))
    monkeypatch.setattr(tui_app, "warning", warnings.append)
    monkeypatch.setattr(tui_app, "scenario_file_choices", lambda target: [])

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert warnings == ["No compatible scenario files found for k3s-junit-curl."]
    assert called["request"].function_preset == "demo-javascript"


def test_tui_k3s_junit_curl_warns_when_no_compatible_saved_profiles(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    answers = iter(
        [
            "nanofaas-e2e",
            "java",
            True,
            "saved-profile",
            "preset",
            "demo-javascript",
            False,
        ]
    )
    warnings: list[str] = []
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))
    monkeypatch.setattr(tui_app, "warning", warnings.append)
    monkeypatch.setattr(tui_app, "saved_profile_choices", lambda target: [])

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.k3s_junit_curl", task_ids=["vm.ensure_running"], run=lambda: "ok")

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    assert warnings == ["No compatible saved profiles found for k3s-junit-curl."]
    assert called["request"].function_preset == "demo-javascript"


def test_tui_helm_stack_scenario_shows_shared_execution_phases(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    called: dict[str, object] = {}

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Provision base VM dependencies"),
            SimpleNamespace(summary="Sync project to VM"),
            SimpleNamespace(summary="Ensure registry container"),
            SimpleNamespace(summary="Build control-plane and runtime images in VM"),
            SimpleNamespace(summary="Build selected function images in VM"),
            SimpleNamespace(summary="Install k3s"),
            SimpleNamespace(summary="Configure k3s registry"),
            SimpleNamespace(summary="Install namespace Helm release"),
            SimpleNamespace(summary="Deploy control-plane via Helm"),
            SimpleNamespace(summary="Deploy function-runtime via Helm"),
            SimpleNamespace(summary="Install k6 for load testing"),
            SimpleNamespace(summary="Run k6 loadtest via controlplane runner"),
            SimpleNamespace(summary="Run autoscaling experiment (Python)"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        called["event_listener"] = kwargs.get("event_listener")
        return LocalFlowDefinition(
            flow_id="e2e.helm_stack",
            task_ids=["vm.ensure_running", "loadtest.run"],
            run=lambda: "ok",
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        called["result"] = flow()
        return _completed_flow_result(flow_id, called["result"])

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["summary_lines"] = summary_lines
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("helm-stack")

    assert called["scenario"] == "helm-stack"
    assert called["flow_id"] == "e2e.helm_stack"
    assert callable(called["event_listener"])
    assert called["summary_lines"] == [
        "Scenario: helm-stack",
        "Mode: self-bootstrapping VM-backed scenario",
    ]
    assert called["planned_steps"] == [
        "Ensure VM is running",
        "Provision base VM dependencies",
        "Sync project to VM",
        "Ensure registry container",
        "Build control-plane and runtime images in VM",
        "Build selected function images in VM",
        "Install k3s",
        "Configure k3s registry",
        "Install namespace Helm release",
        "Deploy control-plane via Helm",
        "Deploy function-runtime via Helm",
        "Install k6 for load testing",
        "Run k6 loadtest via controlplane runner",
        "Run autoscaling experiment (Python)",
    ]


def test_tui_helm_stack_scenario_does_not_add_wrapper_steps_to_dashboard(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    captured: dict[str, object] = {}

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Ensure VM is running"),
            SimpleNamespace(summary="Provision base VM dependencies"),
            SimpleNamespace(summary="Sync project to VM"),
            SimpleNamespace(summary="Ensure registry container"),
            SimpleNamespace(summary="Build control-plane and runtime images in VM"),
            SimpleNamespace(summary="Build selected function images in VM"),
            SimpleNamespace(summary="Install k3s"),
            SimpleNamespace(summary="Configure k3s registry"),
            SimpleNamespace(summary="Install namespace Helm release"),
            SimpleNamespace(summary="Deploy control-plane via Helm"),
            SimpleNamespace(summary="Deploy function-runtime via Helm"),
            SimpleNamespace(summary="Install k6 for load testing"),
            SimpleNamespace(summary="Run k6 loadtest via controlplane runner"),
            SimpleNamespace(summary="Run autoscaling experiment (Python)"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        event_listener = kwargs["event_listener"]

        def _run() -> str:
            step = ScenarioPlanStep(summary="Ensure VM is running", command=["echo", "noop"])
            event_listener(
                ScenarioStepEvent(
                    step_index=1,
                    total_steps=14,
                    step=step,
                    status="running",
                )
            )
            event_listener(
                ScenarioStepEvent(
                    step_index=1,
                    total_steps=14,
                    step=step,
                    status="success",
                )
            )
            return "ok"

        return LocalFlowDefinition(
            flow_id="e2e.helm_stack",
            task_ids=["vm.ensure_running"],
            run=_run,
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        return _completed_flow_result(flow_id, flow())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        dashboard = WorkflowDashboard(
            title=title,
            summary_lines=summary_lines,
            planned_steps=planned_steps,
        )
        sink = TuiWorkflowSink(dashboard)
        with bind_workflow_sink(sink):
            action(dashboard, sink)
        captured["steps"] = [(step.label, step.state) for step in dashboard.steps]

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("helm-stack")

    assert captured["steps"] == [
        ("Ensure VM is running", "success"),
        ("Provision base VM dependencies", "pending"),
        ("Sync project to VM", "pending"),
        ("Ensure registry container", "pending"),
        ("Build control-plane and runtime images in VM", "pending"),
        ("Build selected function images in VM", "pending"),
        ("Install k3s", "pending"),
        ("Configure k3s registry", "pending"),
        ("Install namespace Helm release", "pending"),
        ("Deploy control-plane via Helm", "pending"),
        ("Deploy function-runtime via Helm", "pending"),
        ("Install k6 for load testing", "pending"),
        ("Run k6 loadtest via controlplane runner", "pending"),
        ("Run autoscaling experiment (Python)", "pending"),
    ]


def test_tui_k3s_junit_curl_marks_nested_verify_steps_success_when_flow_completes(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner
    from workflow_tasks import phase, step
    from rich.console import Console
    import re

    captured: dict[str, object] = {}

    answers = iter(["nanofaas-e2e", "java", True, "default", False])
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    class _FakePlan:
        steps = [
            SimpleNamespace(summary="Run k3s-junit-curl verification"),
        ]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        event_listener = kwargs["event_listener"]

        def _run() -> str:
            step_meta = ScenarioPlanStep(
                summary="Run k3s-junit-curl verification",
                command=["python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack"],
            )
            event_listener(
                ScenarioStepEvent(
                    step_index=1,
                    total_steps=1,
                    step=step_meta,
                    status="running",
                )
            )
            phase("Verify")
            step("Verifying control-plane health")
            step("Verifying Prometheus metrics")
            event_listener(
                ScenarioStepEvent(
                    step_index=1,
                    total_steps=1,
                    step=step_meta,
                    status="success",
                )
            )
            return "ok"

        return LocalFlowDefinition(
            flow_id="e2e.k3s_junit_curl",
            task_ids=["tests.run_k3s_curl_checks"],
            run=_run,
        )

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        return _completed_flow_result(flow_id, flow())

    class _FakeLive:
        def __init__(self, renderable, **kwargs):  # noqa: ANN001
            self.renderable = renderable

        def __enter__(self):
            captured["live"] = self
            return self

        def __exit__(self, exc_type, exc, tb):  # noqa: ANN001
            return False

        def update(self, renderable, refresh=False):  # noqa: ANN001
            self.renderable = renderable

    class _FakeKeyListener:
        def __init__(self, *args, **kwargs):  # noqa: ANN001
            pass

        def start(self) -> None:
            return None

        def stop(self) -> None:
            return None

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(tui_wfc, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(tui_wfc, "Live", _FakeLive)
    monkeypatch.setattr(tui_wfc, "WorkflowKeyListener", _FakeKeyListener)

    NanofaasTUI()._run_vm_e2e_scenario("k3s-junit-curl")

    console = Console(record=True, width=140)
    console.print(captured["live"].renderable)
    text = console.export_text()
    phase_labels = [
        re.sub(r"\s+\d+\.\d+s$", "", match.group(1)).strip()
        for line in text.splitlines()
        for match in [re.search(r"\d+\.\s+(.*?)\s+\d+\.\d+s", line)]
        if match is not None
    ]

    assert phase_labels == ["Run k3s-junit-curl verification"]


def test_tui_run_live_workflow_does_not_force_complete_running_steps(monkeypatch) -> None:
    class _FakeLive:
        def __init__(self, renderable, **kwargs):  # noqa: ANN001
            self.renderable = renderable

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):  # noqa: ANN001
            return False

        def update(self, renderable, refresh=False):  # noqa: ANN001
            self.renderable = renderable

    class _FakeKeyListener:
        def __init__(self, *args, **kwargs):  # noqa: ANN001
            pass

        def start(self) -> None:
            return None

        def stop(self) -> None:
            return None

    def fake_action(dashboard, sink):  # noqa: ANN001
        dashboard.upsert_step("Run k3s-junit-curl verification", activate=True)
        return "ok"

    def fail_complete_running_steps(self, *args, **kwargs):  # noqa: ANN001
        raise AssertionError("dashboard.complete_running_steps() should not be called")

    monkeypatch.setattr(tui_wfc, "Live", _FakeLive)
    monkeypatch.setattr(tui_wfc, "WorkflowKeyListener", _FakeKeyListener)
    monkeypatch.setattr(WorkflowDashboard, "complete_running_steps", fail_complete_running_steps)

    result = NanofaasTUI()._controller.run_live_workflow(
        title="E2E Scenarios",
        summary_lines=["Scenario: k3s-junit-curl"],
        planned_steps=["Run k3s-junit-curl verification"],
        action=fake_action,
    )

    assert result == "ok"

def test_apply_e2e_step_event_failure_keeps_error_out_of_step_detail() -> None:
    dashboard = WorkflowDashboard(
        title="E2E Scenarios",
        summary_lines=["Scenario: helm-stack"],
        planned_steps=["Run autoscaling experiment (Python)"],
    )
    event = ScenarioStepEvent(
        step_index=1,
        total_steps=1,
        step=ScenarioPlanStep(summary="Run autoscaling experiment (Python)", command=["python"]),
        status="failed",
        error="very long traceback line 1\nline 2",
    )

    NanofaasTUI()._applier.apply_e2e_step_event(dashboard, event)

    assert [(step.label, step.state, step.detail) for step in dashboard.steps] == [
        ("Run autoscaling experiment (Python)", "failed", "")
    ]
    assert any("[fail] Run autoscaling experiment (Python)" in line for line in dashboard.log_lines)


def test_tui_helm_stack_scenario_uses_demo_loadtest_defaults(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app

    called: dict[str, object] = {}

    monkeypatch.setattr(
        tui_app,
        "_ask",
        lambda prompt_fn: {
            "Scenario:": "helm-stack",
            "VM Name:": "nanofaas-e2e",
            "Control-plane runtime:": "java",
            "Cleanup VM at end?": False,
            "Dry-run? (show plan without executing)": True,
        }[str(prompt_fn().message)],
    )

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.helm_stack", task_ids=["vm.ensure_running"], run=lambda: "ok")

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)

    NanofaasTUI()._run_vm_e2e_scenario("helm-stack")

    request = called["request"]
    assert request.function_preset == "demo-loadtest"
    assert request.resolved_scenario is not None
    assert request.resolved_scenario.base_scenario == "helm-stack"


def test_tui_two_vm_loadtest_uses_two_vm_request_defaults(monkeypatch) -> None:
    import controlplane_tool.tui.app as tui_app
    import controlplane_tool.e2e.e2e_runner as e2e_runner

    called: dict[str, object] = {}

    class _FakePlan:
        steps = [SimpleNamespace(summary="Run k6 from loadgen VM")]

    monkeypatch.setattr(e2e_runner.E2eRunner, "plan", lambda self, request: _FakePlan())

    def fake_build_scenario_flow(scenario, **kwargs):  # noqa: ANN001
        called["scenario"] = scenario
        called["request"] = kwargs["request"]
        return LocalFlowDefinition(flow_id="e2e.two_vm_loadtest", task_ids=["loadgen.run_k6"], run=lambda: "ok")

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["summary_lines"] = summary_lines
        called["planned_steps"] = planned_steps
        dashboard = SimpleNamespace(append_log=lambda message: None)
        sink = SimpleNamespace(_update=lambda: None)
        return action(dashboard, sink)

    monkeypatch.setattr(tui_app, "build_scenario_flow", fake_build_scenario_flow)
    monkeypatch.setattr(TuiWorkflowController, "run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e_scenario("two-vm-loadtest")

    request = called["request"]
    assert called["scenario"] == "two-vm-loadtest"
    assert called["summary_lines"] == [
        "Scenario: two-vm-loadtest",
        "Mode: self-bootstrapping VM-backed scenario",
    ]
    assert called["planned_steps"] == ["Run k6 from loadgen VM"]
    assert request.scenario == "two-vm-loadtest"
    assert request.function_preset == "demo-loadtest"
    assert request.vm is not None
    assert request.vm.name == "nanofaas-e2e"
    assert request.vm.memory == "8G"
    assert request.loadgen_vm is not None
    assert request.loadgen_vm.name == "nanofaas-e2e-loadgen"
