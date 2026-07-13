from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

import questionary

from controlplane_tool.cli.execution import resolve_loadtest_urls
from controlplane_tool.cli.product import _environment, _render, _scenario, _workflow
from controlplane_tool.workspace.paths import default_tool_paths


def _select(message: str, choices: list[str]) -> str:
    value = questionary.select(message, choices=choices).ask()
    if value is None:
        raise KeyboardInterrupt
    return str(value)


class NanofaasTUI:
    """Thin interactive client for the same scenario plans used by the CLI."""

    def __init__(self, choose: Callable[[str, list[str]], str] = _select) -> None:
        self._choose = choose

    def run(self) -> None:
        tool_root = default_tool_paths().tool_root
        scenarios = sorted(str(path) for path in (tool_root / "scenarios-v2").glob("*.yaml"))
        environments = sorted(str(path) for path in (tool_root / "environments").glob("*.yaml"))
        if not scenarios or not environments:
            raise RuntimeError("scenario and environment examples are required")
        scenario_path = Path(self._choose("Scenario", scenarios))
        environment_path = Path(self._choose("Environment", environments))
        action = self._choose("Action", ["plan", "run"])
        scenario = _scenario(scenario_path)
        environment = _environment(environment_path)
        control_plane_url = "http://127.0.0.1:8080"
        prometheus_url = "http://127.0.0.1:9090"
        if scenario.workflow == "loadtest":
            control_plane_url, prometheus_url = resolve_loadtest_urls(
                environment, dry_run=action == "plan"
            )
        workflow = _workflow(
            scenario,
            environment,
            control_plane_url=control_plane_url,
            prometheus_url=prometheus_url,
        )
        if action == "plan":
            _render(workflow)
            return
        keep = self._choose("Cleanup policy", ["cleanup", "keep"]) == "keep"
        workflow.keep_infrastructure = keep
        workflow.run()
