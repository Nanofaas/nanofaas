from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.console import bind_workflow_sink
from controlplane_tool.e2e_runner import ScenarioPlanStep, ScenarioStepEvent
from controlplane_tool.module_catalog import module_choices
from controlplane_tool.prefect_models import FlowRunResult, LocalFlowDefinition
from controlplane_tool.tui import DEFAULT_REQUIRED_METRICS, build_profile_interactive
from controlplane_tool.tui_app import NanofaasTUI
from controlplane_tool.tui_workflow import TuiWorkflowSink, WorkflowDashboard


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


def test_tui_cli_e2e_menu_offers_cli_stack_runner(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    answers = iter(["cli-stack"])
    captured: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        captured["title"] = title
        captured["summary_lines"] = summary_lines
        captured["planned_steps"] = planned_steps
        return None

    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert captured["title"] == "CLI E2E"
    assert captured["summary_lines"] == [
        "Runner: cli-stack",
        "Mode: canonical self-bootstrapping VM-backed CLI stack",
    ]
    assert "Build nanofaas-cli installDist in VM" in captured["planned_steps"]
    assert "Verify cli-stack status fails" in captured["planned_steps"]


def test_tui_cli_e2e_menu_describes_host_platform_as_compatibility_path(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    answers = iter(["host-platform"])
    captured: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        captured["title"] = title
        captured["summary_lines"] = summary_lines
        captured["planned_steps"] = planned_steps
        return None

    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._cli_e2e_menu()

    assert captured["title"] == "CLI E2E"
    assert captured["summary_lines"] == [
        "Runner: host-platform",
        "Mode: compatibility path; platform-only on host vs cluster",
    ]


def test_tui_e2e_menu_marks_vm_scenarios_as_self_bootstrapping(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    captured: dict[str, object] = {}

    def fake_select(*args, **kwargs):  # noqa: ANN001
        captured["choices"] = [choice.title for choice in kwargs["choices"]]
        return _Prompt("container-local")

    monkeypatch.setattr(tui_app.questionary, "select", fake_select)
    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: prompt_fn())
    monkeypatch.setattr(NanofaasTUI, "_run_container_local", lambda self: None)

    NanofaasTUI()._e2e_menu()

    assert "k3s-junit-curl — self-bootstrapping VM stack with curl + JUnit verification" in captured["choices"]
    assert "helm-stack — self-bootstrapping VM stack for Helm compatibility" in captured["choices"]


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


def test_tui_main_menu_includes_registry_entry() -> None:
    assert any(choice.value == "registry" for choice in NanofaasTUI._MAIN_MENU if hasattr(choice, "value"))


def test_tui_registry_menu_starts_local_registry(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app
    from controlplane_tool.registry_runtime import default_registry_url

    answers = iter(["start"])
    called: dict[str, object] = {}

    monkeypatch.setattr(tui_app, "_ask", lambda prompt_fn: next(answers))
    monkeypatch.setattr(tui_app, "ensure_local_registry", lambda **kwargs: called.update(kwargs) or object())

    def fake_live(self, *, title, summary_lines, planned_steps, action):  # noqa: ANN001
        called["title"] = title
        called["planned_steps"] = planned_steps
        return action(SimpleNamespace(append_log=lambda message: None), SimpleNamespace(_update=lambda: None))

    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._registry_menu()

    assert called["title"] == "Registry"
    assert called["registry"] == default_registry_url()


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


def test_tui_helm_stack_scenario_shows_shared_execution_phases(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app
    import controlplane_tool.e2e_runner as e2e_runner

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
            SimpleNamespace(summary="Ensure E2E namespace exists"),
            SimpleNamespace(summary="Deploy control-plane via Helm"),
            SimpleNamespace(summary="Deploy function-runtime via Helm"),
            SimpleNamespace(summary="Wait for control-plane deployment"),
            SimpleNamespace(summary="Wait for function-runtime deployment"),
            SimpleNamespace(summary="Run loadtest via Python runner"),
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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e("helm-stack")

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
        "Ensure E2E namespace exists",
        "Deploy control-plane via Helm",
        "Deploy function-runtime via Helm",
        "Wait for control-plane deployment",
        "Wait for function-runtime deployment",
        "Run loadtest via Python runner",
        "Run autoscaling experiment (Python)",
    ]


def test_tui_helm_stack_scenario_does_not_add_wrapper_steps_to_dashboard(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app
    import controlplane_tool.e2e_runner as e2e_runner

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
            SimpleNamespace(summary="Ensure E2E namespace exists"),
            SimpleNamespace(summary="Deploy control-plane via Helm"),
            SimpleNamespace(summary="Deploy function-runtime via Helm"),
            SimpleNamespace(summary="Wait for control-plane deployment"),
            SimpleNamespace(summary="Wait for function-runtime deployment"),
            SimpleNamespace(summary="Run loadtest via Python runner"),
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
                    total_steps=15,
                    step=step,
                    status="running",
                )
            )
            event_listener(
                ScenarioStepEvent(
                    step_index=1,
                    total_steps=15,
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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(NanofaasTUI, "_run_live_workflow", fake_live)

    NanofaasTUI()._run_vm_e2e("helm-stack")

    assert captured["steps"] == [
        ("Ensure VM is running", "success"),
        ("Provision base VM dependencies", "pending"),
        ("Sync project to VM", "pending"),
        ("Ensure registry container", "pending"),
        ("Build control-plane and runtime images in VM", "pending"),
        ("Build selected function images in VM", "pending"),
        ("Install k3s", "pending"),
        ("Configure k3s registry", "pending"),
        ("Ensure E2E namespace exists", "pending"),
        ("Deploy control-plane via Helm", "pending"),
        ("Deploy function-runtime via Helm", "pending"),
        ("Wait for control-plane deployment", "pending"),
        ("Wait for function-runtime deployment", "pending"),
        ("Run loadtest via Python runner", "pending"),
        ("Run autoscaling experiment (Python)", "pending"),
    ]


def test_tui_k3s_junit_curl_marks_nested_verify_steps_success_when_flow_completes(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app
    import controlplane_tool.e2e_runner as e2e_runner
    from controlplane_tool.console import phase, step
    from rich.console import Console
    import re

    captured: dict[str, object] = {}

    answers = iter(["nanofaas-e2e", "java", True, False])
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
                command=["python", "-m", "controlplane_tool.k3s_curl_runner", "verify-existing-stack"],
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
    monkeypatch.setattr(tui_app, "run_local_flow", fake_run_local_flow)
    monkeypatch.setattr(tui_app, "Live", _FakeLive)
    monkeypatch.setattr(tui_app, "WorkflowKeyListener", _FakeKeyListener)

    NanofaasTUI()._run_vm_e2e("k3s-junit-curl")

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
    import controlplane_tool.tui_app as tui_app

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

    monkeypatch.setattr(tui_app, "Live", _FakeLive)
    monkeypatch.setattr(tui_app, "WorkflowKeyListener", _FakeKeyListener)
    monkeypatch.setattr(WorkflowDashboard, "complete_running_steps", fail_complete_running_steps)

    result = NanofaasTUI()._run_live_workflow(
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

    NanofaasTUI()._apply_e2e_step_event(dashboard, event)

    assert [(step.label, step.state, step.detail) for step in dashboard.steps] == [
        ("Run autoscaling experiment (Python)", "failed", "")
    ]
    assert any("[fail] Run autoscaling experiment (Python)" in line for line in dashboard.log_lines)


def test_tui_helm_stack_scenario_uses_demo_loadtest_defaults(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

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

    NanofaasTUI()._run_vm_e2e("helm-stack")

    request = called["request"]
    assert request.function_preset == "demo-loadtest"
    assert request.resolved_scenario is not None
    assert request.resolved_scenario.base_scenario == "helm-stack"
