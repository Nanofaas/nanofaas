from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_components.operations import (
    RemoteCommandOperation,
    ScenarioOperation,
)

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


@dataclass(frozen=True)
class ScenarioPlanStep:
    summary: str
    command: list[str]
    env: dict[str, str] = field(default_factory=dict)
    step_id: str = ""
    action: Callable[[], None] | None = None


def operation_to_plan_step(
    operation: ScenarioOperation,
    *,
    request: E2eRequest,
    on_k3s_curl_verify: Callable[[], None] | None = None,
    on_ensure_running: Callable[[], None] | None = None,
    on_vm_down: Callable[[], None] | None = None,
    on_remote_exec: Callable[[tuple[str, ...], Mapping[str, str]], None] | None = None,
) -> ScenarioPlanStep:
    def _expect_remote_failure(
        argv: tuple[str, ...],
        env: Mapping[str, str],
    ) -> None:
        if on_remote_exec is None:  # pragma: no cover - defensive
            raise ValueError("cleanup.verify_cli_platform_status_fails requires a remote execution callback")
        try:
            on_remote_exec(argv, env)
        except RuntimeError:
            return
        raise RuntimeError("platform status unexpectedly succeeded after cleanup")

    if not isinstance(operation, RemoteCommandOperation):  # pragma: no cover - defensive
        raise TypeError(f"Unsupported scenario operation: {type(operation)!r}")
    summary = _SUMMARY_OVERRIDES.get(operation.operation_id, operation.summary)
    if operation.execution_target == "vm" and on_remote_exec is not None:
        argv = operation.argv
        env = operation.env
        action = (
            (lambda: _expect_remote_failure(argv, env))
            if operation.operation_id == "cleanup.verify_cli_platform_status_fails"
            else (lambda: on_remote_exec(argv, env))
        )
        return ScenarioPlanStep(
            summary=summary,
            command=list(argv),
            env=dict(env),
            step_id=operation.operation_id,
            action=action,
        )
    if operation.operation_id == "vm.ensure_running" and on_ensure_running is not None:
        return ScenarioPlanStep(
            summary=summary,
            command=list(operation.argv),
            env=dict(operation.env),
            step_id=operation.operation_id,
            action=on_ensure_running,
        )
    if operation.operation_id == "tests.run_k3s_curl_checks":
        if on_k3s_curl_verify is None:  # pragma: no cover - defensive
            raise ValueError("tests.run_k3s_curl_checks requires a verification callback")
        return ScenarioPlanStep(
            summary=summary,
            command=list(operation.argv),
            env=dict(operation.env),
            step_id=operation.operation_id,
            action=on_k3s_curl_verify,
        )
    if operation.operation_id == "vm.down":
        if not request.cleanup_vm:
            return ScenarioPlanStep(
                summary=summary,
                command=["echo", "Skipping VM teardown (--no-cleanup-vm)"],
                step_id=operation.operation_id,
            )
        if on_vm_down is not None:
            return ScenarioPlanStep(
                summary=summary,
                command=list(operation.argv),
                env=dict(operation.env),
                step_id=operation.operation_id,
                action=on_vm_down,
            )
    return ScenarioPlanStep(
        summary=summary,
        command=list(operation.argv),
        env=dict(operation.env),
        step_id=operation.operation_id,
    )


def operations_to_plan_steps(
    operations: Iterable[ScenarioOperation],
    *,
    request: E2eRequest,
    on_k3s_curl_verify: Callable[[], None] | None = None,
    on_ensure_running: Callable[[], None] | None = None,
    on_vm_down: Callable[[], None] | None = None,
    on_remote_exec: Callable[[tuple[str, ...], Mapping[str, str]], None] | None = None,
) -> list[ScenarioPlanStep]:
    return [
        operation_to_plan_step(
            operation,
            request=request,
            on_k3s_curl_verify=on_k3s_curl_verify,
            on_ensure_running=on_ensure_running,
            on_vm_down=on_vm_down,
            on_remote_exec=on_remote_exec,
        )
        for operation in operations
    ]
