import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DASHBOARD = REPO_ROOT / "experiments" / "grafana" / "dashboards" / "nanofaas.json"


def _load_dashboard():
    return json.loads(DASHBOARD.read_text(encoding="utf-8"))


def _panel_by_title(data, title):
    for panel in data.get("panels", []):
        if panel.get("title") == title:
            return panel
    return None


def test_dashboard_has_function_variable_and_queue_percentiles_panel():
    data = _load_dashboard()

    templating = data.get("templating", {}).get("list", [])
    function_var = next((v for v in templating if v.get("name") == "function"), None)
    assert function_var is not None
    assert "label_values" in function_var.get("query", "")

    queue_panel = _panel_by_title(data, "Queue Depth Percentiles (window)")
    assert queue_panel is not None
    exprs = [t.get("expr", "") for t in queue_panel.get("targets", [])]
    assert any("quantile_over_time(0.50" in expr for expr in exprs)
    assert any("quantile_over_time(0.95" in expr for expr in exprs)
    assert any("quantile_over_time(0.99" in expr for expr in exprs)


def test_dashboard_uses_zero_fill_for_sparse_error_metrics_and_dispatch_total_stat():
    data = _load_dashboard()

    err_panel = _panel_by_title(data, "Error / Timeout / Reject / Retry Rate")
    assert err_panel is not None
    exprs = [t.get("expr", "") for t in err_panel.get("targets", [])]
    # Sparse counters are absent until first event; zero-fill avoids empty charts.
    assert all("or on(function)" in expr for expr in exprs)

    total_panel = _panel_by_title(data, "Total Dispatch (cumulative)")
    assert total_panel is not None
    total_exprs = [t.get("expr", "") for t in total_panel.get("targets", [])]
    assert any("function_dispatch_total" in expr for expr in total_exprs)
    assert all("function_enqueue_total" not in expr for expr in total_exprs)
