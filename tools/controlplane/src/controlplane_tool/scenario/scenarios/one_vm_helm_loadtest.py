from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from workflow_tasks.components.models import ScenarioRecipe

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import STACK_PRELUDE
from controlplane_tool.scenario.scenarios._workflow_assembly import _Setup, build_command_tasks, build_setup

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class OneVmHelmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_task_ids

        return loadtest_flow_task_ids(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    @property
    def phase_titles(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_phase_titles

        return loadtest_flow_phase_titles(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _recipe(self) -> ScenarioRecipe:
        # vm.ensure_running is handled separately (EnsureVmRunning task outside the
        # Workflow, via the lifecycle adapter). The raw recipe component is a bare
        # `multipass launch` and would fail with "already exists" on every run.
        return ScenarioRecipe(
            name="loadtest-one-vm-stack",
            component_ids=tuple(c for c in STACK_PRELUDE if c != "vm.ensure_running"),
            requires_managed_vm=True,
        )

    def _adapter(self):
        from controlplane_tool.scenario.one_vm_loadtest_adapter import OneVmLoadtestAdapter

        return OneVmLoadtestAdapter(runner=self.runner, request=self.request)

    def _build_stack_prelude_tasks(self, setup: _Setup, *, resolve_host: bool = True) -> list:
        return build_command_tasks(
            self.runner,
            self.request,
            setup,
            self._recipe(),
            resolve_host=resolve_host,
        )

    def run(self, event_listener=None) -> None:
        from controlplane_tool.scenario.loadtest_flow import run_loadtest_flow

        run_loadtest_flow(
            runner=self.runner,
            request=self.request,
            setup=self._build_setup(),
            recipe=self._recipe(),
            adapter=self._adapter(),
            event_listener=event_listener,
        )


def build_one_vm_helm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> OneVmHelmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("loadtest-one-vm")
    return OneVmHelmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
