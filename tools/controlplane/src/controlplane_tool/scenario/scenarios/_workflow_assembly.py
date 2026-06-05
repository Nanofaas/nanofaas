"""Shared machinery for assembling honest-Task Workflows from legacy recipes.

This module factors out the pieces common to every scenario that has been
converted from the legacy recipe engine to a ``workflow_tasks.Workflow`` of
honest Tasks (k3s-junit-curl, helm-stack, ...):

- ``CallableTask`` — wraps a host-side callable as an honest Task.
- ``_Setup`` — environment/config built once per plan.
- ``build_setup`` — constructs the ``_Setup``.
- ``resolve_host_operation`` — substitutes ``<multipass-ip:NAME>`` placeholders.
- ``build_command_tasks`` — iterates a composed recipe and routes each
  operation to a host/vm ``CommandTask``, delegating scenario-specific
  operations (``vm.ensure_running``, ``vm.down``, custom verify steps) to an
  injectable handler.

Scenario-specific behavior (which operation_ids are special, what their Tasks
do) stays in the per-scenario plan modules; this module only provides the
behavior-preserving routing skeleton.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Callable, Optional

from workflow_tasks import (
    CommandTask,
    CommandTaskSpec,
    HostCommandTaskExecutor,
    VmCommandTaskExecutor,
    command_task_from_operation,
)
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.models import VmConfig

from controlplane_tool.e2e.e2e_models import E2eRequest
from workflow_tasks.vm.orchestrator import VmOrchestrator
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.connectivity import (
    ConnectivityStrategy,
    MultipassConnectivity,
    resolve_host_operation,
)
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


# Display-summary overrides keyed by task_id/operation_id. Duplicated from
# ``components/executor.py`` (the legacy recipe path still uses it until C4.4);
# applied here to CommandTask *titles* so the converted Workflow scenarios show
# identical step names to the legacy recipe path.
_SUMMARY_OVERRIDES = {
    "cli.build_install_dist": "Build nanofaas-cli installDist in VM",
    "cli.platform_install": "Install nanofaas into k3s through the CLI",
    "cli.platform_status": "Run platform status",
    "repo.sync_to_vm": "Sync project to VM",
    "registry.ensure_container": "Ensure registry container",
    "k3s.configure_registry": "Configure k3s registry",
    "helm.deploy_control_plane": "Deploy control-plane via Helm",
    "helm.deploy_function_runtime": "Deploy function-runtime via Helm",
    "namespace.install": "Install namespace Helm release",
    "namespace.uninstall": "Uninstall namespace Helm release",
    "cleanup.uninstall_control_plane": "Uninstall control-plane Helm release",
    "cleanup.uninstall_function_runtime": "Uninstall function-runtime Helm release",
    "cleanup.verify_cli_platform_status_fails": "Verify cli-stack status fails",
}


@dataclass
class CallableTask:
    """A Task that runs an injected callable.

    Used to wrap host-side actions (e.g. a verification step, or a no-op
    vm.down placeholder) as honest Workflow Tasks. Exceptions raised by
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


def build_setup(runner: "E2eRunner", request: E2eRequest) -> _Setup:
    """Build the shared environment/config once (used by run() and introspection).

    Passes ``manifest_root`` so the resolved-scenario manifest is written and its
    path injected into the scenario context (mirroring the legacy recipe planning
    path); the k8s-junit step depends on the ``nanofaas.e2e.scenarioManifest``
    system property pointing at this manifest.
    """
    context = resolve_scenario_environment(
        runner.paths.workspace_root, request, manifest_root=runner.manifest_root
    )
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


def host_command_task_from_step(
    step: ScenarioPlanStep,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
    host_executor: HostCommandTaskExecutor,
    resolve_host: bool = True,
) -> CommandTask:
    """Convert a host ``ScenarioPlanStep`` into an honest host ``CommandTask``.

    Used by planner-step scenarios (e.g. ``cli``) whose steps come from
    ``ScenarioPlanner.vm_backed_steps`` rather than a recipe. Substitutes
    ``<multipass-ip:NAME>`` placeholders in the step's command/env at assembly
    time, mirroring ``resolve_host_operation`` for recipe operations.

    *resolve_host*: when False the raw step command/env are kept (used to derive
    cheap task_ids/display titles without a running VM).
    """
    if resolve_host:
        argv = resolver._resolve_command(list(step.command), request.vm, ip_cache, vm)  # noqa: SLF001
        env = resolver._resolve_env(dict(step.env), request.vm, ip_cache, vm)  # noqa: SLF001
    else:
        argv = list(step.command)
        env = dict(step.env)
    spec = CommandTaskSpec(
        task_id=step.step_id,
        summary=step.summary,
        argv=tuple(argv),
        target="host",
        env=env,
    )
    return CommandTask(
        task_id=step.step_id,
        title=_SUMMARY_OVERRIDES.get(step.step_id, step.summary),
        spec=spec,
        executor=host_executor,
    )


# A handler is invoked for each composed operation. It returns:
#   - a Task to append to ``tasks`` (the default command path returns None and the
#     caller routes the operation), or
#   - the sentinel ``HANDLED`` to indicate the handler fully consumed the operation
#     (e.g. it appended to cleanup_tasks itself, or deliberately skipped it).
HANDLED = object()

# Signature: (operation) -> Optional[object]
#   return HANDLED  -> operation fully handled (skip default routing)
#   return None     -> fall through to default host/vm command routing
SpecialHandler = Callable[[RemoteCommandOperation], Optional[object]]


def workflow_display_steps(
    tasks: list,
    *,
    prepend_ensure_running: bool = True,
) -> list[ScenarioPlanStep]:
    """Build lightweight display ``ScenarioPlanStep``s from a Workflow's tasks.

    Used by the recipe-derived workflow scenarios (k3s/helm/cli-stack) to keep a
    ``steps`` field for CLI dry-run rendering WITHOUT re-running the legacy recipe
    engine. Each task becomes a ``ScenarioPlanStep`` with:
    ``summary`` from the task title, ``command``/``env`` from the CommandTask spec
    (empty for non-command tasks like DestroyVm/CallableTask), and the task_id as
    ``step_id``. ``vm.ensure_running`` is run separately by the plan's ``run()`` and
    is prepended here so the displayed step list matches the legacy recipe order.
    """
    steps: list[ScenarioPlanStep] = []
    if prepend_ensure_running:
        steps.append(
            ScenarioPlanStep(
                summary="Ensure VM is running",
                command=[],
                step_id="vm.ensure_running",
            )
        )
    for task in tasks:
        spec = getattr(task, "spec", None)
        if spec is not None:
            command = list(spec.argv)
            env = dict(spec.env)
        else:
            command = []
            env = {}
        steps.append(
            ScenarioPlanStep(
                summary=task.title,
                command=command,
                env=env,
                step_id=task.task_id,
            )
        )
    return steps


def build_command_tasks(
    runner: "E2eRunner",
    request: E2eRequest,
    setup: _Setup,
    recipe,
    *,
    special_handler: SpecialHandler | None = None,
    context_selector: Callable[[object], object] | None = None,
    resolve_host: bool = True,
    connectivity: ConnectivityStrategy | None = None,
) -> list:
    """Route each composed recipe operation to an honest CommandTask.

    Operations are routed by ``execution_target`` (host vs vm). Scenario-specific
    operations are delegated to *special_handler*, which may append its own Tasks
    (returning ``HANDLED``) or signal default routing (returning ``None``).

    *context_selector* (optional) chooses the planner context per component. When
    ``None`` (the default), every component's planner receives ``setup.context``.
    Scenarios that need a per-component context (e.g. cli-stack's ``cli.*``
    planners need a ``CliComponentContext``) pass a callable mapping each
    component to the context its planner expects.

    *resolve_host*: when True (the default, used by ``run()``) host operations have
    their ``<multipass-ip:NAME>`` placeholders resolved against the live VM. When
    False (used to derive cheap display steps / task_ids without a running VM) the
    raw operation command/env are kept — matching the legacy unresolved plan steps.

    Returns the ordered list of command Tasks. Cleanup Tasks (if any) are the
    handler's responsibility — it should append them to a list it closes over.
    """
    context = setup.context
    vm_request = setup.vm_request
    vm_orch = runner.vm
    remote_dir = vm_orch.remote_project_dir(vm_request)

    if connectivity is None:
        connectivity = MultipassConnectivity(runner=runner, request=request)

    host_executor = HostCommandTaskExecutor(runner.shell)
    vm_executor = VmCommandTaskExecutor(connectivity.vm_runner(vm_request))

    tasks: list = []
    for component in compose_recipe(recipe):
        ctx = context_selector(component) if context_selector is not None else context
        for operation in component.planner(ctx):
            if special_handler is not None:
                result = special_handler(operation)
                if result is HANDLED:
                    continue
                if result is not None:
                    tasks.append(result)
                    continue
            title = _SUMMARY_OVERRIDES.get(operation.operation_id, operation.summary)
            if operation.execution_target == "vm":
                tasks.append(
                    command_task_from_operation(
                        operation, vm_executor, title=title, remote_dir=remote_dir
                    )
                )
            else:
                host_op = (
                    connectivity.resolve_host_operation(operation)
                    if resolve_host
                    else operation
                )
                tasks.append(
                    command_task_from_operation(host_op, host_executor, title=title)
                )
    return tasks
