from pathlib import Path
from unittest.mock import MagicMock
from controlplane_tool.scenario_planner import ScenarioPlanner
from controlplane_tool.e2e_models import E2eRequest


def test_scenario_planner_local_steps_returns_list() -> None:
    vm = MagicMock()
    shell = MagicMock()
    paths = MagicMock()
    paths.workspace_root = Path("/repo")
    planner = ScenarioPlanner(paths=paths, vm=vm, shell=shell, manifest_root=Path("/repo/runs/manifests"))
    request = E2eRequest(scenario="docker", runtime="java")

    steps = planner.local_steps(request)

    assert isinstance(steps, list)
