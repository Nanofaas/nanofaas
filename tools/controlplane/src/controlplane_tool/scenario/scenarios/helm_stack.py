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
        return self.workflow_task_ids

    @property
    def workflow_task_ids(self) -> list[str]:
        """Ordered task_ids of the honest Workflow.

        EnsureVmRunning is run by ``run()`` and is not part of the Workflow's
        ``tasks``; we prepend its id here so the list matches the recipe exactly.
        """
        setup = self._build_setup()
        workflow = self._assemble(
            setup, lambda: VmInfo(name="", host="", user="", home=""), resolve_host=False
        )
        return ["vm.ensure_running"] + workflow.task_ids

    @property
    def phase_titles(self) -> list[str]:
        """Ordered display titles of the workflow phases (for the TUI).

        Mirrors ``workflow_task_ids``: prepends the EnsureVmRunning title (run
        separately by ``run()``) then the titles of the Workflow tasks (helm-stack
        has no cleanup tasks).
        """
        setup = self._build_setup()
        workflow = self._assemble(
            setup, lambda: VmInfo(name="", host="", user="", home=""), resolve_host=False
        )
        return ["Ensure VM is running"] + [
            t.title for t in workflow.tasks + workflow.cleanup_tasks
        ]

    # ── workflow assembly ───────────────────────────────────────────────────────

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _assemble(
        self,
        setup: _Setup,
        vm_info: "Callable[[], VmInfo]",
        *,
        resolve_host: bool = True,
    ) -> Workflow:
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
            self.runner,
            self.request,
            setup,
            recipe,
            special_handler=special_handler,
            resolve_host=resolve_host,
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
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.scenario.scenarios._workflow_assembly import (
        workflow_display_steps,
    )

    scenario = resolve_scenario("helm-stack")
    plan = HelmStackPlan(
        scenario=scenario,
        request=request,
        steps=[],
        runner=runner,
    )
    # Lightweight display steps derived from the honest Workflow (NOT the legacy
    # recipe engine), so CLI dry-run still renders commands. The TUI uses
    # phase_titles; task identity comes from workflow_task_ids.
    workflow = plan._assemble(
        plan._build_setup(),
        lambda: VmInfo(name="", host="", user="", home=""),
        resolve_host=False,
    )
    plan.steps = workflow_display_steps(workflow.tasks + workflow.cleanup_tasks)
    return plan
