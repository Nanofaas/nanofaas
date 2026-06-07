"""Per-lifecycle loadtest connectivity adapter.

A LoadtestConnectivityAdapter composes a generic ConnectivityStrategy (for the
provision prelude via build_command_tasks) and supplies the loadtest-only pieces
that differ between multipass/proxmox/azure: VM lifecycles, the loadgen install
endpoint, runner/fetcher, control-plane/prometheus URLs, an optional script-upload
hook, lifecycle-specific extra steps, and the display title suffix.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Optional, Protocol

from workflow_tasks.loadtest.ports import RemoteFileFetcher
from workflow_tasks.tasks.executors import VmCommandRunner
from workflow_tasks.vm.multipass import _find_ssh_private_key_path

from multipass import find_ssh_public_key

from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import (
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.connectivity import ConnectivityStrategy, MultipassConnectivity
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext
from controlplane_tool.scenario.scenarios._workflow_assembly import SpecialHandler
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_prometheus_url,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios._workflow_assembly import _Setup


@dataclass
class InstallEndpoint:
    """SSH endpoint for install_k6_task. ``port`` is None when the lifecycle uses the default."""

    host: str
    user: str
    private_key: Path | None
    port: int | None = None


class LoadtestConnectivityAdapter(Protocol):
    title_suffix: str
    connectivity: ConnectivityStrategy

    def connectivity_for(
        self, ctx: "RunContext | None", *, resolve_host: bool
    ) -> ConnectivityStrategy: ...
    def stack_lifecycle(self): ...
    def loadgen_lifecycle(self): ...
    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint: ...
    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner: ...
    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher: ...
    def control_plane_url(self, ctx: RunContext) -> str: ...
    def prometheus_url(self, ctx: RunContext) -> str: ...
    def prepare_loadgen(self, ctx: RunContext) -> None: ...
    def create_run_dir(self) -> Path: ...
    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list: ...
    def extra_step_ids(self, phase: FlowPhase) -> list: ...
    def emits_step_events(self) -> bool: ...
    def cleanup_on_failure(self, error: Exception) -> list[str]: ...
    def prelude_special_handler(self, ctx: RunContext) -> Optional[SpecialHandler]: ...
    def prelude_context_selector(
        self, ctx: RunContext, *, resolve_host: bool = True
    ) -> Optional[object]: ...
    def register_functions(self, ctx: RunContext) -> None: ...
    def extra_step_titles(self, phase: FlowPhase) -> list[str]: ...


@dataclass
class MultipassLoadtestAdapter:
    """Multipass: reproduces two-vm's run() connectivity exactly."""

    runner: "E2eRunner"
    request: "E2eRequest"
    title_suffix: str = ""
    connectivity: ConnectivityStrategy = field(init=False)
    _vm_runner_impl: object = field(default=None, init=False, repr=False)
    _cached_setup: Optional["_Setup"] = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.connectivity = MultipassConnectivity(runner=self.runner, request=self.request)

    def connectivity_for(
        self, ctx: "RunContext | None", *, resolve_host: bool
    ) -> ConnectivityStrategy:
        # Multipass resolves IPs lazily inside build_command_tasks; resolve_host is
        # honored there via the call site, so the static MultipassConnectivity is
        # correct regardless of ctx/resolve_host.
        return self.connectivity

    def _runner_impl(self):
        if self._vm_runner_impl is None:
            from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

            self._vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        return self._vm_runner_impl

    def stack_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint:
        return InstallEndpoint(
            host=ctx.loadgen_info.host,
            user=self.request.loadgen_vm.user,
            private_key=_find_ssh_private_key_path(find_ssh_public_key()),
            port=None,
        )

    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner:
        return OrchestratorVmRunner(self._runner_impl().vm, self.request.loadgen_vm)

    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher:
        return VmFileFetcher(vm=self._runner_impl().vm, request=self.request.loadgen_vm)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self.request.vm, host=ctx.stack_info.host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return two_vm_prometheus_url(self.request.vm, host=ctx.stack_info.host)

    def prepare_loadgen(self, ctx: RunContext) -> None:
        self._runner_impl().prepare_loadgen(self.request, ctx.remote_paths)

    def create_run_dir(self) -> Path:
        return self._runner_impl()._create_run_dir()  # noqa: SLF001

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        return []

    def extra_step_ids(self, phase: FlowPhase) -> list:
        return []

    # ── New optional-capability methods ────────────────────────────────────

    def emits_step_events(self) -> bool:
        return False

    def cleanup_on_failure(self, error: Exception) -> list[str]:
        return []

    def prelude_special_handler(self, ctx: RunContext) -> Optional[SpecialHandler]:
        return None

    def prelude_context_selector(
        self, ctx: RunContext, *, resolve_host: bool = True
    ) -> Optional[object]:
        return None

    def extra_step_titles(self, phase: FlowPhase) -> list[str]:
        return []

    def register_functions(self, ctx: RunContext) -> None:
        from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
        from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions

        setup = self._setup()
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=ctx.control_plane_url,
            specs=[
                FunctionSpec(
                    name=fn_key,
                    image=function_image(fn_key, self.request.resolved_scenario, runtime_image_default),
                )
                for fn_key in selected_functions(self.request.resolved_scenario)
            ],
        ).run()

    def _setup(self) -> "_Setup":
        """Lazily build-and-cache the shared _Setup (avoids double-build when driver provides it)."""
        if self._cached_setup is None:
            from controlplane_tool.scenario.scenarios._workflow_assembly import build_setup
            self._cached_setup = build_setup(self.runner, self.request)
        return self._cached_setup


@dataclass
class ProxmoxLoadtestAdapter:
    """Proxmox: rewrites host-ops onto the published NAT SSH endpoint and publishes
    NAT ports for the control plane (in the prelude special_handler, alongside
    function registration) and prometheus (in a BEFORE_LOADGEN extra step).

    Mirrors the connectivity/registration/publish/cleanup pieces of the legacy
    ``ProxmoxVmLoadtestPlan``. Constructed lazily over a
    ``ProxmoxVmOrchestrator(repo_root=runner.paths.workspace_root)``.
    """

    runner: "E2eRunner"
    request: "E2eRequest"
    title_suffix: str = " (Proxmox)"
    connectivity: ConnectivityStrategy = field(default=None, init=False)  # type: ignore[assignment]
    _proxmox_orch: object = field(default=None, init=False, repr=False)
    _published_cp: "tuple[str, int] | None" = field(default=None, init=False, repr=False)

    def _orch(self):
        if self._proxmox_orch is None:
            from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

            self._proxmox_orch = ProxmoxVmOrchestrator(
                repo_root=self.runner.paths.workspace_root
            )
        return self._proxmox_orch

    @property
    def _stack_request(self):
        if self.request.vm is None:
            raise ValueError("proxmox loadtest requires a stack VM request")
        return self.request.vm

    @property
    def _loadgen_request(self):
        if self.request.loadgen_vm is None:
            raise ValueError("proxmox loadtest requires a loadgen VM request")
        return self.request.loadgen_vm

    # ── connectivity ────────────────────────────────────────────────────────

    def connectivity_for(
        self, ctx: Optional[RunContext], *, resolve_host: bool
    ) -> ConnectivityStrategy:
        from controlplane_tool.scenario.connectivity import ProxmoxConnectivity

        orch = self._orch()
        stack_request = self._stack_request
        if resolve_host:
            remote_dir = orch.remote_project_dir(stack_request)
            host, port = orch.ssh_endpoint(stack_request)
            key = orch.ssh_private_key_path(stack_request)
        else:
            remote_dir = f"/home/{stack_request.user or 'ubuntu'}/nanofaas"
            host, port = "<proxmox-host>", 0
            key = None
        return ProxmoxConnectivity(
            orchestrator=orch,
            request=stack_request,
            host=host,
            port=port,
            key=key,
            repo_root=self.runner.paths.workspace_root,
            remote_dir_value=remote_dir,
        )

    # ── prelude special handler + context selector ──────────────────────────

    def _resolve_context(self):
        from controlplane_tool.scenario.components.environment import (
            resolve_scenario_environment,
        )

        return resolve_scenario_environment(
            self.runner.paths.workspace_root,
            self.request,
            manifest_root=self.runner.manifest_root,
        )

    def prelude_special_handler(self, ctx: RunContext) -> Optional[SpecialHandler]:
        from controlplane_tool.scenario.scenarios._workflow_assembly import (
            HANDLED,
            CallableTask,
        )
        from controlplane_tool.scenario.two_vm_loadtest_config import LOADTEST_SCENARIOS

        context = self._resolve_context()
        registered = {"done": False}

        def special_handler(operation):
            if (
                operation.operation_id.startswith("cli.fn_apply_selected")
                and self.request.scenario in LOADTEST_SCENARIOS
            ):
                if not registered["done"]:
                    registered["done"] = True
                    return CallableTask(
                        task_id="functions.register",
                        title="Register selected functions via REST API",
                        action=self._register_functions_action(context),
                    )
                return HANDLED
            return None

        return special_handler

    def _register_functions_action(self, context):
        from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
        from controlplane_tool.scenario.scenario_helpers import (
            function_image,
            selected_functions,
        )
        from controlplane_tool.scenario.two_vm_loadtest_config import (
            TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
        )

        request = self.request
        orch = self._orch()
        stack_request = self._stack_request

        def action() -> None:
            runtime_image_default = (
                f"{context.local_registry}/nanofaas/function-runtime:e2e"
            )
            specs = [
                FunctionSpec(
                    name=fn_key,
                    image=function_image(
                        fn_key, request.resolved_scenario, runtime_image_default
                    ),
                )
                for fn_key in selected_functions(request.resolved_scenario)
            ]
            cp_host, cp_port = orch.publish_port(
                stack_request,
                service="CONTROL_PLANE_HTTP",
                guest_port=TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
            )
            self._published_cp = (cp_host, cp_port)
            cp_url = f"http://{cp_host}:{cp_port}"
            RegisterFunctions(
                task_id="functions.register",
                title="Register functions",
                control_plane_url=cp_url,
                specs=specs,
            ).run()

        return action

    def prelude_context_selector(
        self, ctx: RunContext, *, resolve_host: bool = True
    ) -> Optional[object]:
        from pathlib import Path as _Path
        from typing import cast

        from controlplane_tool.scenario.components.cli import CliComponentContext

        context = self._resolve_context()
        conn = self.connectivity_for(ctx, resolve_host=resolve_host)
        cli_context = CliComponentContext(
            repo_root=_Path(conn.remote_dir_value),
            release=cast(str, context.release),
            namespace=cast(str, context.namespace),
            local_registry=context.local_registry,
            resolved_scenario=context.resolved_scenario,
            control_plane_endpoint=None,
        )

        def context_selector(component):
            return cli_context if component.component_id.startswith("cli.") else context

        return context_selector

    def register_functions(self, ctx: RunContext) -> None:
        # No-op: registration is performed in the prelude via the special_handler.
        return None

    # ── lifecycles ───────────────────────────────────────────────────────────

    def stack_lifecycle(self):
        from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter

        return ProxmoxVmAdapter(self._orch(), credentials=self._stack_request)

    def loadgen_lifecycle(self):
        from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter

        return ProxmoxVmAdapter(self._orch(), credentials=self._loadgen_request)

    # ── loadgen body collaborators ─────────────────────────────────────────────

    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint:
        orch = self._orch()
        loadgen_request = self._loadgen_request
        host, port = orch.ssh_endpoint(loadgen_request)
        return InstallEndpoint(
            host=host,
            user=loadgen_request.user,
            private_key=orch.ssh_private_key_path(loadgen_request),
            port=port,
        )

    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner:
        return OrchestratorVmRunner(self._orch(), self._loadgen_request)

    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher:
        return VmFileFetcher(vm=self._orch(), request=self._loadgen_request)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self._stack_request, host=ctx.stack_host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return ctx.prometheus_url

    def prepare_loadgen(self, ctx: RunContext) -> None:
        # Proxmox builds the loadgen body directly (no separate script-upload /
        # prepare step in its tail) — no-op.
        return None

    def create_run_dir(self) -> Path:
        from typing import Any, cast

        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        run_dir_creator = TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root, vm=cast("Any", self._orch())
        )
        return run_dir_creator._create_run_dir()  # noqa: SLF001

    # ── lifecycle-specific extra steps ─────────────────────────────────────────

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        if phase is FlowPhase.BEFORE_LOADGEN:
            from controlplane_tool.scenario.scenarios._workflow_assembly import CallableTask
            from controlplane_tool.scenario.two_vm_loadtest_config import (
                TWO_VM_PROMETHEUS_NODE_PORT,
            )

            orch = self._orch()
            stack_request = self._stack_request

            def action() -> None:
                host, port = orch.publish_port(
                    stack_request,
                    service="PROMETHEUS",
                    guest_port=TWO_VM_PROMETHEUS_NODE_PORT,
                )
                ctx.prometheus_url = f"http://{host}:{port}"

            return [
                CallableTask(
                    task_id="vm.stack.publish_ports",
                    title="Publish Proxmox NAT ports",
                    action=action,
                )
            ]
        return []

    def extra_step_ids(self, phase: FlowPhase) -> list:
        if phase is FlowPhase.BEFORE_LOADGEN:
            return ["vm.stack.publish_ports"]
        return []

    def extra_step_titles(self, phase: FlowPhase) -> list[str]:
        if phase is FlowPhase.BEFORE_LOADGEN:
            return ["Publish Proxmox NAT ports"]
        return []

    # ── event/cleanup capabilities ─────────────────────────────────────────────

    def emits_step_events(self) -> bool:
        return True

    def cleanup_on_failure(self, error: Exception) -> list[str]:
        if not self.request.cleanup_vm:
            return []
        orch = self._orch()
        errors: list[str] = []
        for vm_request in (self.request.loadgen_vm, self.request.vm):
            if vm_request is None:
                continue
            try:
                orch.teardown(vm_request)
            except Exception as exc:  # noqa: BLE001
                errors.append(str(exc))
        return errors
