from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from controlplane_tool.cli_platform_workflow import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario_helpers import function_image, function_payload, selected_functions


def _frozen_env(env: Mapping[str, str] | None = None) -> Mapping[str, str]:
    return MappingProxyType(dict(env or {}))


def _repo_root(context: object) -> Path:
    repo_root = getattr(context, "repo_root", None)
    if repo_root is None:
        raise ValueError("scenario context is missing repo_root")
    return Path(repo_root)


def _local_registry(context: object) -> str:
    registry = getattr(context, "local_registry", None)
    if registry is None:
        raise ValueError("scenario context is missing local_registry")
    return registry


def _namespace(context: object) -> str:
    namespace = getattr(context, "namespace", None)
    if namespace:
        return namespace
    scenario_name = getattr(context, "scenario_name", None)
    if scenario_name:
        return scenario_name
    return "nanofaas-e2e"


def _release(context: object) -> str:
    release = getattr(context, "release", None)
    if release:
        return release
    return _namespace(context)


def _resolved_scenario(context: object):
    return getattr(context, "resolved_scenario", None)


def _endpoint(namespace: str) -> str:
    return f"http://control-plane.{namespace}.svc.cluster.local:8080"


def _cli_env(context: object) -> Mapping[str, str]:
    namespace = _namespace(context)
    env = {"NANOFAAS_NAMESPACE": namespace, "NANOFAAS_ENDPOINT": _endpoint(namespace)}
    return _frozen_env(env)


def _apply_manifest_path(fn_key: str) -> str:
    return f"/tmp/{fn_key}.json"


def plan_build_install_dist(context: object) -> tuple[ScenarioOperation, ...]:
    _ = context
    return (
        RemoteCommandOperation(
            operation_id="cli.build_install_dist",
            summary="Build nanofaas-cli installDist",
            argv=("./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"),
            env=_frozen_env(),
        ),
    )


def plan_platform_install(context: object) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_install",
            summary="Install nanofaas platform with CLI",
            argv=tuple(
                platform_install_command(
                    repo_root=_repo_root(context),
                    release=_release(context),
                    namespace=_namespace(context),
                    control_plane_image=f"{_local_registry(context)}/nanofaas/control-plane:e2e",
                )
            ),
            env=_frozen_env(),
        ),
    )


def plan_platform_status(context: object) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_status",
            summary="Check nanofaas platform status",
            argv=tuple(platform_status_command(_namespace(context))),
            env=_frozen_env(),
        ),
    )


def plan_platform_uninstall(context: object) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.platform_uninstall",
            summary="Uninstall nanofaas platform with CLI",
            argv=tuple(
                platform_uninstall_command(
                    release=_release(context),
                    namespace=_namespace(context),
                )
            ),
            env=_frozen_env(),
        ),
    )


def _selected_function_keys(context: object) -> list[str]:
    resolved = _resolved_scenario(context)
    return selected_functions(resolved)


def plan_fn_apply_selected(context: object) -> tuple[ScenarioOperation, ...]:
    resolved = _resolved_scenario(context)
    namespace = _namespace(context)
    env = _cli_env(context)
    operations: list[ScenarioOperation] = []
    for fn_key in _selected_function_keys(context):
        image = function_image(fn_key, resolved, f"{_local_registry(context)}/nanofaas/function-runtime:e2e")
        manifest_path = _apply_manifest_path(fn_key)
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_apply_selected.{fn_key}",
                summary=f"Apply selected function '{fn_key}'",
                argv=("fn", "apply", "-f", manifest_path),
                env=_frozen_env({**dict(env), "NANOFAAS_NAMESPACE": namespace, "NANOFAAS_ENDPOINT": _endpoint(namespace), "NANOFAAS_FUNCTION_IMAGE": image}),
            )
        )
    return tuple(operations)


def plan_fn_list_selected(context: object) -> tuple[ScenarioOperation, ...]:
    return (
        RemoteCommandOperation(
            operation_id="cli.fn_list_selected",
            summary="List selected functions",
            argv=("fn", "list"),
            env=_cli_env(context),
        ),
    )


def plan_fn_invoke_selected(context: object) -> tuple[ScenarioOperation, ...]:
    resolved = _resolved_scenario(context)
    namespace = _namespace(context)
    env = _cli_env(context)
    operations: list[ScenarioOperation] = []
    for fn_key in _selected_function_keys(context):
        payload = function_payload(fn_key, resolved, default_message="hello-from-cli-stack")
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_invoke_selected.{fn_key}",
                summary=f"Invoke selected function '{fn_key}'",
                argv=("invoke", fn_key, "-d", payload),
                env=_frozen_env({**dict(env), "NANOFAAS_NAMESPACE": namespace, "NANOFAAS_ENDPOINT": _endpoint(namespace)}),
            )
        )
    return tuple(operations)


def plan_fn_enqueue_selected(context: object) -> tuple[ScenarioOperation, ...]:
    resolved = _resolved_scenario(context)
    namespace = _namespace(context)
    env = _cli_env(context)
    operations: list[ScenarioOperation] = []
    for fn_key in _selected_function_keys(context):
        payload = function_payload(fn_key, resolved, default_message="hello-from-cli-stack")
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_enqueue_selected.{fn_key}",
                summary=f"Enqueue selected function '{fn_key}'",
                argv=("enqueue", fn_key, "-d", payload),
                env=_frozen_env({**dict(env), "NANOFAAS_NAMESPACE": namespace, "NANOFAAS_ENDPOINT": _endpoint(namespace)}),
            )
        )
    return tuple(operations)


def plan_fn_delete_selected(context: object) -> tuple[ScenarioOperation, ...]:
    namespace = _namespace(context)
    env = _cli_env(context)
    operations: list[ScenarioOperation] = []
    for fn_key in _selected_function_keys(context):
        operations.append(
            RemoteCommandOperation(
                operation_id=f"cli.fn_delete_selected.{fn_key}",
                summary=f"Delete selected function '{fn_key}'",
                argv=("fn", "delete", fn_key),
                env=_frozen_env({**dict(env), "NANOFAAS_NAMESPACE": namespace, "NANOFAAS_ENDPOINT": _endpoint(namespace)}),
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

CLI_PLATFORM_UNINSTALL = ScenarioComponentDefinition(
    component_id="cli.platform_uninstall",
    summary="Uninstall nanofaas platform with CLI",
    planner=plan_platform_uninstall,
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
