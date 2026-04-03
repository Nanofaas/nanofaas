from pathlib import Path

from controlplane_tool.report import render_report


def test_report_contains_required_sections(tmp_path: Path) -> None:
    summary = {
        "profile_name": "demo",
        "final_status": "passed",
        "steps": [
            {"name": "preflight", "status": "passed", "detail": "ok", "duration_ms": 0},
            {"name": "compile", "status": "passed", "detail": "ok", "duration_ms": 2100},
            {"name": "docker_image", "status": "passed", "detail": "ok", "duration_ms": 3500},
        ],
        "metrics": {
            "dispatch_duration_ms": [
                {"timestamp": "2026-02-26T12:00:00Z", "value": 12.0},
                {"timestamp": "2026-02-26T12:01:00Z", "value": 9.5},
            ]
        },
    }

    report_path = render_report(summary=summary, output_dir=tmp_path)
    text = report_path.read_text(encoding="utf-8")

    assert "Run metadata" in text
    assert "Step timeline" in text
    assert "Metrics over time" in text
