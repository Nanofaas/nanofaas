from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import replace
from pathlib import Path
from typing import Any

from workflow_tasks import (
    EnsureVmRunning,
    HostCommandTaskExecutor,
    VmConfig,
    VmLifecycleAdapter,
    VmRequest,
    Workflow,
    command_task_from_operation,
)
from workflow_tasks.components.bootstrap import (
    plan_k3s_configure_registry,
    plan_k3s_install,
    plan_loadtest_install_k6,
    plan_registry_ensure_container,
    plan_repo_sync_to_vm,
    plan_vm_provision_base,
)
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.orchestrator import VmOrchestrator

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.config.environment import RoleTarget


def _request(environment: EnvironmentConfig, target: RoleTarget) -> VmRequest:
    if environment.provider not in {"multipass", "external"}:
        raise ValueError(f"--provision does not support {environment.provider} environments")
    lifecycle = "multipass" if environment.provider == "multipass" else "external"
    return VmRequest(
        lifecycle=lifecycle,
        name=target.name,
        host=target.host,
        user=target.user,
        home=target.home,
        cpus=target.cpus,
        memory=target.memory,
        disk=target.disk,
    )


def _ensure_vm(orchestrator: Any, request: VmRequest, *, role: str) -> VmRequest:
    lifecycle = VmLifecycleAdapter(
        orchestrator,
        lifecycle=request.lifecycle,
        credentials=request,
    )
    task = EnsureVmRunning(
        task_id=f"provision.{role}.ensure",
        title=f"Ensure {role} VM is running",
        lifecycle=lifecycle,
        config=VmConfig(
            name=request.name or request.host or role,
            cpus=request.cpus,
            memory=request.memory,
            disk=request.disk,
        ),
    )
    Workflow(tasks=[task]).run()
    info = task.result
    return request.model_copy(
        update={
            "lifecycle": "external",
            "host": info.host,
            "user": info.user,
            "home": info.home,
        }
    )


def _context(repo_root: Path, request: VmRequest) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=repo_root,
        scenario_name="provision",
        runtime="java",
        namespace=None,
        local_registry="localhost:5000",
        resolved_scenario=None,
        vm_request=request,
        cleanup_vm=False,
    )


def _run_operations(
    orchestrator: Any,
    operations: Iterable[RemoteCommandOperation],
    *,
    role: str,
) -> None:
    executor = HostCommandTaskExecutor(orchestrator.shell)
    tasks = [
        command_task_from_operation(
            replace(operation, operation_id=f"provision.{role}.{operation.operation_id}"),
            executor,
        )
        for operation in operations
    ]
    Workflow(tasks=tasks).run()


def _stack_operations(
    scenario: ScenarioConfig,
    context: ScenarioExecutionContext,
    *,
    dedicated_loadgen: bool,
) -> tuple[RemoteCommandOperation, ...]:
    planners = [plan_vm_provision_base]
    if scenario.backend == "k8s" or scenario.workflow == "loadtest":
        planners.extend(
            [
                plan_k3s_install,
                plan_registry_ensure_container,
                plan_k3s_configure_registry,
            ]
        )
    if scenario.workflow == "loadtest" and not dedicated_loadgen:
        planners.append(plan_loadtest_install_k6)
    planners.append(plan_repo_sync_to_vm)
    return tuple(operation for planner in planners for operation in planner(context))


def provision_environment(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    repo_root: Path,
    orchestrator_factory: Callable[[Path], Any] = VmOrchestrator,
) -> None:
    if environment.provider == "local":
        raise ValueError("--provision requires a non-local environment")

    orchestrator = orchestrator_factory(repo_root)
    dedicated_loadgen = scenario.workflow == "loadtest" and "loadgen" in environment.roles
    stack = _ensure_vm(
        orchestrator,
        _request(environment, environment.target("stack")),
        role="stack",
    )
    _run_operations(
        orchestrator,
        _stack_operations(
            scenario,
            _context(repo_root, stack),
            dedicated_loadgen=dedicated_loadgen,
        ),
        role="stack",
    )

    if dedicated_loadgen:
        loadgen = _ensure_vm(
            orchestrator,
            _request(environment, environment.target("loadgen")),
            role="loadgen",
        )
        context = _context(repo_root, loadgen)
        _run_operations(
            orchestrator,
            (*plan_loadtest_install_k6(context), *plan_repo_sync_to_vm(context)),
            role="loadgen",
        )
