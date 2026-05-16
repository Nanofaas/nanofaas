from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class K3sJunitCurlPlan:
    """ScenarioPlan Protocol implementation for k3s-junit-curl."""

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


def build_k3s_junit_curl_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> K3sJunitCurlPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("k3s-junit-curl")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "k3s-junit-curl",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
        multipass_client=runner._multipass_client,
    )
    return K3sJunitCurlPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
