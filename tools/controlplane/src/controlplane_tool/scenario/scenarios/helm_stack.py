from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable

from workflow_tasks import (
    EnsureVmRunning,
    Workflow,
    workflow_step,
)
from workflow_tasks.vm.models import VmInfo

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenarios._workflow_assembly import (
    HANDLED,
    _Setup,
    build_command_tasks,
    build_setup,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class HelmStackPlan:
    """ScenarioPlan Protocol implementation for helm-stack.

    Builds and runs a Workflow of honest Tasks (no legacy recipe engine), while
    preserving the exact recipe ordering, task_ids and commands. helm-stack leaves
    the VM running, so there is no vm.down cleanup task.
    """

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    # ── task identity ───────────────────────────────────────────────────────────

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    @property
    def workflow_task_ids(self) -> list[str]:
        """Ordered task_ids of the honest Workflow.

        EnsureVmRunning is run by ``run()`` and is not part of the Workflow's
        ``tasks``; we prepend its id here so the list matches the recipe exactly.
        """
        setup = self._build_setup()
        workflow = self._assemble(setup, lambda: VmInfo(name="", host="", user="", home=""))
        return ["vm.ensure_running"] + workflow.task_ids

    # ── workflow assembly ───────────────────────────────────────────────────────

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _assemble(self, setup: _Setup, vm_info: "Callable[[], VmInfo]") -> Workflow:
        """Build the Workflow of honest Tasks for this scenario.

        The returned Workflow contains ONLY the command tasks, routed by
        ``execution_target``. EnsureVmRunning is run separately by ``run()`` and is
        not part of the Workflow. There are no cleanup tasks (the VM is left
        running).

        *vm_info* is accepted for signature parity with the other converted plans;
        helm-stack has no cleanup task that needs it.
        """
        del vm_info

        def special_handler(operation):
            if operation.operation_id == "vm.ensure_running":
                return HANDLED  # run separately by run() as EnsureVmRunning
            return None

        recipe = build_scenario_recipe("helm-stack")
        tasks = build_command_tasks(
            self.runner, self.request, setup, recipe, special_handler=special_handler
        )
        return Workflow(tasks=tasks, cleanup_tasks=[])

    # ── execution ───────────────────────────────────────────────────────────────

    def run(self, event_listener=None) -> None:
        # event_listener: not used; the Workflow emits progress via workflow_step.
        del event_listener

        setup = self._build_setup()

        ensure_vm = EnsureVmRunning(
            task_id="vm.ensure_running",
            title="Ensure VM is running",
            lifecycle=setup.lifecycle,
            config=setup.vm_config,
        )
        with workflow_step(task_id=ensure_vm.task_id, title=ensure_vm.title):
            info = ensure_vm.run()

        workflow = self._assemble(setup, lambda: info)
        workflow.run()


def build_helm_stack_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> HelmStackPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("helm-stack")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "helm-stack",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
        multipass_client=runner._multipass_client,
    )
    return HelmStackPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
