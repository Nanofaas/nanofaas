from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Literal

from multipass import MultipassClient

from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result, TwoVmLoadtestRunner
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.catalog import ScenarioDefinition, list_scenarios, resolve_scenario
from controlplane_tool.e2e.e2e_models import E2eRequest
from tui_toolkit import bind_workflow_context
from controlplane_tool.workspace.paths import ToolPaths
from controlplane_tool.scenario.components.cli import CliComponentContext
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.environment import (
    ScenarioExecutionContext,
    resolve_scenario_environment,
)
from controlplane_tool.scenario.components.executor import (
    ScenarioPlanStep,
    operations_to_plan_steps,
)
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.components.two_vm_loadtest import loadgen_vm_request
from controlplane_tool.scenario.scenario_planner import ScenarioPlanner
from controlplane_tool.core.shell_backend import (
    ShellBackend,
    SubprocessShell,
)
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.workflow.workflow_models import WorkflowContext


@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]


ScenarioExecutionStatus = Literal["running", "success", "failed"]


@dataclass(frozen=True)
class ScenarioStepEvent:
    step_index: int
    total_steps: int
    step: ScenarioPlanStep
    status: ScenarioExecutionStatus
    error: str | None = None

def plan_recipe_steps(
    repo_root: Path,
    request: E2eRequest,
    scenario_name: str,
    *,
    shell: ShellBackend | None = None,
    release: str | None = None,
    manifest_root: Path | None = None,
    host_resolver: Callable[[VmRequest], str] | None = None,
) -> list[ScenarioPlanStep]:
    context: ScenarioExecutionContext = resolve_scenario_environment(
        repo_root,
        request,
        manifest_root=manifest_root,
        release=release,
    )
    recipe = build_scenario_recipe(scenario_name)
    runner = E2eRunner(repo_root, shell=shell, manifest_root=manifest_root, host_resolver=host_resolver)
    cli_context = CliComponentContext(
        repo_root=repo_root,
        release=context.release,
        namespace=context.namespace,
        local_registry=context.local_registry,
        resolved_scenario=context.resolved_scenario,
    )
    vm_request = context.vm_request
    remote_dir = runner.vm.remote_project_dir(vm_request)
    two_vm_runner = TwoVmLoadtestRunner(
        repo_root=repo_root,
        vm=runner.vm,
        shell=runner.shell,
        host_resolver=host_resolver,
    )
    two_vm_k6_result: TwoVmK6Result | None = None

    def _on_ensure_running() -> None:
        runner.vm.ensure_running(vm_request)

    def _on_vm_down() -> None:
        runner.vm.teardown(vm_request)

    def _on_loadgen_down() -> None:
        runner.vm.teardown(loadgen_vm_request(context))

    def _on_remote_exec(argv: tuple[str, ...], env: Mapping[str, str]) -> None:
        result = runner.vm.exec_argv(vm_request, argv, env=dict(env), cwd=remote_dir)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")

    def _on_loadgen_run_k6() -> None:
        nonlocal two_vm_k6_result
        two_vm_k6_result = two_vm_runner.run_k6(request)

    def _on_prometheus_snapshot() -> None:
        if two_vm_k6_result is None:
            raise RuntimeError("Prometheus snapshots require a completed k6 run")
        two_vm_runner.capture_prometheus_snapshots(request, two_vm_k6_result)

    cli_context = CliComponentContext(
        repo_root=Path(remote_dir),
        release=cli_context.release,
        namespace=cli_context.namespace,
        local_registry=cli_context.local_registry,
        resolved_scenario=cli_context.resolved_scenario,
    )

    steps: list[ScenarioPlanStep] = []
    for component in compose_recipe(recipe):
        planner_context: object = cli_context if component.component_id.startswith("cli.") else context
        operations = component.planner(planner_context)
        component_steps = operations_to_plan_steps(
            operations,
            request=request,
            on_k3s_curl_verify=lambda: runner._planner._k3s_curl_runner(request).verify_existing_stack(
                request.resolved_scenario
            ),
            on_ensure_running=_on_ensure_running,
            on_vm_down=_on_vm_down,
            on_remote_exec=_on_remote_exec,
        )
        if component.component_id == "loadgen.run_k6":
            component_steps = [
                ScenarioPlanStep(
                    summary=step.summary,
                    command=step.command,
                    env=step.env,
                    step_id=step.step_id,
                    action=_on_loadgen_run_k6,
                )
                for step in component_steps
            ]
        if component.component_id == "loadgen.down":
            component_steps = [
                ScenarioPlanStep(
                    summary=step.summary,
                    command=["echo", "Skipping loadgen VM teardown (--no-cleanup-vm)"],
                    step_id=step.step_id,
                )
                if not request.cleanup_vm
                else ScenarioPlanStep(
                    summary=step.summary,
                    command=step.command,
                    env=step.env,
                    step_id=step.step_id,
                    action=_on_loadgen_down,
                )
                for step in component_steps
            ]
        if component.component_id == "metrics.prometheus_snapshot":
            component_steps = [
                ScenarioPlanStep(
                    summary=step.summary,
                    command=step.command,
                    env=step.env,
                    step_id=step.step_id,
                    action=_on_prometheus_snapshot,
                )
                for step in component_steps
            ]
        steps.extend(component_steps)
    return steps


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
        self.vm = VmOrchestrator(self.paths.workspace_root, shell=self.shell, multipass_client=multipass_client)
        self.manifest_root = manifest_root or (self.paths.runs_dir / "manifests")
        self._host_resolver = host_resolver
        self._planner = ScenarioPlanner(
            paths=self.paths, vm=self.vm, shell=self.shell, manifest_root=self.manifest_root
        )
        self._resolver = CommandResolver(host_resolver=host_resolver)

    def plan(self, request: E2eRequest) -> ScenarioPlan:
        scenario = resolve_scenario(request.scenario)
        if request.runtime not in scenario.supported_runtimes:
            raise ValueError(
                f"Scenario '{request.scenario}' does not support runtime '{request.runtime}'"
            )
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack", "two-vm-loadtest"}:
            plan_request = request
            recipe = build_scenario_recipe(request.scenario)
            if (request.vm is None and recipe.requires_managed_vm) or (
                request.scenario == "two-vm-loadtest" and request.loadgen_vm is None
            ):
                context = resolve_scenario_environment(self.paths.workspace_root, request)
                updates: dict[str, object] = {}
                if request.vm is None and recipe.requires_managed_vm:
                    updates["vm"] = context.vm_request
                if request.scenario == "two-vm-loadtest" and request.loadgen_vm is None:
                    updates["loadgen_vm"] = loadgen_vm_request(context)
                plan_request = request.model_copy(update=updates)
            return ScenarioPlan(
                scenario=scenario,
                request=plan_request,
                steps=plan_recipe_steps(
                    self.paths.workspace_root,
                    plan_request,
                    request.scenario,
                    shell=self.shell,
                    manifest_root=self.manifest_root,
                    host_resolver=self._host_resolver,
                ),
            )
        steps = (
            self._planner.vm_backed_steps(request)
            if scenario.requires_vm
            else self._planner.local_steps(request)
        )
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)

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
                shared_vm_request = VmRequest(lifecycle="multipass")

            loadgen_vm = None
            if scenario.name == "two-vm-loadtest" and shared_vm_request is not None:
                loadgen_vm = loadgen_vm_request or VmRequest(
                    lifecycle=shared_vm_request.lifecycle,
                    name="nanofaas-e2e-loadgen",
                    host=shared_vm_request.host,
                    user=shared_vm_request.user,
                    home=shared_vm_request.home,
                    cpus=2,
                    memory="2G",
                    disk="10G",
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
                    steps = plan_recipe_steps(
                        self.paths.workspace_root,
                        request,
                        scenario.name,
                        shell=self.shell,
                        manifest_root=self.manifest_root,
                        host_resolver=self._host_resolver,
                    )
                    vm_bootstrap_planned = True
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                    continue
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue

            plans.append(ScenarioPlan(scenario=scenario, request=request, steps=self._planner.local_steps(request)))
        return plans

    def _emit_event(
        self,
        event_listener: Callable[[ScenarioStepEvent], None] | None,
        *,
        step_index: int,
        total_steps: int,
        step: ScenarioPlanStep,
        status: ScenarioExecutionStatus,
        error: str | None = None,
    ) -> None:
        if event_listener is None:
            return
        event_listener(
            ScenarioStepEvent(
                step_index=step_index,
                total_steps=total_steps,
                step=step,
                status=status,
                error=error,
            )
        )

    def _require_step_id(self, step: ScenarioPlanStep) -> str:
        if not step.step_id:
            raise ValueError(f"Scenario step '{step.summary}' is missing a stable step_id")
        return step.step_id

    def _execute_steps(
        self,
        plan: ScenarioPlan,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        ip_cache: dict[str, str] = {}
        total_steps = len(plan.steps)
        for step_index, step in enumerate(plan.steps, start=1):
            step_id = self._require_step_id(step)
            self._emit_event(
                event_listener,
                step_index=step_index,
                total_steps=total_steps,
                step=step,
                status="running",
            )
            with bind_workflow_context(WorkflowContext(flow_id=plan.request.scenario, task_id=step_id)):
                if step.action is not None:
                    try:
                        step.action()
                    except Exception as exc:
                        self._emit_event(
                            event_listener,
                            step_index=step_index,
                            total_steps=total_steps,
                            step=step,
                            status="failed",
                            error=str(exc),
                        )
                        raise RuntimeError(
                            f"Scenario '{plan.request.scenario}' failed at step '{step.summary}': {exc}"
                        ) from exc
                    self._emit_event(
                        event_listener,
                        step_index=step_index,
                        total_steps=total_steps,
                        step=step,
                        status="success",
                    )
                    continue
                command = self._resolver._resolve_command(step.command, plan.request.vm, ip_cache, self.vm)
                env = self._resolver._resolve_env(step.env, plan.request.vm, ip_cache, self.vm)
                result = self.shell.run(
                    command,
                    cwd=self.paths.workspace_root,
                    env=env,
                    dry_run=False,
                )
                if result.return_code != 0:
                    output = (result.stderr or result.stdout or "").strip()
                    self._emit_event(
                        event_listener,
                        step_index=step_index,
                        total_steps=total_steps,
                        step=step,
                        status="failed",
                        error=output or f"exit {result.return_code}",
                    )
                    msg = f"Scenario '{plan.request.scenario}' failed at step '{step.summary}' (exit {result.return_code})"
                    if output:
                        msg += f"\n\n{output}"
                    raise RuntimeError(msg)
                self._emit_event(
                    event_listener,
                    step_index=step_index,
                    total_steps=total_steps,
                    step=step,
                    status="success",
                )

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
        plan: ScenarioPlan,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        succeeded = False
        try:
            self._execute_steps(plan, event_listener=event_listener)
            succeeded = True
        finally:
            if succeeded and self._should_teardown(plan.request):
                self.vm.teardown(plan.request.vm)

    def run(
        self,
        request: E2eRequest,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        self.execute(plan, event_listener=event_listener)
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
            (plan.request.vm for plan in plans if plan.request.vm is not None),
            None,
        )
        succeeded = False
        try:
            for plan in plans:
                self._execute_steps(plan)
            succeeded = True
            return plans
        finally:
            final_request = plans[-1].request if plans else None
            if succeeded and self._should_teardown(final_request):
                self.vm.teardown(shared_vm_request)
