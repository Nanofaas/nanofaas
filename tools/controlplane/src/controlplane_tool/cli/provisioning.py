from __future__ import annotations

from collections.abc import Callable, Generator, Iterable
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
from typing import Any

from workflow_tasks import (
    DestroyVm,
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
    retarget_bootstrap_operation,
)
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.components.operations import ScenarioOperation
from workflow_tasks.core.task import Task
from workflow_tasks.vm.orchestrator import VmOrchestrator
from workflow_tasks.vm.models import VmInfo, vm_remote_home

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.config.environment import ExecutionRole
from controlplane_tool.cli.vm_provider import (
    vm_provider_for_environment,
    vm_request_for_role,
)
from controlplane_tool.core.task_shell_adapter import ShellCommandTaskRunner


def _request(environment: EnvironmentConfig, role: ExecutionRole, *, loadtest: bool) -> VmRequest:
    return vm_request_for_role(environment, role, loadtest=loadtest)


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
    resolved = request.model_copy(
        update={
            "lifecycle": "external",
            "host": info.host,
            "user": info.user,
            "home": info.home,
        }
    )
    return resolved


def _destroy_task(orchestrator: Any, request: VmRequest, *, role: str) -> DestroyVm | None:
    if request.lifecycle == "external":
        return None
    lifecycle = VmLifecycleAdapter(
        orchestrator,
        lifecycle=request.lifecycle,
        credentials=request,
    )
    return DestroyVm(
        task_id=f"provision.{role}.destroy",
        title=f"Destroy {role} VM",
        lifecycle=lifecycle,
        info=VmInfo(
            name=request.name or role,
            host=request.host or "",
            user=request.user,
            home=vm_remote_home(request),
        ),
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
    runner = getattr(orchestrator, "shell", None) or ShellCommandTaskRunner()
    executor = HostCommandTaskExecutor(runner)
    tasks: list[Task] = [
        command_task_from_operation(
            replace(operation, operation_id=f"provision.{role}.{operation.operation_id}"),
            executor,
        )
        for operation in operations
    ]
    Workflow(tasks=tasks).run()


def _retarget_cloud_operations(
    environment: EnvironmentConfig,
    orchestrator: Any,
    context: ScenarioExecutionContext,
    operations: Iterable[RemoteCommandOperation],
) -> tuple[RemoteCommandOperation, ...]:
    if environment.provider not in {"azure", "proxmox"}:
        return tuple(operations)

    request = context.vm_request
    if environment.provider == "proxmox":
        host, port = orchestrator.ssh_endpoint(request)
    else:
        host, port = orchestrator.connection_host(request), None
    private_key = orchestrator.ssh_private_key_path(request)
    return tuple(
        retarget_bootstrap_operation(
            operation,
            context=context,
            host=host,
            port=port,
            private_key=private_key,
        )
        for operation in operations
    )


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
    return _remote_operations(operation for planner in planners for operation in planner(context))


def _remote_operations(
    operations: Iterable[ScenarioOperation],
) -> tuple[RemoteCommandOperation, ...]:
    resolved: list[RemoteCommandOperation] = []
    for operation in operations:
        if not isinstance(operation, RemoteCommandOperation):
            raise TypeError(f"bootstrap operation is not a remote command: {operation}")
        resolved.append(operation)
    return tuple(resolved)


@contextmanager
def provision_environment(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    repo_root: Path,
    orchestrator_factory: Callable[[Path], Any] | None = None,
    post_ensure_verifier: Callable[[ExecutionRole, VmRequest], None] | None = None,
    keep: bool = False,
) -> Generator[None, None, None]:
    if environment.provider == "local":
        raise ValueError("--provision requires a non-local environment")

    if orchestrator_factory is not None:
        orchestrator = orchestrator_factory(repo_root)
    elif environment.provider in {"azure", "proxmox"}:
        orchestrator = vm_provider_for_environment(environment, repo_root)
    else:
        orchestrator = VmOrchestrator(repo_root)
    cleanup_tasks: list[DestroyVm] = []
    main_error: BaseException | None = None
    cleanup_error: Exception | None = None
    try:
        loadtest_workflow = scenario.workflow in ("loadtest", "offload-loadtest")
        dedicated_loadgen = loadtest_workflow and "loadgen" in environment.roles
        stack_request = _request(environment, "stack", loadtest=loadtest_workflow)
        stack_cleanup = _destroy_task(orchestrator, stack_request, role="stack")
        if stack_cleanup is not None:
            cleanup_tasks.append(stack_cleanup)
        stack = _ensure_vm(
            orchestrator,
            stack_request,
            role="stack",
        )
        loadgen_request: VmRequest | None = None
        loadgen: VmRequest | None = None
        if dedicated_loadgen:
            loadgen_request = _request(environment, "loadgen", loadtest=True)
            loadgen_cleanup = _destroy_task(orchestrator, loadgen_request, role="loadgen")
            if loadgen_cleanup is not None:
                cleanup_tasks.append(loadgen_cleanup)
            loadgen = _ensure_vm(
                orchestrator,
                loadgen_request,
                role="loadgen",
            )

        dedicated_cloud = scenario.workflow == "offload-loadtest" and "cloud" in environment.roles
        cloud_request: VmRequest | None = None
        cloud: VmRequest | None = None
        if dedicated_cloud:
            cloud_request = _request(environment, "cloud", loadtest=True)
            cloud_cleanup = _destroy_task(orchestrator, cloud_request, role="cloud")
            if cloud_cleanup is not None:
                cleanup_tasks.append(cloud_cleanup)
            cloud = _ensure_vm(
                orchestrator,
                cloud_request,
                role="cloud",
            )

        dedicated_arm = "arm-builder" in environment.roles
        arm_builder_request: VmRequest | None = None
        arm_builder: VmRequest | None = None
        if dedicated_arm:
            arm_builder_request = _request(environment, "arm-builder", loadtest=False)
            arm_cleanup = _destroy_task(
                orchestrator, arm_builder_request, role="arm-builder"
            )
            if arm_cleanup is not None:
                cleanup_tasks.append(arm_cleanup)
            arm_builder = _ensure_vm(
                orchestrator,
                arm_builder_request,
                role="arm-builder",
            )

        if post_ensure_verifier is not None:
            post_ensure_verifier("stack", stack_request)
            if loadgen_request is not None:
                post_ensure_verifier("loadgen", loadgen_request)
            if cloud_request is not None:
                post_ensure_verifier("cloud", cloud_request)
            if arm_builder_request is not None:
                post_ensure_verifier("arm-builder", arm_builder_request)

        stack_context = _context(repo_root, stack)
        _run_operations(
            orchestrator,
            _retarget_cloud_operations(
                environment,
                orchestrator,
                stack_context,
                _stack_operations(
                    scenario,
                    stack_context,
                    dedicated_loadgen=dedicated_loadgen,
                ),
            ),
            role="stack",
        )

        if arm_builder is not None:
            arm_context = _context(repo_root, arm_builder)
            _run_operations(
                orchestrator,
                _remote_operations(plan_vm_provision_base(arm_context)),
                role="arm-builder",
            )

        if loadgen is not None:
            context = _context(repo_root, loadgen)
            _run_operations(
                orchestrator,
                _retarget_cloud_operations(
                    environment,
                    orchestrator,
                    context,
                    _remote_operations(
                        (*plan_loadtest_install_k6(context), *plan_repo_sync_to_vm(context))
                    ),
                ),
                role="loadgen",
            )

        if cloud is not None:
            cloud_context = _context(repo_root, cloud)
            _run_operations(
                orchestrator,
                _retarget_cloud_operations(
                    environment,
                    orchestrator,
                    cloud_context,
                    _stack_operations(
                        scenario,
                        cloud_context,
                        dedicated_loadgen=True,
                    ),
                ),
                role="cloud",
            )
        yield
    except BaseException as exc:
        main_error = exc
    finally:
        try:
            Workflow(
                tasks=[],
                cleanup_tasks=list(reversed(cleanup_tasks)),
                keep_infrastructure=keep,
            ).run()
        except Exception as exc:
            cleanup_error = exc

    if main_error is not None:
        if cleanup_error is not None:
            raise RuntimeError(f"{main_error}\n\nCleanup errors:\n{cleanup_error}") from main_error
        raise main_error
    if cleanup_error is not None:
        raise cleanup_error
