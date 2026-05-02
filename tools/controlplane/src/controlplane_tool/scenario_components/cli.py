from __future__ import annotations

import json
import shlex
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType

from controlplane_tool.cli_platform_workflow import platform_install_command, platform_status_command
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation
from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.scenario_helpers import function_image, function_payload, selected_functions


@dataclass(frozen=True, slots=True)
class CliComponentContext:
    repo_root: Path
    release: str
    namespace: str
    local_registry: str
    resolved_scenario: ResolvedScenario | None = None


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))

def _cli_binary(context: CliComponentContext) -> str:
    """Full path to the nanofaas-cli binary produced by :nanofaas-cli:installDist."""
    return str(
        context.repo_root / "nanofaas-cli" / "build" / "install" / "nanofaas-cli" / "bin" / "nanofaas-cli"
    )


def _kubeconfig_path(context: CliComponentContext) -> str:
    return str(Path(context.repo_root).parent / ".kube" / "config")


def _cli_env(context: CliComponentContext) -> Mapping[str, str]:
    return _frozen_env(
        {
            "KUBECONFIG": _kubeconfig_path(context),
            "NANOFAAS_NAMESPACE": context.namespace,
        }
    )


def _selected_function_keys(context: CliComponentContext) -> list[str]:
    return selected_functions(context.resolved_scenario)


def _apply_manifest_path(fn_key: str) -> str:
    return f"/tmp/{fn_key}.json"


def _apply_manifest_spec(fn_key: str, image: str) -> str:
    return json.dumps(
        {
            "name": fn_key,
            "image": image,
            "timeoutMs": 5000,
            "concurrency": 2,
            "queueSize": 20,
            "maxRetries": 3,
            "executionMode": "DEPLOYMENT",
        },
        separators=(",", ":"),
    )


def plan_build_install_dist(_: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.build_install_dist",
            summary="Build nanofaas-cli installDist",
            argv=("./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"),
            env=_frozen_env(),
            execution_target="vm",
        ),
    )


def plan_platform_install(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    raw_cmd = platform_install_command(
        repo_root=context.repo_root,
        release=context.release,
        namespace=context.namespace,
        control_plane_image=f"{context.local_registry}/nanofaas/control-plane:e2e",
    )
    argv = (_cli_binary(context), *raw_cmd)
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_install",
            summary="Install nanofaas platform with CLI",
            argv=tuple(argv),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_platform_status(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    raw_cmd = platform_status_command(context.namespace)
    argv = (_cli_binary(context), *raw_cmd)
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_status",
            summary="Check nanofaas platform status",
            argv=tuple(argv),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def _plan_platform_uninstall(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_uninstall",
            summary="Uninstall nanofaas platform with CLI",
            argv=(_cli_binary(context), "platform", "uninstall", "--release", context.release, "-n", context.namespace),
            env=_frozen_env({"KUBECONFIG": _kubeconfig_path(context)}),
            execution_target="vm",
        ),
    )


def plan_fn_apply_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    operations: list[RemoteCommandOperation] = []
    for fn_key in _selected_function_keys(context):
        image = function_image(
            fn_key,
            context.resolved_scenario,
            f"{context.local_registry}/nanofaas/function-runtime:e2e",
        )
        manifest_path = _apply_manifest_path(fn_key)
        manifest_spec = _apply_manifest_spec(fn_key, image)
        command = (
            f"printf '%s' {shlex.quote(manifest_spec)} > {shlex.quote(manifest_path)} && "
            f"{shlex.quote(_cli_binary(context))} fn apply -f {shlex.quote(manifest_path)}"
        )
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_apply_selected.{fn_key}",
                summary=f"Apply selected function '{fn_key}'",
                argv=("bash", "-lc", command),
                env=_frozen_env(
                    {
                        **dict(_cli_env(context)),
                        "NANOFAAS_FUNCTION_IMAGE": image,
                    }
                ),
                execution_target="vm",
            )
        )
    return tuple(operations)


def plan_fn_list_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.fn_list_selected",
            summary="List selected functions",
            argv=(_cli_binary(context), "fn", "list"),
            env=_cli_env(context),
            execution_target="vm",
        ),
    )


def plan_fn_invoke_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    operations: list[RemoteCommandOperation] = []
    for fn_key in _selected_function_keys(context):
        payload = function_payload(
            fn_key,
            context.resolved_scenario,
            default_message="hello-from-cli-stack",
        )
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_invoke_selected.{fn_key}",
                summary=f"Invoke selected function '{fn_key}'",
                argv=(_cli_binary(context), "invoke", fn_key, "-d", payload),
                env=_cli_env(context),
                execution_target="vm",
            )
        )
    return tuple(operations)


def plan_fn_enqueue_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    operations: list[RemoteCommandOperation] = []
    for fn_key in _selected_function_keys(context):
        payload = function_payload(
            fn_key,
            context.resolved_scenario,
            default_message="hello-from-cli-stack",
        )
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_enqueue_selected.{fn_key}",
                summary=f"Enqueue selected function '{fn_key}'",
                argv=(_cli_binary(context), "enqueue", fn_key, "-d", payload),
                env=_cli_env(context),
                execution_target="vm",
            )
        )
    return tuple(operations)


def plan_fn_delete_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    operations: list[RemoteCommandOperation] = []
    for fn_key in _selected_function_keys(context):
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_delete_selected.{fn_key}",
                summary=f"Delete selected function '{fn_key}'",
                argv=(_cli_binary(context), "fn", "delete", fn_key),
                env=_cli_env(context),
                execution_target="vm",
            )
        )
    return tuple(operations)


CLI_BUILD_INSTALL_DIST = ScenarioComponentDefinition(
    component_id="cli.build_install_dist",
    summary="Build nanofaas-cli installDist",
    planner=plan_build_install_dist,
)

CLI_PLATFORM_INSTALL = ScenarioComponentDefinition(
    component_id="cli.platform_install",
    summary="Install nanofaas platform with CLI",
    planner=plan_platform_install,
)

CLI_PLATFORM_STATUS = ScenarioComponentDefinition(
    component_id="cli.platform_status",
    summary="Check nanofaas platform status",
    planner=plan_platform_status,
)

CLI_FN_APPLY_SELECTED = ScenarioComponentDefinition(
    component_id="cli.fn_apply_selected",
    summary="Apply selected functions with CLI",
    planner=plan_fn_apply_selected,
)

CLI_FN_LIST_SELECTED = ScenarioComponentDefinition(
    component_id="cli.fn_list_selected",
    summary="List selected functions with CLI",
    planner=plan_fn_list_selected,
)

CLI_FN_INVOKE_SELECTED = ScenarioComponentDefinition(
    component_id="cli.fn_invoke_selected",
    summary="Invoke selected functions with CLI",
    planner=plan_fn_invoke_selected,
)

CLI_FN_ENQUEUE_SELECTED = ScenarioComponentDefinition(
    component_id="cli.fn_enqueue_selected",
    summary="Enqueue selected functions with CLI",
    planner=plan_fn_enqueue_selected,
)

CLI_FN_DELETE_SELECTED = ScenarioComponentDefinition(
    component_id="cli.fn_delete_selected",
    summary="Delete selected functions with CLI",
    planner=plan_fn_delete_selected,
)
