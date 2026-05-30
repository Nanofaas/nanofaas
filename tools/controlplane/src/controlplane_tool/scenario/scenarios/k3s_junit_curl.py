from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable

from workflow_tasks import (
    DestroyVm,
    EnsureVmRunning,
    Workflow,
    workflow_step,
)
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.models import VmInfo

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenarios._workflow_assembly import (
    HANDLED,
    CallableTask,
    _Setup,
    build_command_tasks,
    build_setup,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class K3sJunitCurlPlan:
    """ScenarioPlan Protocol implementation for k3s-junit-curl.

    Builds and runs a Workflow of honest Tasks (no legacy recipe engine), while
    preserving the exact recipe ordering, task_ids, commands and --no-cleanup-vm
    handling.
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
        # The info for the (possible) DestroyVm cleanup task is irrelevant to the
        # id list, so a placeholder VmInfo is fine here.
        setup = self._build_setup()
        workflow = self._assemble(setup, lambda: VmInfo(name="", host="", user="", home=""))
        return ["vm.ensure_running"] + workflow.task_ids

    # ── workflow assembly ───────────────────────────────────────────────────────

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _assemble(self, setup: _Setup, vm_info: "Callable[[], VmInfo]") -> Workflow:
        """Build the Workflow of honest Tasks for this scenario.

        The returned Workflow contains ONLY the command/verify tasks (+ a cleanup
        task). EnsureVmRunning is run separately by ``run()`` and is not part of the
        Workflow.

        *vm_info* is called lazily to supply the resolved VmInfo for the DestroyVm
        cleanup task (it is only resolved if cleanup_vm is True).
        """
        runner = self.runner
        request = self.request
        lifecycle = setup.lifecycle

        cleanup_tasks: list = []

        def special_handler(operation: RemoteCommandOperation):
            op_id = operation.operation_id
            if op_id == "vm.ensure_running":
                return HANDLED  # run separately by run() as EnsureVmRunning
            if op_id == "vm.down":
                if request.cleanup_vm:
                    cleanup_tasks.append(
                        DestroyVm(
                            task_id="vm.down",
                            title="Tear down VM",
                            lifecycle=lifecycle,
                            info=vm_info(),
                        )
                    )
                else:
                    # The legacy recipe keeps a 'vm.down' no-op step even with
                    # --no-cleanup-vm; preserve the task_id for spec parity.
                    cleanup_tasks.append(
                        CallableTask(
                            task_id="vm.down",
                            title="Skip VM teardown (--no-cleanup-vm)",
                            action=lambda: None,
                        )
                    )
                return HANDLED
            if op_id == "tests.run_k3s_curl_checks":
                return CallableTask(
                    task_id=op_id,
                    title=operation.summary,
                    action=lambda: runner._planner._k3s_curl_runner(  # noqa: SLF001
                        request
                    ).verify_existing_stack(request.resolved_scenario),
                )
            return None

        recipe = build_scenario_recipe("k3s-junit-curl")
        tasks = build_command_tasks(
            runner, request, setup, recipe, special_handler=special_handler
        )
        return Workflow(tasks=tasks, cleanup_tasks=cleanup_tasks)

    # ── execution ───────────────────────────────────────────────────────────────

    def run(self, event_listener=None) -> None:
        # event_listener: not used; the Workflow emits progress via workflow_step.
        del event_listener

        setup = self._build_setup()

        # Run vm.ensure_running first so the resolved host is available for the
        # DestroyVm cleanup task and for placeholder substitution.
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
