from controlplane_tool.module_catalog import module_choices
from controlplane_tool.tui import DEFAULT_REQUIRED_METRICS


def test_module_catalog_has_descriptions() -> None:
    choices = module_choices()
    assert choices
    for module in choices:
        assert module.name
        assert module.description


def test_default_required_metrics_match_control_plane_metrics() -> None:
    assert "function_dispatch_total" in DEFAULT_REQUIRED_METRICS
    assert "function_latency_ms" in DEFAULT_REQUIRED_METRICS
    assert "function_e2e_latency_ms" in DEFAULT_REQUIRED_METRICS
