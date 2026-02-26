from __future__ import annotations

from pathlib import Path
import re

_METRIC_NAME = re.compile(r"^[a-zA-Z_:][a-zA-Z0-9_:]*$")
_JAVA_METRIC_LITERAL = re.compile(r'"(function_[a-z0-9_]+(?:_ms|_total)?)"')


def parse_prometheus_metric_names(payload: str) -> set[str]:
    names: set[str] = set()
    for line in payload.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        metric_name = stripped.split("{", 1)[0].split(" ", 1)[0]
        if _METRIC_NAME.match(metric_name):
            names.add(metric_name)
    return names


def missing_required_metrics(required: list[str], observed_names: set[str]) -> list[str]:
    return [name for name in required if name not in observed_names]


def discover_control_plane_metric_names(repo_root: Path) -> set[str]:
    metrics_java = (
        repo_root
        / "control-plane"
        / "src"
        / "main"
        / "java"
        / "it"
        / "unimib"
        / "datai"
        / "nanofaas"
        / "controlplane"
        / "service"
        / "Metrics.java"
    )
    if not metrics_java.exists():
        return set()
    text = metrics_java.read_text(encoding="utf-8")
    return set(_JAVA_METRIC_LITERAL.findall(text))
