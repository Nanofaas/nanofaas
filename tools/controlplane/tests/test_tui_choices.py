from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.module_catalog import module_choices
from controlplane_tool.prefect_models import FlowRunResult, LocalFlowDefinition
from controlplane_tool.tui import DEFAULT_REQUIRED_METRICS, build_profile_interactive
from controlplane_tool.tui_app import NanofaasTUI


def test_module_catalog_has_descriptions() -> None:
    choices = module_choices()
    assert choices
    for module in choices:
        assert module.name
        assert module.description


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
        ["java", "native", "quick", "preset", "k3s-junit-curl", "demo-java", "vm"]
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

    assert profile.cli_test.default_scenario == "vm"


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
    import controlplane_tool.tui_app as tui_app

    answers = iter(["provision-base", "multipass", "nanofaas-e2e", "ubuntu", False, False])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._vm_menu()

    assert called["built_flow_id"] == "vm.provision_base"
    assert called["flow_id"] == "vm.provision_base"
    assert called["dry_run"] is False
    assert called["title"] == "VM Management"


def test_tui_vm_menu_raises_when_shared_flow_returns_nonzero_command_result(monkeypatch) -> None:
    import pytest
    import controlplane_tool.tui_app as tui_app

    answers = iter(["up", "multipass", "nanofaas-e2e", "ubuntu", False])

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    with pytest.raises(RuntimeError, match="vm failed"):
        NanofaasTUI()._vm_menu()


def test_tui_vm_menu_logs_stdout_stderr_before_raising_on_nonzero_result(monkeypatch) -> None:
    import pytest
    import controlplane_tool.tui_app as tui_app

    answers = iter(["up", "multipass", "nanofaas-e2e", "ubuntu", False])
    log_lines: list[str] = []

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    with pytest.raises(RuntimeError, match="vm stderr"):
        NanofaasTUI()._vm_menu()

    assert "vm stdout" in log_lines
    assert "vm stderr" in log_lines


def test_tui_loadtest_menu_runs_shared_loadtest_flow_via_runtime(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: "run")
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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._loadtest_menu()

    assert called["built_flow_id"] == "loadtest.quick"
    assert called["flow_id"] == "loadtest.quick"
    assert called["title"] == "Load Testing"


def test_tui_k3s_junit_curl_scenario_runs_shared_flow_not_direct_execute(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app
    import controlplane_tool.e2e_runner as e2e_runner

    answers = iter(["nanofaas-e2e", "java", True, False])
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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e("k3s-junit-curl")

    assert called["scenario"] == "k3s-junit-curl"
    assert called["flow_id"] == "e2e.k3s_junit_curl"
    assert callable(called["event_listener"])
    assert called["request"].cleanup_vm is True
    assert called["request"].function_preset == "demo-java"
    assert called["request"].resolved_scenario is not None
    assert called["request"].resolved_scenario.function_keys == [
        "word-stats-java",
        "json-transform-java",
    ]
    assert called["planned_steps"] == ["Ensure VM is running", "Run k3s-junit-curl verification"]
