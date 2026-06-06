from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Literal, cast

from multipass import MultipassClient

from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.catalog import ScenarioDefinition, list_scenarios, resolve_scenario
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.workspace.paths import ToolPaths
from controlplane_tool.scenario.components.environment import (
    resolve_scenario_environment,
)
from controlplane_tool.scenario.components.executor import (
    ScenarioPlanStep,
)
from controlplane_tool.scenario.scenarios import ScenarioPlan
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.components.two_vm_loadtest import loadgen_vm_request
from controlplane_tool.scenario.scenario_planner import ScenarioPlanner
from workflow_tasks.shell import (
    ShellBackend,
    SubprocessShell,
)
from workflow_tasks.vm.orchestrator import VmOrchestrator
from workflow_tasks.infra.host_sleep import prevent_host_sleep
from controlplane_tool.infra.vm.vm_models import VmRequest


@dataclass(frozen=True)
class E2ePlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    shell: "ShellBackend | None" = field(default=None, repr=False, compare=False)
    workspace_root: "Path | None" = field(default=None, repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        """Step IDs in execution order, for TUI dry-run planning."""
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        # Local (non-VM) scenarios are plain ordered host commands with no
        # placeholders (request.vm is None). Build a workflow_tasks.Workflow of
        # host CommandTasks and run it: ordered execution, stop on first non-zero
        # exit, run on the host shell with cwd=workspace_root and the step's env.
        # event_listener is unused; the Workflow emits progress via workflow_step.
        del event_listener
        if self.shell is None:
            raise RuntimeError(
                "E2ePlan.run() requires a shell — construct it via E2eRunner.plan()"
            )
        from workflow_tasks import (
            CommandTask,
            CommandTaskSpec,
            HostCommandTaskExecutor,
            Workflow,
        )

        host_executor = HostCommandTaskExecutor(self.shell)
        tasks = [
            CommandTask(
                task_id=step.step_id,
                title=step.summary,
                spec=CommandTaskSpec(
                    task_id=step.step_id,
                    summary=step.summary,
                    argv=tuple(step.command),
                    target="host",
                    env=dict(step.env),
                    cwd=self.workspace_root,
                ),
                executor=host_executor,
            )
            for step in self.steps
        ]
        Workflow(tasks=tasks).run()


ScenarioExecutionStatus = Literal["running", "success", "failed"]


@dataclass(frozen=True)
class ScenarioStepEvent:
    step_index: int
    total_steps: int
    step: ScenarioPlanStep
    status: ScenarioExecutionStatus
    error: str | None = None


class E2eRunner:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        manifest_root: Path | None = None,
        host_resolver: Callable[[VmRequest], str] | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.paths = ToolPaths.repo_root(Path(repo_root))
        self.shell = shell or SubprocessShell()
        self._multipass_client = multipass_client
        self.vm = VmOrchestrator(self.paths.workspace_root, shell=self.shell, multipass_client=multipass_client)
        self.manifest_root = manifest_root or (self.paths.runs_dir / "manifests")
        self._host_resolver = host_resolver
        self._planner = ScenarioPlanner(
            paths=self.paths, vm=self.vm, shell=self.shell, manifest_root=self.manifest_root
        )
        self._resolver = CommandResolver(host_resolver=host_resolver)

    def _prepare_recipe_request(self, request: E2eRequest) -> E2eRequest:
        loadgen_scenarios = {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"}
        try:
            recipe = build_scenario_recipe(request.scenario)
            requires_managed_vm = recipe.requires_managed_vm
        except ValueError:
            recipe = None
            requires_managed_vm = False
        if (request.vm is None and requires_managed_vm) or (
            request.scenario in loadgen_scenarios
            and request.loadgen_vm is None
        ):
            context = resolve_scenario_environment(self.paths.workspace_root, request)
            updates: dict[str, object] = {}
            if request.vm is None and requires_managed_vm:
                updates["vm"] = context.vm_request
            if request.scenario in loadgen_scenarios and request.loadgen_vm is None:
                updates["loadgen_vm"] = loadgen_vm_request(context)
            return request.model_copy(update=updates)
        return request

    def plan(self, request: E2eRequest) -> ScenarioPlan:
        scenario = resolve_scenario(request.scenario)
        if request.runtime not in scenario.supported_runtimes:
            raise ValueError(
                f"Scenario '{request.scenario}' does not support runtime '{request.runtime}'"
            )
        if request.scenario == "two-vm-loadtest":
            from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan
            return build_two_vm_loadtest_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "azure-vm-loadtest":
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
            return build_azure_vm_loadtest_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "proxmox-vm-loadtest":
            from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
            return build_proxmox_vm_loadtest_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "k3s-junit-curl":
            from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
            return build_k3s_junit_curl_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "helm-stack":
            from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan
            return build_helm_stack_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "cli-stack":
            from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan
            return build_cli_stack_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "cli":
            from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
            return build_cli_vm_plan(self, request)
        if request.scenario == "cli-host":
            from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
            return build_cli_host_plan(self, request)
        if scenario.requires_vm:
            raise ValueError(f"Unsupported VM-backed scenario: {request.scenario!r}")
        steps = self._planner.local_steps(request)
        return E2ePlan(
            scenario=scenario,
            request=request,
            steps=steps,
            shell=self.shell,
            workspace_root=self.paths.workspace_root,
        )

    def plan_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        loadgen_vm_request: VmRequest | None = None,
        cleanup_vm: bool = True,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
    ) -> list[ScenarioPlan]:
        only_set = set(only or [])
        skip_set = set(skip or [])
        plans: list[ScenarioPlan] = []
        shared_vm_request = vm_request
        vm_bootstrap_planned = False
        selected_scenarios = [
            scenario
            for scenario in list_scenarios()
            if (not only_set or scenario.name in only_set)
            and scenario.name not in skip_set
            and runtime in scenario.supported_runtimes
        ]
        last_vm_index = max(
            (index for index, scenario in enumerate(selected_scenarios) if scenario.requires_vm),
            default=-1,
        )

        for index, scenario in enumerate(selected_scenarios):
            if only_set and scenario.name not in only_set:
                continue
            if scenario.name in skip_set:
                continue
            if runtime not in scenario.supported_runtimes:
                continue

            if scenario.requires_vm and shared_vm_request is None:
                if scenario.name in {"azure-vm-loadtest", "proxmox-vm-loadtest"}:
                    continue  # cloud-provider scenarios require explicit credentials
                shared_vm_request = VmRequest(lifecycle="multipass")

            loadgen_vm = None
            if scenario.name in {"two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"} and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(
                    lifecycle=shared_vm_request.lifecycle,
                    name=(
                        "nanofaas-e2e-loadgen"
                        if scenario.name == "two-vm-loadtest"
                        else "nanofaas-azure-loadgen"
                        if scenario.name == "azure-vm-loadtest"
                        else "nanofaas-proxmox-loadgen"
                    ),
                    host=shared_vm_request.host,
                    user=shared_vm_request.user,
                    home=shared_vm_request.home,
                    cpus=2,
                    memory="2G",
                    disk="10G",
                    proxmox_host=shared_vm_request.proxmox_host,
                    proxmox_node=shared_vm_request.proxmox_node,
                    proxmox_user=shared_vm_request.proxmox_user,
                    proxmox_password=shared_vm_request.proxmox_password,
                    proxmox_template_id=shared_vm_request.proxmox_template_id,
                    proxmox_ssh_key_path=shared_vm_request.proxmox_ssh_key_path,
                )

            request = E2eRequest(
                scenario=scenario.name,
                runtime=runtime,
                vm=shared_vm_request if scenario.requires_vm else None,
                loadgen_vm=loadgen_vm,
                cleanup_vm=cleanup_vm if index == last_vm_index else False,
                namespace=namespace,
                local_registry=local_registry,
            )
            if scenario.requires_vm:
                if scenario.name == "two-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan
                    plans.append(build_two_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "azure-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
                    plans.append(build_azure_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "proxmox-vm-loadtest":
                    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import build_proxmox_vm_loadtest_plan
                    plans.append(build_proxmox_vm_loadtest_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "k3s-junit-curl":
                    from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
                    plans.append(build_k3s_junit_curl_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "helm-stack":
                    from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan
                    plans.append(build_helm_stack_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "cli-stack":
                    steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli":
                    from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                    plans.append(build_cli_vm_plan(self, request, include_bootstrap=not vm_bootstrap_planned))
                elif scenario.name == "cli-host":
                    from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                    plans.append(build_cli_host_plan(self, request, include_bootstrap=not vm_bootstrap_planned))
                else:
                    raise ValueError(f"Unsupported VM-backed scenario in plan_all(): {scenario.name!r}")
                vm_bootstrap_planned = True
                continue

            plans.append(
                E2ePlan(
                    scenario=scenario,
                    request=request,
                    steps=self._planner.local_steps(request),
                    shell=self.shell,
                    workspace_root=self.paths.workspace_root,
                )
            )
        return plans

    def _should_teardown(self, request: E2eRequest | None) -> bool:
        if request is None or request.vm is None:
            return False
        if request.vm.lifecycle != "multipass" or not request.cleanup_vm:
            return False
        try:
            recipe = build_scenario_recipe(request.scenario)
        except ValueError:
            return True
        return "vm.down" not in recipe.component_ids

    def _recorded_command_count(self) -> int | None:
        commands = getattr(self.shell, "commands", None)
        if isinstance(commands, list):
            return len(commands)
        return None

    def _discard_planning_commands(self, initial_count: int | None) -> None:
        if initial_count is None:
            return
        commands = getattr(self.shell, "commands", None)
        if isinstance(commands, list):
            del commands[initial_count:]

    def execute(
        self,
        plan: E2ePlan,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        succeeded = False
        try:
            plan.run(event_listener=event_listener)
            succeeded = True
        finally:
            teardown_request = plan.request.vm
            if succeeded and teardown_request is not None and self._should_teardown(plan.request):
                self.vm.teardown(teardown_request)

    def run(
        self,
        request: E2eRequest,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        # Keep the host awake for the whole run: idle-sleep drifts the VM clock vs
        # the host and breaks time-windowed Prometheus queries (macOS only; no-op elsewhere).
        with prevent_host_sleep():
            if isinstance(plan, E2ePlan):
                self.execute(plan, event_listener=event_listener)
            else:
                plan.run(event_listener=event_listener)
        return plan

    def run_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        loadgen_vm_request: VmRequest | None = None,
        cleanup_vm: bool = True,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> list[ScenarioPlan]:
        initial_count = self._recorded_command_count()
        plans = self.plan_all(
            only=only,
            skip=skip,
            runtime=runtime,
            vm_request=vm_request,
            loadgen_vm_request=loadgen_vm_request,
            cleanup_vm=cleanup_vm,
            namespace=namespace,
            local_registry=local_registry,
        )
        self._discard_planning_commands(initial_count)
        shared_vm_request = next(
            (
                cast(E2ePlan, plan).request.vm
                for plan in plans
                if isinstance(plan, E2ePlan) and plan.request.vm is not None
            ),
            None,
        )
        # Keep the host awake for the whole multi-scenario run (macOS; no-op elsewhere).
        with prevent_host_sleep():
            succeeded = False
            try:
                for plan in plans:
                    plan.run(event_listener=event_listener)
                succeeded = True
                return plans
            finally:
                final_request = (
                    cast(E2ePlan, plans[-1]).request
                    if plans and isinstance(plans[-1], E2ePlan)
                    else None
                )
                if succeeded and shared_vm_request is not None and self._should_teardown(final_request):
                    self.vm.teardown(shared_vm_request)
