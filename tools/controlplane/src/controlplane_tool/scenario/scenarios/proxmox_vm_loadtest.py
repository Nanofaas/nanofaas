from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, cast

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.cli import CliComponentContext
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.connectivity import ProxmoxConnectivity
from controlplane_tool.scenario.loadtest_adapter import ProxmoxLoadtestAdapter
from controlplane_tool.scenario.loadtest_flow import (
    loadtest_flow_phase_titles,
    loadtest_flow_task_ids,
    run_loadtest_flow,
)
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
from controlplane_tool.scenario.scenarios._workflow_assembly import (
    HANDLED,
    CallableTask,
    build_command_tasks,
    build_setup,
)
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
)
from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


# Components run on the stack VM before loadgen starts.
# vm.ensure_running is handled separately (EnsureVmRunning task outside the prelude
# Workflow) by the unified driver, so it is NOT part of the prelude recipe.
_PROXMOX_LOADTEST_PRELUDE_COMPONENTS = (
    "vm.ensure_running",
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
    "cli.build_install_dist",
    "cli.fn_apply_selected",
)


@dataclass
class ProxmoxVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    # ── unified-driver delegation ────────────────────────────────────────────

    @property
    def task_ids(self) -> list[str]:
        return loadtest_flow_task_ids(
            runner=self.runner,
            request=self.request,
            setup=build_setup(self.runner, self.request),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    @property
    def phase_titles(self) -> list[str]:
        return loadtest_flow_phase_titles(
            runner=self.runner,
            request=self.request,
            setup=build_setup(self.runner, self.request),
            recipe=self._recipe(),
            adapter=self._adapter(),
        )

    def _adapter(self) -> ProxmoxLoadtestAdapter:
        return ProxmoxLoadtestAdapter(runner=self.runner, request=self.request)

    def _recipe(self):
        """The proxmox prelude recipe minus ``vm.ensure_running``.

        ``vm.ensure_running`` is executed separately by the unified driver as an
        ``EnsureVmRunning`` task; the recipe is the remaining provision/deploy
        components in their canonical order (this is exactly the recipe
        ``_build_prelude_tasks`` builds for the prelude argv oracle).
        """
        prelude_components = tuple(
            cid for cid in _PROXMOX_LOADTEST_PRELUDE_COMPONENTS if cid != "vm.ensure_running"
        )
        recipe = build_scenario_recipe("proxmox-vm-loadtest")
        return recipe.__class__(
            name=recipe.name,
            component_ids=prelude_components,
            requires_managed_vm=recipe.requires_managed_vm,
        )

    def run(self, event_listener=None) -> None:
        run_loadtest_flow(
            runner=self.runner,
            request=self.request,
            setup=build_setup(self.runner, self.request),
            recipe=self._recipe(),
            adapter=self._adapter(),
            event_listener=event_listener,
        )

    # ── prelude argv oracle support (consumed by the hard-invariant tests) ────
    # These methods are independent of run(): they reproduce the legacy proxmox
    # prelude CommandTasks (ansible/rsync/register rewrites) so the prelude argv
    # golden (test_proxmox_prelude_argv.py) and the prelude workflow snapshot
    # (test_proxmox_prelude_workflow.py) stay pinned. The unified driver builds
    # the identical tasks via the adapter (ProxmoxConnectivity +
    # prelude_special_handler/context_selector); this is the same assembly,
    # surfaced here for the goldens.

    def _requests(self) -> tuple[VmRequest, VmRequest]:
        if self.request.vm is None:
            raise ValueError("proxmox-vm-loadtest requires a stack VM request")
        if self.request.loadgen_vm is None:
            raise ValueError("proxmox-vm-loadtest requires a loadgen VM request")
        return self.request.vm, self.request.loadgen_vm

    @property
    def prelude_tasks(self) -> list:
        from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

        stack_request, _ = self._requests()
        proxmox_orch = ProxmoxVmOrchestrator(repo_root=self.runner.paths.workspace_root)
        return self._build_prelude_tasks(proxmox_orch, stack_request)

    @property
    def prelude_task_ids(self) -> list[str]:
        return ["vm.ensure_running"] + [t.task_id for t in self.prelude_tasks]

    def _build_prelude_tasks(
        self, proxmox_orch, stack_request: VmRequest, *, resolve_host: bool = True
    ) -> list:
        """Assemble the honest prelude Tasks against a (running) proxmox orch.

        Mirrors exactly what the unified driver builds for the prelude: the
        ``proxmox-vm-loadtest`` recipe minus ``vm.ensure_running``, with the
        proxmox rewrites (ansible inventory / repo rsync / functions.register)
        applied through ``ProxmoxConnectivity`` + special_handler/context_selector.
        """
        repo_root = self.runner.paths.workspace_root
        request = self.request

        context = resolve_scenario_environment(
            repo_root, request, manifest_root=self.runner.manifest_root
        )

        if resolve_host:
            remote_dir = proxmox_orch.remote_project_dir(stack_request)
            host, port = proxmox_orch.ssh_endpoint(stack_request)
            key = proxmox_orch.ssh_private_key_path(stack_request)
        else:
            remote_dir = f"/home/{stack_request.user or 'ubuntu'}/nanofaas"
            host, port = "<proxmox-host>", 0
            key = None

        cli_context = CliComponentContext(
            repo_root=Path(remote_dir),
            release=cast(str, context.release),
            namespace=cast(str, context.namespace),
            local_registry=context.local_registry,
            resolved_scenario=context.resolved_scenario,
            control_plane_endpoint=None,
        )

        connectivity = ProxmoxConnectivity(
            orchestrator=proxmox_orch,
            request=stack_request,
            host=host,
            port=port,
            key=key,
            repo_root=repo_root,
            remote_dir_value=remote_dir,
        )

        setup = build_setup(self.runner, request)
        registered = {"done": False}

        def special_handler(operation):
            if (
                operation.operation_id.startswith("cli.fn_apply_selected")
                and request.scenario in LOADTEST_SCENARIOS
            ):
                if not registered["done"]:
                    registered["done"] = True
                    return CallableTask(
                        task_id="functions.register",
                        title="Register selected functions via REST API",
                        action=self._register_functions_action(proxmox_orch, stack_request, context),
                    )
                return HANDLED
            return None

        def context_selector(component):
            return cli_context if component.component_id.startswith("cli.") else context

        return build_command_tasks(
            self.runner,
            request,
            setup,
            self._recipe(),
            special_handler=special_handler,
            context_selector=context_selector,
            connectivity=connectivity,
            resolve_host=True,
        )

    def _register_functions_action(self, proxmox_orch, stack_request, context):
        request = self.request

        def action() -> None:
            runtime_image_default = (
                f"{context.local_registry}/nanofaas/function-runtime:e2e"
            )
            fn_keys = selected_functions(request.resolved_scenario)
            specs = [
                FunctionSpec(
                    name=fn_key,
                    image=function_image(
                        fn_key, request.resolved_scenario, runtime_image_default
                    ),
                )
                for fn_key in fn_keys
            ]
            cp_host, cp_port = proxmox_orch.publish_port(
                stack_request,
                service="CONTROL_PLANE_HTTP",
                guest_port=TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
            )
            cp_url = f"http://{cp_host}:{cp_port}"
            RegisterFunctions(
                task_id="functions.register",
                title="Register functions",
                control_plane_url=cp_url,
                specs=specs,
            ).run()

        return action


def build_proxmox_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> ProxmoxVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("proxmox-vm-loadtest")
    return ProxmoxVmLoadtestPlan(
        scenario=scenario,
        request=request,
        steps=[],
        runner=runner,
    )
