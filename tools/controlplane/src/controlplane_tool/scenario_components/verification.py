from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from controlplane_tool.cli_platform_workflow import platform_status_command
from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario_tasks import k8s_e2e_test_vm_script


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _namespace(context: ScenarioExecutionContext) -> str:
    if context.namespace:
        return context.namespace
    if context.resolved_scenario is not None and context.resolved_scenario.namespace:
        return context.resolved_scenario.namespace
    return "nanofaas-e2e"


def _kubeconfig_path(context: ScenarioExecutionContext) -> str:
    home = context.vm_request.home
    if home:
        return f"{home}/.kube/config"
    if context.vm_request.user == "root":
        return "/root/.kube/config"
    return f"/home/{context.vm_request.user}/.kube/config"


def _remote_home(context: ScenarioExecutionContext) -> str:
    if context.vm_request.home:
        return context.vm_request.home
    if context.vm_request.user == "root":
        return "/root"
    return f"/home/{context.vm_request.user}"


def _remote_project_dir(context: ScenarioExecutionContext) -> str:
    return f"{_remote_home(context)}/nanofaas"


def _remote_manifest_path(context: ScenarioExecutionContext) -> str | None:
    if context.manifest_path is None:
        return None
    try:
        relative = context.manifest_path.resolve().relative_to(context.repo_root.resolve())
        return f"{_remote_project_dir(context)}/{relative.as_posix()}"
    except ValueError:
        return f"{_remote_project_dir(context)}/tools/controlplane/runs/manifests/{context.manifest_path.name}"


def _remote_exec_argv(context: ScenarioExecutionContext, command: str) -> tuple[str, ...]:
    vm_request = context.vm_request
    if vm_request.lifecycle == "external":
        if vm_request.host is None:
            raise ValueError("external VM lifecycle requires a host")
        return ("ssh", f"{vm_request.user}@{vm_request.host}", command)

    vm_name = vm_request.name or "nanofaas-e2e"
    return ("multipass", "exec", vm_name, "--", "bash", "-lc", command)


def _managed_vm_env(context: ScenarioExecutionContext) -> Mapping[str, str]:
    vm_request = context.vm_request
    env = {
        "CONTROL_PLANE_RUNTIME": context.runtime,
        "LOCAL_REGISTRY": context.local_registry,
        "NAMESPACE": _namespace(context),
        "VM_NAME": vm_request.name or "nanofaas-e2e",
        "CPUS": str(vm_request.cpus),
        "MEMORY": vm_request.memory,
        "DISK": vm_request.disk,
        "KEEP_VM": "true",
        "E2E_SKIP_VM_BOOTSTRAP": "true",
        "E2E_VM_LIFECYCLE": vm_request.lifecycle,
        "E2E_VM_USER": vm_request.user,
        "E2E_REMOTE_PROJECT_DIR": _remote_project_dir(context),
        "E2E_KUBECONFIG_PATH": _kubeconfig_path(context),
    }
    if vm_request.host:
        env["E2E_VM_HOST"] = vm_request.host
        env["E2E_PUBLIC_HOST"] = vm_request.host
    elif vm_request.lifecycle == "multipass":
        vm_name = vm_request.name or "nanofaas-e2e"
        placeholder = f"<multipass-ip:{vm_name}>"
        env["E2E_VM_HOST"] = placeholder
        env["E2E_PUBLIC_HOST"] = placeholder
    if vm_request.home:
        env["E2E_VM_HOME"] = vm_request.home
    return _frozen_env(env)


def plan_verify_cli_platform_status_fails(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    return (
        RemoteCommandOperation(
            operation_id="cleanup.verify_cli_platform_status_fails",
            summary="Verify CLI platform status fails after cleanup",
            argv=tuple(platform_status_command(namespace)),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
        ),
    )


def plan_run_k3s_curl_checks(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="tests.run_k3s_curl_checks",
            summary="Run k3s-junit-curl verification",
            argv=("python", "-m", "controlplane_tool.k3s_curl_runner", "verify-existing-stack"),
            env=_frozen_env(),
        ),
    )


def plan_run_k8s_junit(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    remote_manifest_path = _remote_manifest_path(context)
    return (
        RemoteCommandOperation(
            operation_id="tests.run_k8s_junit",
            summary="Run K8sE2eTest in VM",
            argv=_remote_exec_argv(
                context,
                k8s_e2e_test_vm_script(
                    remote_dir=_remote_project_dir(context),
                    kubeconfig_path=_kubeconfig_path(context),
                    runtime_image=f"{context.local_registry}/nanofaas/function-runtime:e2e",
                    namespace=_namespace(context),
                    remote_manifest_path=remote_manifest_path,
                ),
            ),
            env=_frozen_env(),
        ),
    )


def plan_loadtest_run(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    controlplane_tool_project = Path(context.repo_root) / "tools" / "controlplane"
    env = dict(_managed_vm_env(context))
    if context.manifest_path is not None:
        env["NANOFAAS_SCENARIO_PATH"] = str(context.manifest_path)
    return (
        RemoteCommandOperation(
            operation_id="loadtest.run",
            summary="Run loadtest via Python runner",
            argv=(
                "uv",
                "run",
                "--project",
                str(controlplane_tool_project),
                "--locked",
                "controlplane-tool",
                "loadtest",
                "run",
            ),
            env=_frozen_env(env),
        ),
    )


def plan_autoscaling_experiment(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    controlplane_tool_project = Path(context.repo_root) / "tools" / "controlplane"
    env = dict(_managed_vm_env(context))
    if context.manifest_path is not None:
        env["NANOFAAS_SCENARIO_PATH"] = str(context.manifest_path)
    return (
        RemoteCommandOperation(
            operation_id="experiments.autoscaling",
            summary="Run autoscaling experiment (Python)",
            argv=(
                "uv",
                "run",
                "--project",
                str(controlplane_tool_project),
                "--locked",
                "python",
                str(Path(context.repo_root) / "experiments" / "autoscaling.py"),
            ),
            env=_frozen_env(env),
        ),
    )
