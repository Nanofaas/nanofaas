from __future__ import annotations

from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, select_autoescape
import plotly.graph_objects as go
from plotly.io import to_html


def _timeline_chart(steps: list[dict[str, Any]]) -> str:
    names = [step.get("name", "") for step in steps]
    durations = [int(step.get("duration_ms", 0)) for step in steps]
    colors = [
        "#1f77b4" if step.get("status") == "passed" else "#d62728"
        for step in steps
    ]
    fig = go.Figure(
        data=[
            go.Bar(
                x=names,
                y=durations,
                marker_color=colors,
                text=[step.get("status", "") for step in steps],
            )
        ]
    )
    fig.update_layout(
        title="Step Duration (ms)",
        xaxis_title="Step",
        yaxis_title="Duration (ms)",
        margin=dict(l=30, r=30, t=45, b=30),
        height=360,
    )
    return to_html(fig, include_plotlyjs="cdn", full_html=False)


def _metrics_charts(metrics: dict[str, Any]) -> list[dict[str, str]]:
    charts: list[dict[str, str]] = []
    for metric_name, points in metrics.items():
        x = [point.get("timestamp") for point in points]
        y = [point.get("value") for point in points]
        fig = go.Figure(data=[go.Scatter(x=x, y=y, mode="lines+markers", name=metric_name)])
        fig.update_layout(
            title=metric_name,
            xaxis_title="Time",
            yaxis_title="Value",
            margin=dict(l=30, r=30, t=45, b=30),
            height=320,
        )
        charts.append(
            {
                "name": metric_name,
                "html": to_html(fig, include_plotlyjs=False, full_html=False),
            }
        )
    return charts


def render_report(summary: dict[str, Any], output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)

    env = Environment(
        loader=FileSystemLoader(str(Path(__file__).parent / "templates")),
        autoescape=select_autoescape(["html", "xml"]),
    )
    template = env.get_template("report.html.j2")

    timeline_html = _timeline_chart(list(summary.get("steps", [])))
    metric_charts = _metrics_charts(dict(summary.get("metrics", {})))

    html = template.render(
        summary=summary,
        timeline_html=timeline_html,
        metric_charts=metric_charts,
    )

    destination = output_dir / "report.html"
    destination.write_text(html, encoding="utf-8")
    return destination
