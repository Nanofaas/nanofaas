import json
from pathlib import Path

from controlplane_tool.e2e.two_vm_loadtest_runner import (
    TwoVmLoadtestReport,
    write_two_vm_report,
)


def test_two_vm_report_writes_summary_and_html(tmp_path: Path) -> None:
    k6_summary = tmp_path / "k6-summary.json"
    k6_summary.write_text('{"metrics":{}}\n', encoding="utf-8")
    prometheus_snapshot = tmp_path / "metrics" / "prometheus-snapshots.json"
    prometheus_snapshot.parent.mkdir()
    prometheus_snapshot.write_text(
        json.dumps(
            {
                "queries": {
                    "function_dispatch_total": {
                        "points": [{"timestamp": "2026-05-13T10:00:00+00:00", "value": 3.0}]
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    summary_path = write_two_vm_report(
        TwoVmLoadtestReport(
            final_status="passed",
            target_function="word-stats-java",
            control_plane_url="http://10.0.0.1:30080",
            prometheus_url="http://10.0.0.1:30090",
            k6_summary_path=k6_summary,
            prometheus_snapshot_path=prometheus_snapshot,
            script_path=Path("/tmp/script.js"),
            payload_path=Path("/tmp/payload.json"),
        ),
        tmp_path,
    )

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    assert summary["final_status"] == "passed"
    assert summary["metrics"]["function_dispatch_total"][0]["value"] == 3.0
    assert summary["two_vm_loadtest"]["target_function"] == "word-stats-java"
    assert summary["two_vm_loadtest"]["k6_summary_path"] == str(k6_summary)
    html = (tmp_path / "report.html").read_text(encoding="utf-8")
    assert "Two-VM Loadtest" in html
    assert "word-stats-java" in html
