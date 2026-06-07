from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from workflow_tasks import (
    DestroyVm,
    EnsureVmRunning,
    LoadgenBodyInputs,
    Workflow,
    build_loadgen_body_tasks,
    make_loadtest_k6_config,
    workflow_step,
)
from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
from workflow_tasks.components.models import ScenarioRecipe
from workflow_tasks.vm.models import VmConfig
from workflow_tasks.vm.multipass import _find_ssh_private_key_path

from multipass import find_ssh_public_key

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.loadtest.loadtest_adapters import (
    HttpPrometheusClient,
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
from controlplane_tool.scenario.scenarios._workflow_assembly import (
    _Setup,
    build_command_tasks,
    build_setup,
)
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_PROMETHEUS_QUERIES,
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


# Components run on the stack VM before loadgen starts.
# vm.ensure_running is handled separately (EnsureVmRunning task outside the Workflow).
_TWO_VM_STACK_PRELUDE_COMPONENTS: tuple[str, ...] = (
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core",
    "images.build_selected_functions",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
)

# Static task IDs used for TUI dry-run planning and test assertions.
# Component IDs for provisioning phases (expand to multiple operations at runtime).
_TWO_VM_STATIC_TASK_IDS: tuple[str, ...] = (
    "vm.stack.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core",
    "images.build_selected_functions",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "vm.loadgen.ensure_running",
    "loadgen.install_k6",
    "loadgen.run_k6",
    "loadgen.fetch_results",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "vm.loadgen.destroy",
    "vm.stack.destroy",
)


@dataclass
class TwoVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return list(_TWO_VM_STATIC_TASK_IDS)

    @property
    def phase_titles(self) -> list[str]:
        setup = self._build_setup()
        stack_tasks = self._build_stack_prelude_tasks(setup, resolve_host=False)
        return (
            ["Ensure stack VM running"]
            + [t.title for t in stack_tasks]
            + [
                "Ensure loadgen VM running",
                "Install k6 on loadgen VM",
                "Run k6 loadtest",
                "Fetch k6 results from loadgen VM",
                "Capture Prometheus snapshots",
                "Write loadtest report",
                "Destroy loadgen VM",
                "Destroy stack VM",
            ]
        )

    def _build_setup(self) -> _Setup:
        return build_setup(self.runner, self.request)

    def _build_stack_prelude_tasks(self, setup: _Setup, *, resolve_host: bool = True) -> list:
        recipe = ScenarioRecipe(
            name="two-vm-loadtest-stack",
            component_ids=_TWO_VM_STACK_PRELUDE_COMPONENTS,
            requires_managed_vm=True,
        )
        return build_command_tasks(
            self.runner, self.request, setup, recipe, resolve_host=resolve_host
        )

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        setup = self._build_setup()
        request = self.request

        # ── 1. Ensure stack VM running ──────────────────────────────────────────
        ensure_stack = EnsureVmRunning(
            task_id="vm.stack.ensure_running",
            title="Ensure stack VM running",
            lifecycle=setup.lifecycle,
            config=setup.vm_config,
        )
        with workflow_step(task_id=ensure_stack.task_id, title=ensure_stack.title):
            stack_info = ensure_stack.run()

        # ── 2. Stack provisioning (provision, sync, build, k3s, deploy) ────────
        stack_tasks = self._build_stack_prelude_tasks(setup)
        Workflow(tasks=stack_tasks).run()

        # ── 3. Ensure loadgen VM running ────────────────────────────────────────
        lifecycle = setup.lifecycle
        loadgen_config = VmConfig(
            name=request.loadgen_vm.name or "",
            cpus=request.loadgen_vm.cpus,
            memory=request.loadgen_vm.memory,
            disk=request.loadgen_vm.disk,
        )
        ensure_loadgen = EnsureVmRunning(
            task_id="vm.loadgen.ensure_running",
            title="Ensure loadgen VM running",
            lifecycle=lifecycle,
            config=loadgen_config,
        )
        with workflow_step(task_id=ensure_loadgen.task_id, title=ensure_loadgen.title):
            loadgen_info = ensure_loadgen.run()

        # ── 4. Loadgen workflow ─────────────────────────────────────────────────
        vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        remote_home = loadgen_info.home
        remote_paths = two_vm_remote_paths(
            remote_home,
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )
        # Create the loadgen run dirs and upload the k6 script before RunK6 — without
        # this `k6 run` exits immediately ("script.js: No such file") and writes no summary.
        vm_runner_impl.prepare_loadgen(request, remote_paths)
        run_dir = vm_runner_impl._create_run_dir()  # noqa: SLF001
        control_plane_url = two_vm_control_plane_url(request.vm, host=stack_info.host)

        # Register the selected functions on the control plane (REST) before k6 runs.
        # Without this /v1/functions is empty, every invocation 400s, no dispatch
        # happens, and the required Prometheus metrics (function_dispatch_total) have
        # no data — failing the Prometheus-snapshot phase.
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=control_plane_url,
            specs=[
                FunctionSpec(
                    name=fn_key,
                    image=function_image(fn_key, request.resolved_scenario, runtime_image_default),
                )
                for fn_key in selected_functions(request.resolved_scenario)
            ],
        ).run()

        k6_config = make_loadtest_k6_config(
            remote_paths=remote_paths,
            control_plane_url=control_plane_url,
            target_function=two_vm_target_function(request),
            stages=two_vm_load_stages(request),
            vus=request.k6_vus,
            duration=request.k6_duration,
        )

        loadgen_runner = OrchestratorVmRunner(vm_runner_impl.vm, request.loadgen_vm)
        fetcher = VmFileFetcher(vm=vm_runner_impl.vm, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(url=two_vm_prometheus_url(request.vm, host=stack_info.host))

        body = build_loadgen_body_tasks(
            LoadgenBodyInputs(
                task_ids=(
                    "loadgen.install_k6",
                    "loadgen.run_k6",
                    "loadgen.fetch_results",
                    "metrics.prometheus_snapshot",
                    "loadtest.write_report",
                ),
                titles=(
                    "Install k6 on loadgen VM",
                    "Run k6 loadtest",
                    "Fetch k6 results from loadgen VM",
                    "Capture Prometheus snapshots",
                    "Write loadtest report",
                ),
                runner=loadgen_runner,
                fetcher=fetcher,
                prometheus_client=prom_client,
                prometheus_queries=LOADTEST_PROMETHEUS_QUERIES,
                k6_config=k6_config,
                remote_dir=remote_home,
                remote_summary_path=remote_paths.summary_path,
                run_dir=run_dir,
                repo_root=self.runner.paths.workspace_root,
                shell=self.runner.shell,
                install_host=loadgen_info.host,
                install_user=request.loadgen_vm.user,
                install_private_key=_find_ssh_private_key_path(find_ssh_public_key()),
            )
        )

        cleanup: list = []
        if getattr(request, "cleanup_vm", True):
            cleanup = [
                DestroyVm(
                    task_id="vm.loadgen.destroy",
                    title="Destroy loadgen VM",
                    lifecycle=lifecycle,
                    info=loadgen_info,
                ),
                DestroyVm(
                    task_id="vm.stack.destroy",
                    title="Destroy stack VM",
                    lifecycle=setup.lifecycle,
                    info=stack_info,
                ),
            ]

        Workflow(tasks=body, cleanup_tasks=cleanup).run()


def build_two_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> TwoVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("two-vm-loadtest")
    return TwoVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
