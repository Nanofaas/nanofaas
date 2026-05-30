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
from workflow_tasks.vm.models import VmConfig, VmInfo

from controlplane_tool.e2e.e2e_models import E2eRequest
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
class K3sCurlVerifyTask:
    """Honest Task wrapper for the k3s-junit-curl verification step.

    Reproduces the legacy `on_k3s_curl_verify` callback:
    `runner._planner._k3s_curl_runner(request).verify_existing_stack(...)`.
    """

    task_id: str
    title: str
    runner: "E2eRunner" = field(repr=False, compare=False)
    request: E2eRequest = field(repr=False, compare=False)

    def run(self) -> None:
        self.runner._planner._k3s_curl_runner(self.request).verify_existing_stack(
            self.request.resolved_scenario
        )


def _resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
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
        """Ordered task_ids of the honest Workflow (tasks + cleanup_tasks)."""
        # The info for the (possible) DestroyVm cleanup task is irrelevant to the
        # id list, so a placeholder VmInfo is fine here.
        workflow = self._assemble(lambda: VmInfo(name="", host="", user="", home=""))
        return workflow.task_ids

    # ── workflow assembly ───────────────────────────────────────────────────────

    def _assemble(self, vm_info: "Callable[[], VmInfo]") -> Workflow:
        """Build the Workflow of honest Tasks for this scenario.

        *vm_info* is called lazily to supply the resolved VmInfo for the DestroyVm
        cleanup task (it is only resolved if cleanup_vm is True).
        """
        runner = self.runner
        request = self.request

        context = resolve_scenario_environment(runner.paths.workspace_root, request)
        vm_request = context.vm_request
        vm_orch = runner.vm
        remote_dir = vm_orch.remote_project_dir(vm_request)

        lifecycle = MultipassVmAdapter(vm_orch)
        vm_config = VmConfig(
            name=vm_request.name or "",
            cpus=vm_request.cpus,
            memory=vm_request.memory,
            disk=vm_request.disk,
        )

        host_executor = HostCommandTaskExecutor(runner.shell)
        vm_executor = VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request))
        resolver = CommandResolver(host_resolver=runner._host_resolver)
        ip_cache: dict[str, str] = {}

        recipe = build_scenario_recipe("k3s-junit-curl")
        tasks: list = [
            EnsureVmRunning(
                task_id="vm.ensure_running",
                title="Ensure VM is running",
                lifecycle=lifecycle,
                config=vm_config,
            )
        ]
        cleanup_tasks: list = []

        for component in compose_recipe(recipe):
            for operation in component.planner(context):
                op_id = operation.operation_id
                if op_id == "vm.ensure_running":
                    continue  # already added as EnsureVmRunning
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
                    continue
                if op_id == "tests.run_k3s_curl_checks":
                    tasks.append(
                        K3sCurlVerifyTask(
                            task_id=op_id,
                            title=operation.summary,
                            runner=runner,
                            request=request,
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
        # Run vm.ensure_running first so the resolved host is available for the
        # DestroyVm cleanup task and for placeholder substitution.
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
        ensure_vm = EnsureVmRunning(
            task_id="vm.ensure_running",
            title="Ensure VM is running",
            lifecycle=lifecycle,
            config=vm_config,
        )
        with workflow_step(task_id=ensure_vm.task_id, title=ensure_vm.title):
            info = ensure_vm.run()

        # The first task in the assembled workflow is the (already-run)
        # EnsureVmRunning; drop it so it does not run twice.
        workflow = self._assemble(lambda: info)
        workflow.tasks = workflow.tasks[1:]
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
