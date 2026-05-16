from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CliHostPlan:
    """ScenarioPlan Protocol implementation for the cli-host scenario."""

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy, event_listener=event_listener)


def build_cli_host_plan(runner: "E2eRunner", request: E2eRequest) -> CliHostPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("cli-host")
    steps = runner._planner.vm_backed_steps(request)
    return CliHostPlan(scenario=scenario, request=request, steps=steps, runner=runner)
