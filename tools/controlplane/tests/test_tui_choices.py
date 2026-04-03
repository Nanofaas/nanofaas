from controlplane_tool.module_catalog import module_choices
from controlplane_tool.tui import DEFAULT_REQUIRED_METRICS, build_profile_interactive


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
    confirm_answers = iter([True, True, True, True, False])
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

    select_answers = iter(["java", "native", "quick", "preset", "k8s-vm", "demo-java"])
    confirm_answers = iter([True, True, True, True, True])

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
    assert profile.scenario.base_scenario == "k8s-vm"
