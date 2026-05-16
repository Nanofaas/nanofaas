from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CliStackPlan:
    """ScenarioPlan Protocol implementation for cli-stack."""

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan

        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy)


def build_cli_stack_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> CliStackPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("cli-stack")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "cli-stack",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return CliStackPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
