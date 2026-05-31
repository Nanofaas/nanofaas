from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from workflow_tasks import (
    EnsureVmRunning,
    HostCommandTaskExecutor,
    Workflow,
    workflow_step,
)

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.scenarios._workflow_assembly import (
    _Setup,
    build_setup,
    host_command_task_from_step,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CliVmPlan:
    """ScenarioPlan Protocol implementation for cli.

    Builds and runs a Workflow of honest Tasks (no legacy recipe engine), while
    preserving the exact planner ordering and task_ids.

    cli differs from the recipe-based pilots (k3s/helm/cli-stack): its steps come
    from ``ScenarioPlanner.vm_backed_steps`` (plain host ScenarioPlanSteps), not a
    recipe. There is no recipe, no vm.down/teardown, and no special verify steps.
    The only special step is ``vm.ensure_running`` (bootstrap), which becomes an
    EnsureVmRunning task run by ``run()`` rather than a CommandTask.

    The ``include_bootstrap`` flag (used by E2eRunner chaining so a shared VM is
    bootstrapped once) is preserved: when False, the bootstrap steps (including
    vm.ensure_running) are omitted and only the scenario step runs.
    """

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)
    include_bootstrap: bool = True

    # ── task identity ───────────────────────────────────────────────────────────

    @property
    def task_ids(self) -> list[str]:
        return self.workflow_task_ids

    @property
    def workflow_task_ids(self) -> list[str]:
        """Ordered task_ids of the honest Workflow.

        When ``include_bootstrap`` is True, EnsureVmRunning is run by ``run()`` and
        is not part of the Workflow's ``tasks``; we prepend its id here so the list
        matches the legacy planner exactly. When False there is no ensure step.
        """
        workflow = self._assemble(
            include_bootstrap=self.include_bootstrap, resolve_host=False
        )
        if self.include_bootstrap:
            return ["vm.ensure_running"] + workflow.task_ids
        return workflow.task_ids

    @property
    def phase_titles(self) -> list[str]:
        """Ordered display titles of the workflow phases (for the TUI).

        Mirrors ``workflow_task_ids``: when ``include_bootstrap`` is True, prepends
        the EnsureVmRunning title (run separately by ``run()``) then the Workflow
        task titles; otherwise just the task titles.
        """
        workflow = self._assemble(
            include_bootstrap=self.include_bootstrap, resolve_host=False
        )
        titles = [t.title for t in workflow.tasks + workflow.cleanup_tasks]
        if self.include_bootstrap:
            return ["Ensure VM is running"] + titles
        return titles

    # ── workflow assembly ───────────────────────────────────────────────────────

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _assemble(
        self, *, include_bootstrap: bool, resolve_host: bool = True
    ) -> Workflow:
        """Build the Workflow of honest Tasks for this scenario.

        Converts every planner step except ``vm.ensure_running`` (handled by
        ``run()`` as EnsureVmRunning) into a host CommandTask, resolving
        ``<multipass-ip:NAME>`` placeholders at assembly time. There are no cleanup
        tasks for cli. When *resolve_host* is False the raw step commands are kept
        (used to derive task_ids/phase_titles without a running VM).
        """
        runner = self.runner
        request = self.request

        legacy_steps = runner._planner.vm_backed_steps(  # noqa: SLF001
            request, include_bootstrap=include_bootstrap
        )
        resolver = CommandResolver(host_resolver=runner._host_resolver)  # noqa: SLF001
        host_executor = HostCommandTaskExecutor(runner.shell)
        ip_cache: dict[str, str] = {}

        tasks: list = []
        for step in legacy_steps:
            if step.step_id == "vm.ensure_running":
                continue  # run separately by run() as EnsureVmRunning
            tasks.append(
                host_command_task_from_step(
                    step,
                    resolver=resolver,
                    request=request,
                    vm=runner.vm,
                    ip_cache=ip_cache,
                    host_executor=host_executor,
                    resolve_host=resolve_host,
                )
            )
        return Workflow(tasks=tasks, cleanup_tasks=[])

    # ── execution ───────────────────────────────────────────────────────────────

    def run(self, event_listener=None) -> None:
        # event_listener: not used; the Workflow emits progress via workflow_step.
        del event_listener

        if self.include_bootstrap:
            setup = self._build_setup()
            ensure_vm = EnsureVmRunning(
                task_id="vm.ensure_running",
                title="Ensure VM is running",
                lifecycle=setup.lifecycle,
                config=setup.vm_config,
            )
            with workflow_step(task_id=ensure_vm.task_id, title=ensure_vm.title):
                ensure_vm.run()

        workflow = self._assemble(include_bootstrap=self.include_bootstrap)
        workflow.run()


def build_cli_vm_plan(
    runner: "E2eRunner",
    request: E2eRequest,
    include_bootstrap: bool = True,
) -> CliVmPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("cli")
    steps = runner._planner.vm_backed_steps(request, include_bootstrap=include_bootstrap)
    return CliVmPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
        include_bootstrap=include_bootstrap,
    )
