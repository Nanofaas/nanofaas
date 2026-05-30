from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable

from workflow_tasks import (
    DestroyVm,
    EnsureVmRunning,
    HostCommandTaskExecutor,
    VmCommandTaskExecutor,
    Workflow,
    command_task_from_operation,
    workflow_step,
)
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmConfig, VmInfo

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import OrchestratorVmRunner
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import build_scenario_recipe

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CallableTask:
    """A Task that runs an injected callable.

    Used to wrap host-side actions (e.g. the k3s-junit-curl verification step, or a
    no-op vm.down placeholder) as honest Workflow Tasks. Exceptions raised by
    *action* propagate so the Workflow stops on failure.
    """

    task_id: str
    title: str
    action: Callable[[], None] = field(repr=False, compare=False)

    def run(self) -> None:
        self.action()


@dataclass
class _Setup:
    """Shared environment/config built once for both run() and introspection."""

    context: ScenarioExecutionContext
    vm_request: object
    lifecycle: MultipassVmAdapter
    vm_config: VmConfig


def _resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
    # TODO(C-followup): promote CommandResolver.resolve_operation to public.
    argv = resolver._resolve_command(list(operation.argv), request.vm, ip_cache, vm)
    env = resolver._resolve_env(dict(operation.env), request.vm, ip_cache, vm)
    return RemoteCommandOperation(
        operation_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(argv),
        env=env,
        execution_target=operation.execution_target,
    )


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
        """Build the shared environment/config once (used by run() and introspection)."""
        runner = self.runner
        request = self.request
        context = resolve_scenario_environment(runner.paths.workspace_root, request)
        vm_request = context.vm_request
        lifecycle = MultipassVmAdapter(runner.vm)
        vm_config = VmConfig(
            name=vm_request.name or "",
            cpus=vm_request.cpus,
            memory=vm_request.memory,
            disk=vm_request.disk,
        )
        return _Setup(
            context=context,
            vm_request=vm_request,
            lifecycle=lifecycle,
            vm_config=vm_config,
        )

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

        context = setup.context
        vm_request = setup.vm_request
        lifecycle = setup.lifecycle
        vm_orch = runner.vm
        remote_dir = vm_orch.remote_project_dir(vm_request)

        host_executor = HostCommandTaskExecutor(runner.shell)
        vm_executor = VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request))
        resolver = CommandResolver(host_resolver=runner._host_resolver)
        ip_cache: dict[str, str] = {}

        recipe = build_scenario_recipe("k3s-junit-curl")
        tasks: list = []
        cleanup_tasks: list = []

        for component in compose_recipe(recipe):
            for operation in component.planner(context):
                op_id = operation.operation_id
                if op_id == "vm.ensure_running":
                    continue  # run separately by run() as EnsureVmRunning
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
                    continue
                if op_id == "tests.run_k3s_curl_checks":
                    tasks.append(
                        CallableTask(
                            task_id=op_id,
                            title=operation.summary,
                            action=lambda: runner._planner._k3s_curl_runner(
                                request
                            ).verify_existing_stack(request.resolved_scenario),
                        )
                    )
                    continue
                if operation.execution_target == "vm":
                    tasks.append(
                        command_task_from_operation(
                            operation, vm_executor, remote_dir=remote_dir
                        )
                    )
                else:
                    resolved = _resolve_host_operation(
                        operation,
                        resolver=resolver,
                        request=request,
                        vm=vm_orch,
                        ip_cache=ip_cache,
                    )
                    tasks.append(command_task_from_operation(resolved, host_executor))

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
