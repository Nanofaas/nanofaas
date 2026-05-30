from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
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
from controlplane_tool.scenario.components.cli import CliComponentContext
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
class CliStackPlan:
    """ScenarioPlan Protocol implementation for cli-stack.

    Builds and runs a Workflow of honest Tasks (no legacy recipe engine), while
    preserving the exact recipe ordering, task_ids, commands and --no-cleanup-vm
    handling.

    cli-stack differs from the k3s/helm pilots in two ways:

    - ``cli.*`` components' planners need a ``CliComponentContext`` (with the VM's
      remote project dir as repo_root); all other planners take the neutral
      ScenarioExecutionContext. ``context_selector`` routes per component.
    - ``cleanup.verify_cli_platform_status_fails`` is an expect-failure step: it
      runs the vm command and only fails if it *unexpectedly succeeds*. It is wired
      as a CallableTask (no CommandTask spec).
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

    @property
    def phase_titles(self) -> list[str]:
        """Ordered display titles of the workflow phases (for the TUI).

        Mirrors ``workflow_task_ids``: prepends the EnsureVmRunning title (run
        separately by ``run()``) then the titles of the Workflow tasks and
        cleanup tasks, in execution order.
        """
        setup = self._build_setup()
        workflow = self._assemble(setup, lambda: VmInfo(name="", host="", user="", home=""))
        return ["Ensure VM is running"] + [
            t.title for t in workflow.tasks + workflow.cleanup_tasks
        ]

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
        vm_request = setup.vm_request
        remote_dir = runner.vm.remote_project_dir(vm_request)

        # cli.* planners need the VM-side repo root and platform identifiers. The
        # control plane endpoint is None for cli-stack (it talks to the in-VM API
        # via KUBECONFIG/namespace, not an explicit endpoint).
        cli_context = CliComponentContext(
            repo_root=Path(remote_dir),
            release=setup.context.release,
            namespace=setup.context.namespace,
            local_registry=setup.context.local_registry,
            resolved_scenario=setup.context.resolved_scenario,
            control_plane_endpoint=None,
        )

        def context_selector(component: object) -> object:
            if component.component_id.startswith("cli."):  # type: ignore[attr-defined]
                return cli_context
            return setup.context

        cleanup_tasks: list = []

        def _expect_status_fails(operation: RemoteCommandOperation) -> Callable[[], None]:
            argv = operation.argv
            env = dict(operation.env) or None

            def action() -> None:
                result = runner.vm.exec_argv(vm_request, argv, env=env, cwd=remote_dir)
                if result.return_code == 0:
                    raise RuntimeError(
                        "platform status unexpectedly succeeded after cleanup"
                    )

            return action

        def special_handler(operation: RemoteCommandOperation):
            op_id = operation.operation_id
            if op_id == "vm.ensure_running":
                return HANDLED  # run separately by run() as EnsureVmRunning
            if op_id == "vm.down":
                if request.cleanup_vm:
                    cleanup_tasks.append(
                        DestroyVm(
                            task_id="vm.down",
                            title="Teardown VM",
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
                            title="Teardown VM",
                            action=lambda: None,
                        )
                    )
                return HANDLED
            if op_id == "cleanup.verify_cli_platform_status_fails":
                return CallableTask(
                    task_id=op_id,
                    title="Verify cli-stack status fails",
                    action=_expect_status_fails(operation),
                )
            return None

        recipe = build_scenario_recipe("cli-stack")
        tasks = build_command_tasks(
            runner,
            request,
            setup,
            recipe,
            special_handler=special_handler,
            context_selector=context_selector,
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
        multipass_client=runner._multipass_client,
    )
    return CliStackPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
