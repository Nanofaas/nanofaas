from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation


def control_image(local_registry: str) -> str:
    return f"{local_registry}/nanofaas/control-plane:e2e"


def runtime_image(local_registry: str) -> str:
    return f"{local_registry}/nanofaas/function-runtime:e2e"


def function_image_specs(
    resolved_scenario,
    fallback_runtime_image: str,
) -> list[tuple[str, str, str, str]]:
    """Return (image, runtime_kind, family, fn_key) for each buildable function."""
    if resolved_scenario is None:
        return []

    function_specs: list[tuple[str, str, str, str]] = []
    for function in resolved_scenario.functions:
        if function.runtime == "fixture" or function.family is None:
            continue
        image = function.image or fallback_runtime_image
        function_specs.append((image, function.runtime, function.family, function.key))
    return function_specs


def _frozen_env() -> Mapping[str, str]:
    return MappingProxyType({})


def _dockerfile_for_runtime_kind(runtime_kind: str, family: str) -> Path:
    dockerfile_map = {
        "exec": Path(f"examples/bash/{family}/Dockerfile"),
        "go": Path(f"examples/go/{family}/Dockerfile"),
        "java-lite": Path(f"examples/java/{family}-lite/Dockerfile"),
        "javascript": Path(f"examples/javascript/{family}/Dockerfile"),
        "python": Path(f"examples/python/{family}/Dockerfile"),
    }
    try:
        return dockerfile_map[runtime_kind]
    except KeyError as exc:  # pragma: no cover - defensive guard
        raise ValueError(f"Unsupported function runtime: {runtime_kind!r}") from exc


_RUST_CP_DIR = (
    "experiments/control-plane-staging/versions"
    "/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust"
)


def plan_build_core(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    control_plane_image = control_image(context.local_registry)
    function_runtime_image = runtime_image(context.local_registry)
    if context.runtime == "rust":
        control_context = _RUST_CP_DIR
        control_dockerfile = f"{_RUST_CP_DIR}/Dockerfile"
    else:
        control_context = "control-plane"
        control_dockerfile = "control-plane/Dockerfile"

    operations: list[ScenarioOperation] = []

    if context.runtime != "rust":
        # Rust Dockerfile is a self-contained multi-stage build (cargo runs inside Docker);
        # no pre-build step is needed on the VM.
        operations.append(
            RemoteCommandOperation(
                operation_id="images.build_core.boot_jars",
                summary="Build core JVM artifacts",
                argv=(
                    "./gradlew",
                    ":control-plane:bootJar",
                    ":function-runtime:bootJar",
                    "--no-daemon",
                    "-q",
                ),
                env=_frozen_env(),
                execution_target="vm",
            )
        )

    operations.extend(
        [
            RemoteCommandOperation(
                operation_id="images.build_core.control_image",
                summary="Build control-plane image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    control_dockerfile,
                    "-t",
                    control_plane_image,
                    control_context,
                ),
                env=_frozen_env(),
                execution_target="vm",
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.runtime_image",
                summary="Build function-runtime image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    "function-runtime/Dockerfile",
                    "-t",
                    function_runtime_image,
                    "function-runtime",
                ),
                env=_frozen_env(),
                execution_target="vm",
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.push_control_image",
                summary="Push control-plane image",
                argv=("docker", "push", control_plane_image),
                env=_frozen_env(),
                execution_target="vm",
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.push_runtime_image",
                summary="Push function-runtime image",
                argv=("docker", "push", function_runtime_image),
                env=_frozen_env(),
                execution_target="vm",
            ),
        ]
    )
    return tuple(operations)


def plan_build_selected_functions(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    selected_specs = function_image_specs(
        context.resolved_scenario,
        runtime_image(context.local_registry),
    )
    operations: list[ScenarioOperation] = []
    for image, runtime_kind, family, fn_key in selected_specs:
        if runtime_kind == "java":
            operations.append(
                RemoteCommandOperation(
                    operation_id=f"images.build_selected_functions.{fn_key}",
                    summary=f"Build {fn_key} function image",
                    argv=(
                        "./gradlew",
                        f":examples:java:{family}:bootBuildImage",
                        f"-PfunctionImage={image}",
                        "--no-daemon",
                        "-q",
                    ),
                    env=_frozen_env(),
                    execution_target="vm",
                )
            )
            operations.append(
                RemoteCommandOperation(
                    operation_id=f"images.push_selected_functions.{fn_key}",
                    summary=f"Push {fn_key} function image",
                    argv=("docker", "push", image),
                    env=_frozen_env(),
                    execution_target="vm",
                )
            )
            continue

        operations.append(
            RemoteCommandOperation(
                operation_id=f"images.build_selected_functions.{fn_key}",
                summary=f"Build {fn_key} function image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    str(_dockerfile_for_runtime_kind(runtime_kind, family)),
                    "-t",
                    image,
                    ".",
                ),
                env=_frozen_env(),
                execution_target="vm",
            )
        )
        operations.append(
            RemoteCommandOperation(
                operation_id=f"images.push_selected_functions.{fn_key}",
                summary=f"Push {fn_key} function image",
                argv=("docker", "push", image),
                env=_frozen_env(),
                execution_target="vm",
            )
        )
    return tuple(operations)


BUILD_CORE = ScenarioComponentDefinition(
    component_id="images.build_core",
    summary="Build core images",
    planner=plan_build_core,
)

BUILD_SELECTED_FUNCTIONS = ScenarioComponentDefinition(
    component_id="images.build_selected_functions",
    summary="Build selected function images",
    planner=plan_build_selected_functions,
)
