from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Literal, Protocol, cast

from multipass import MultipassClient

from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result, TwoVmLoadtestRunner
from controlplane_tool.scenario.tasks.functions import FunctionSpec, RegisterFunctions
from controlplane_tool.scenario.tasks.loadtest import K6MatrixResult, RunK6Matrix
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.catalog import ScenarioDefinition, list_scenarios, resolve_scenario
from controlplane_tool.e2e.e2e_models import E2eRequest
from workflow_tasks import bind_workflow_context
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
from controlplane_tool.scenario.scenarios import ScenarioPlan
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
)
from controlplane_tool.scenario.components.two_vm_loadtest import loadgen_vm_request
from controlplane_tool.scenario.scenario_planner import ScenarioPlanner
from controlplane_tool.core.shell_backend import (
    ShellBackend,
    SubprocessShell,
)
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from workflow_tasks.workflow.events import WorkflowContext
from workflow_tasks.vm.multipass import repo_rsync_command, repo_sync_ssh_rsh


class _RecipeVmOrchestrator(Protocol):
    def remote_project_dir(self, request: VmRequest) -> str: ...
    def ensure_running(self, request: VmRequest) -> Any: ...
    def teardown(self, request: VmRequest) -> Any: ...
    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> Any: ...
    def connection_host(self, request: VmRequest) -> str: ...


class _ProxmoxRecipeVmOrchestrator(_RecipeVmOrchestrator, Protocol):
    def ssh_endpoint(self, request: VmRequest) -> tuple[str, int]: ...
    def wait_for_ssh(self, request: VmRequest, *, timeout: float = 120.0) -> None: ...
    def ssh_private_key_path(self, request: VmRequest) -> Path | None: ...
    def publish_port(
        self,
        request: VmRequest,
        *,
        service: str,
        guest_port: int,
    ) -> tuple[str, int]: ...


def _command_failure_output(stdout: str | None, stderr: str | None) -> str:
    parts = [part.strip() for part in (stdout, stderr) if part and part.strip()]
    return "\n\n".join(parts)


@dataclass(frozen=True)
class E2ePlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[E2ePlan], None] | None" = field(
        default=None, repr=False, compare=False
    )

    @property
    def task_ids(self) -> list[str]:
        """Step IDs in execution order, for TUI dry-run planning."""
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        if self.executor is None:
            raise RuntimeError(
                "E2ePlan.run() requires an executor — use E2eRunner.execute(plan)"
            )
        self.executor(self)


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
    multipass_client: MultipassClient | None = None,
    component_ids: tuple[str, ...] | None = None,
) -> list[ScenarioPlanStep]:
    context: ScenarioExecutionContext = resolve_scenario_environment(
        repo_root,
        request,
        manifest_root=manifest_root,
        release=release,
    )
    recipe = build_scenario_recipe(scenario_name)
    if component_ids is not None:
        recipe = recipe.__class__(
            name=recipe.name,
            component_ids=component_ids,
            requires_managed_vm=recipe.requires_managed_vm,
        )
    runner = E2eRunner(repo_root, shell=shell, manifest_root=manifest_root, host_resolver=host_resolver, multipass_client=multipass_client)
    cli_context = CliComponentContext(
        repo_root=repo_root,
        release=cast(str, context.release),
        namespace=cast(str, context.namespace),
        local_registry=context.local_registry,
        resolved_scenario=context.resolved_scenario,
        control_plane_endpoint=(
            f"http://127.0.0.1:{TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT}"
            if scenario_name == "two-vm-loadtest"
            else None
        ),
    )
    vm_request = context.vm_request

    # Select the orchestrator based on VM lifecycle.
    vm_orch: _RecipeVmOrchestrator
    if request.vm and request.vm.lifecycle == "azure":
        vm_orch = AzureVmOrchestrator(repo_root)
    elif request.vm and request.vm.lifecycle == "proxmox":
        from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

        vm_orch = ProxmoxVmOrchestrator(repo_root=repo_root)
    else:
        vm_orch = runner.vm

    remote_dir = vm_orch.remote_project_dir(vm_request)
    two_vm_runner = TwoVmLoadtestRunner(
        repo_root=repo_root,
        vm=cast(Any, vm_orch),
        shell=runner.shell,
        host_resolver=host_resolver,
    )
    two_vm_k6_result: TwoVmK6Result | None = None
    two_vm_prometheus_snapshot_path: Path | None = None
    two_vm_k6_matrix_result: K6MatrixResult | None = None

    def _on_ensure_running() -> None:
        vm_orch.ensure_running(vm_request)

    def _on_loadgen_ensure_running() -> None:
        vm_orch.ensure_running(loadgen_vm_request(context))

    def _on_vm_down() -> None:
        vm_orch.teardown(vm_request)

    def _on_loadgen_down() -> None:
        vm_orch.teardown(loadgen_vm_request(context))

    def _on_remote_exec(argv: tuple[str, ...], env: Mapping[str, str]) -> None:
        result = vm_orch.exec_argv(vm_request, argv, env=dict(env), cwd=remote_dir)
        if result.return_code != 0:
            raise RuntimeError(
                _command_failure_output(result.stdout, result.stderr)
                or f"exit {result.return_code}"
            )

    def _run_host_command(command: list[str], env: Mapping[str, str]) -> None:
        result = runner.shell.run(command, cwd=repo_root, env=dict(env))
        if result.return_code != 0:
            raise RuntimeError(
                _command_failure_output(result.stdout, result.stderr)
                or f"exit {result.return_code}"
            )

    def _rewrite_ansible_inventory_for_proxmox(
        argv: tuple[str, ...] | list[str],
    ) -> list[str]:
        if request.vm is None or request.vm.lifecycle != "proxmox":
            return list(argv)
        proxmox_orch = cast(_ProxmoxRecipeVmOrchestrator, vm_orch)
        host, port = proxmox_orch.ssh_endpoint(vm_request)
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{host},"
        rewritten.extend(["-e", f"ansible_port={port}"])
        key = proxmox_orch.ssh_private_key_path(vm_request)
        if key is not None:
            if "--private-key" in rewritten:
                rewritten[rewritten.index("--private-key") + 1] = str(key)
            else:
                rewritten.extend(["--private-key", str(key)])
        return rewritten

    def _repo_sync_command_for_proxmox() -> list[str]:
        proxmox_orch = cast(_ProxmoxRecipeVmOrchestrator, vm_orch)
        host, port = proxmox_orch.ssh_endpoint(vm_request)
        key = proxmox_orch.ssh_private_key_path(vm_request)
        return repo_rsync_command(
            source=repo_root,
            user=vm_request.user,
            host=host,
            destination=remote_dir,
            ssh_rsh=repo_sync_ssh_rsh(key, port=port),
        )

    def _resolve_cp_host() -> str:
        if host_resolver is not None:
            return host_resolver(vm_request)
        return vm_orch.connection_host(vm_request)

    def _on_register_functions() -> None:
        runtime_image_default = f"{context.local_registry}/nanofaas/function-runtime:e2e"
        fn_keys = selected_functions(request.resolved_scenario)
        specs = [
            FunctionSpec(
                name=fn_key,
                image=function_image(fn_key, request.resolved_scenario, runtime_image_default),
            )
            for fn_key in fn_keys
        ]
        if request.vm is not None and request.vm.lifecycle == "proxmox":
            proxmox_orch = cast(_ProxmoxRecipeVmOrchestrator, vm_orch)
            cp_host, cp_port = proxmox_orch.publish_port(
                vm_request,
                service="CONTROL_PLANE_HTTP",
                guest_port=TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
            )
        else:
            cp_host = _resolve_cp_host()
            cp_port = TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT
        cp_url = f"http://{cp_host}:{cp_port}"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=cp_url,
            specs=specs,
        ).run()

    def _on_loadgen_run_k6() -> None:
        nonlocal two_vm_k6_result, two_vm_k6_matrix_result
        matrix_result = RunK6Matrix(
            task_id="loadgen.run_k6",
            title="Run k6 against all targets",
            runner=two_vm_runner,
            request=request,
        ).run()
        two_vm_k6_matrix_result = matrix_result
        if matrix_result.results:
            two_vm_k6_result = matrix_result.results[0]

    def _on_prometheus_snapshot() -> None:
        nonlocal two_vm_prometheus_snapshot_path
        if two_vm_k6_result is None:
            raise RuntimeError("Prometheus snapshots require a completed k6 run")
        two_vm_prometheus_snapshot_path = two_vm_runner.capture_prometheus_snapshots(request, two_vm_k6_result)

    def _on_write_report() -> None:
        if two_vm_k6_result is None:
            raise RuntimeError("two-vm report requires a completed k6 run")
        if two_vm_prometheus_snapshot_path is None:
            raise RuntimeError("two-vm report requires captured Prometheus snapshots")
        two_vm_runner.write_report(request, two_vm_k6_result, two_vm_prometheus_snapshot_path)

    cli_context = CliComponentContext(
        repo_root=Path(remote_dir),
        release=cli_context.release,
        namespace=cli_context.namespace,
        local_registry=cli_context.local_registry,
        resolved_scenario=cli_context.resolved_scenario,
        control_plane_endpoint=cli_context.control_plane_endpoint,
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
        if component.component_id == "loadgen.ensure_running":
            component_steps = [
                ScenarioPlanStep(
                    summary=step.summary,
                    command=step.command,
                    env=step.env,
                    step_id=step.step_id,
                    action=_on_loadgen_ensure_running,
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
                    always_run=True,
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
        if component.component_id == "loadtest.write_report":
            component_steps = [
                ScenarioPlanStep(
                    summary=step.summary,
                    command=step.command,
                    env=step.env,
                    step_id=step.step_id,
                    action=_on_write_report,
                )
                for step in component_steps
            ]
        if (
            component.component_id == "cli.fn_apply_selected"
            and scenario_name in LOADTEST_SCENARIOS
        ):
            component_steps = [
                ScenarioPlanStep(
                    summary="Register selected functions via REST API",
                    command=["python", "-c", "# RegisterFunctions via REST"],
                    step_id="functions.register",
                    action=_on_register_functions,
                )
            ]
        if request.vm is not None and request.vm.lifecycle == "proxmox":
            if component.component_id == "repo.sync_to_vm":
                component_steps = [
                    ScenarioPlanStep(
                        summary=step.summary,
                        command=step.command,
                        env=step.env,
                        step_id=step.step_id,
                        action=lambda env=step.env: _run_host_command(
                            _repo_sync_command_for_proxmox(),
                            env,
                        ),
                    )
                    for step in component_steps
                ]
            if component.component_id in {
                "vm.provision_base",
                "registry.ensure_container",
                "k3s.install",
                "k3s.configure_registry",
            }:
                proxmox_orch_ref = cast(_ProxmoxRecipeVmOrchestrator, vm_orch)
                component_steps = [
                    ScenarioPlanStep(
                        summary=step.summary,
                        command=step.command,
                        env=step.env,
                        step_id=step.step_id,
                        action=lambda original_command=step.command, env=step.env: (
                            proxmox_orch_ref.wait_for_ssh(vm_request)
                            or _run_host_command(
                                _rewrite_ansible_inventory_for_proxmox(original_command),
                                env,
                            )
                        ),
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
        return E2ePlan(scenario=scenario, request=request, steps=steps)

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

            plans.append(E2ePlan(scenario=scenario, request=request, steps=self._planner.local_steps(request)))
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
        plan: E2ePlan,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        ip_cache: dict[str, str] = {}
        total_steps = len(plan.steps)
        deferred_steps = [
            (step_index, step)
            for step_index, step in enumerate(plan.steps, start=1)
            if step.always_run
        ]
        main_error: RuntimeError | None = None
        for step_index, step in enumerate(plan.steps, start=1):
            if step.always_run:
                continue
            try:
                self._execute_step(plan, step_index, total_steps, step, ip_cache, event_listener)
            except RuntimeError as exc:
                main_error = exc
                break

        cleanup_errors: list[str] = []
        for step_index, step in deferred_steps:
            try:
                self._execute_step(plan, step_index, total_steps, step, ip_cache, event_listener)
            except RuntimeError as exc:
                cleanup_errors.append(str(exc))

        if main_error is not None:
            if cleanup_errors:
                msg = str(main_error) + "\n\nCleanup failed:\n" + "\n".join(cleanup_errors)
                raise RuntimeError(msg) from main_error
            raise main_error
        if cleanup_errors:
            raise RuntimeError("Scenario cleanup failed:\n" + "\n".join(cleanup_errors))

    def _execute_step(
        self,
        plan: E2ePlan,
        step_index: int,
        total_steps: int,
        step: ScenarioPlanStep,
        ip_cache: dict[str, str],
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
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
                return
            command = self._resolver._resolve_command(step.command, plan.request.vm, ip_cache, self.vm)
            env = self._resolver._resolve_env(step.env, plan.request.vm, ip_cache, self.vm)
            result = self.shell.run(
                command,
                cwd=self.paths.workspace_root,
                env=env,
                dry_run=False,
            )
            if result.return_code != 0:
                output = _command_failure_output(result.stdout, result.stderr)
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
        plan: E2ePlan,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        succeeded = False
        try:
            self._execute_steps(plan, event_listener=event_listener)
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
        succeeded = False
        try:
            for plan in plans:
                if isinstance(plan, E2ePlan):
                    self._execute_steps(plan, event_listener=event_listener)
                else:
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
