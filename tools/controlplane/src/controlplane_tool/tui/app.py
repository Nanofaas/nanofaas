from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

import questionary

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
        workflow = _workflow(_scenario(scenario_path), _environment(environment_path))
        if action == "plan":
            _render(workflow)
            return
        keep = self._choose("Cleanup policy", ["cleanup", "keep"]) == "keep"
        workflow.keep_infrastructure = keep
        workflow.run()
