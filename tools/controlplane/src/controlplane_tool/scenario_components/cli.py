from __future__ import annotations

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


def _endpoint(namespace: str) -> str:
    return f"http://control-plane.{namespace}.svc.cluster.local:8080"


def _cli_env(context: CliComponentContext) -> Mapping[str, str]:
    return _frozen_env(
        {
            "NANOFAAS_NAMESPACE": context.namespace,
            "NANOFAAS_ENDPOINT": _endpoint(context.namespace),
        }
    )


def _selected_function_keys(context: CliComponentContext) -> list[str]:
    return selected_functions(context.resolved_scenario)


def _apply_manifest_path(fn_key: str) -> str:
    return f"/tmp/{fn_key}.json"


def plan_build_install_dist(_: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.build_install_dist",
            summary="Build nanofaas-cli installDist",
            argv=("./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"),
            env=_frozen_env(),
        ),
    )


def plan_platform_install(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_install",
            summary="Install nanofaas platform with CLI",
            argv=tuple(
                platform_install_command(
                    repo_root=context.repo_root,
                    release=context.release,
                    namespace=context.namespace,
                    control_plane_image=f"{context.local_registry}/nanofaas/control-plane:e2e",
                )
            ),
            env=_frozen_env(),
        ),
    )


def plan_platform_status(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_status",
            summary="Check nanofaas platform status",
            argv=tuple(platform_status_command(context.namespace)),
            env=_frozen_env(),
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
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_apply_selected.{fn_key}",
                summary=f"Apply selected function '{fn_key}'",
                argv=("fn", "apply", "-f", _apply_manifest_path(fn_key)),
                env=_frozen_env(
                    {
                        **dict(_cli_env(context)),
                        "NANOFAAS_FUNCTION_IMAGE": image,
                    }
                ),
            )
        )
    return tuple(operations)


def plan_fn_list_selected(context: CliComponentContext) -> tuple[RemoteCommandOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.fn_list_selected",
            summary="List selected functions",
            argv=("fn", "list"),
            env=_cli_env(context),
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
                argv=("invoke", fn_key, "-d", payload),
                env=_cli_env(context),
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
                argv=("enqueue", fn_key, "-d", payload),
                env=_cli_env(context),
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
                argv=("fn", "delete", fn_key),
                env=_cli_env(context),
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
