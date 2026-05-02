from __future__ import annotations

from controlplane_tool.loadtest_flows import build_loadtest_flow


def test_loadtest_flow_runs_bootstrap_execute_gate_and_report_tasks() -> None:
    flow = build_loadtest_flow("quick")

    assert flow.flow_id == "loadtest.quick"
    assert flow.task_ids == [
        "loadtest.bootstrap",
        "loadtest.execute_k6",
        "metrics.evaluate_gate",
        "loadtest.write_report",
    ]
