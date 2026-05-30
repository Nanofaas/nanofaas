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
    HostCommandTaskExecutor,
    VmCommandTaskExecutor,
    command_task_from_operation,
)
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.models import VmConfig

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import OrchestratorVmRunner
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.environment import resolve_scenario_environment

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


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
    """Build the shared environment/config once (used by run() and introspection)."""
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


def resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
    # TODO(C-followup): promote CommandResolver.resolve_operation to public.
    argv = resolver._resolve_command(list(operation.argv), request.vm, ip_cache, vm)  # noqa: SLF001
    env = resolver._resolve_env(dict(operation.env), request.vm, ip_cache, vm)  # noqa: SLF001
    return RemoteCommandOperation(
        operation_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(argv),
        env=env,
        execution_target=operation.execution_target,
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


def build_command_tasks(
    runner: "E2eRunner",
    request: E2eRequest,
    setup: _Setup,
    recipe,
    *,
    special_handler: SpecialHandler | None = None,
    context_selector: Callable[[object], object] | None = None,
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

    Returns the ordered list of command Tasks. Cleanup Tasks (if any) are the
    handler's responsibility — it should append them to a list it closes over.
    """
    context = setup.context
    vm_request = setup.vm_request
    vm_orch = runner.vm
    remote_dir = vm_orch.remote_project_dir(vm_request)

    host_executor = HostCommandTaskExecutor(runner.shell)
    vm_executor = VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request))
    resolver = CommandResolver(host_resolver=runner._host_resolver)  # noqa: SLF001
    ip_cache: dict[str, str] = {}

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
            if operation.execution_target == "vm":
                tasks.append(
                    command_task_from_operation(
                        operation, vm_executor, remote_dir=remote_dir
                    )
                )
            else:
                resolved = resolve_host_operation(
                    operation,
                    resolver=resolver,
                    request=request,
                    vm=vm_orch,
                    ip_cache=ip_cache,
                )
                tasks.append(command_task_from_operation(resolved, host_executor))
    return tasks
