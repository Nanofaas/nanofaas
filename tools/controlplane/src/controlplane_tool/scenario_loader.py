from __future__ import annotations

from pathlib import Path
import tomllib

from controlplane_tool.function_catalog import resolve_function_definition, resolve_function_preset
from controlplane_tool.paths import default_tool_paths, resolve_workspace_path
from controlplane_tool.scenario_models import (
    ResolvedFunction,
    ResolvedScenario,
    ScenarioSpec,
)


def _resolve_input_path(path: Path) -> Path:
    return resolve_workspace_path(path)


def _rewrite_registry(image: str | None, local_registry: str) -> str | None:
    if image is None:
        return None
    _, _, remainder = image.partition("/")
    if not remainder:
        return image
    return f"{local_registry}/{remainder}"


def _resolve_payload_path(
    *,
    scenario_path: Path | None,
    payload_dir: str | None,
    payload_file: str | None,
) -> Path | None:
    if payload_file is None:
        return None

    candidate = Path(payload_file)
    if candidate.is_absolute():
        if candidate.exists():
            return candidate.resolve()
        raise FileNotFoundError(f"Scenario payload not found: {candidate}")

    scenario_root = scenario_path.parent if scenario_path is not None else default_tool_paths().scenarios_dir
    search_roots: list[Path] = []
    if payload_dir:
        search_roots.append(scenario_root / payload_dir)
    search_roots.append(scenario_root)
    search_roots.append(default_tool_paths().scenario_payloads_dir)

    for root in search_roots:
        resolved = (root / candidate).resolve()
        if resolved.exists():
            return resolved

    raise FileNotFoundError(f"Scenario payload not found: {payload_file}")


def resolve_scenario_spec(spec: ScenarioSpec, *, source_path: Path | None = None) -> ResolvedScenario:
    if spec.function_preset:
        definitions = list(resolve_function_preset(spec.function_preset).functions)
    else:
        definitions = [resolve_function_definition(key) for key in spec.functions]

    local_registry = spec.local_registry or "localhost:5000"
    payloads: dict[str, Path] = {}
    resolved_functions: list[ResolvedFunction] = []

    for definition in definitions:
        payload_path = None
        payload_name = spec.payloads.get(definition.key, definition.default_payload_file)
        payload_path = _resolve_payload_path(
            scenario_path=source_path,
            payload_dir=spec.invoke.payload_dir,
            payload_file=payload_name,
        )
        if payload_path is not None:
            payloads[definition.key] = payload_path

        resolved_functions.append(
            ResolvedFunction.from_definition(
                definition,
                image=_rewrite_registry(definition.default_image, local_registry),
                payload_path=payload_path,
            )
        )

    return ResolvedScenario(
        source_path=source_path,
        name=spec.name,
        base_scenario=spec.base_scenario,
        runtime=spec.runtime,
        function_preset=spec.function_preset,
        functions=resolved_functions,
        function_keys=[function.key for function in resolved_functions],
        namespace=spec.namespace,
        local_registry=local_registry,
        payloads=payloads,
        invoke=spec.invoke,
        load=spec.load,
        prefect=spec.prefect,
    )


def overlay_scenario_selection(
    base: ResolvedScenario,
    *,
    function_preset: str | None,
    functions: list[str],
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    if function_preset and functions:
        raise ValueError("function selection must use only one of function_preset or functions")

    has_explicit_functions = bool(functions)
    resolved_preset = None if has_explicit_functions else (
        function_preset if function_preset is not None else base.function_preset
    )
    resolved_functions = list(functions) if has_explicit_functions else []

    if resolved_preset:
        selected_keys = [
            function.key for function in resolve_function_preset(resolved_preset).functions
        ]
    elif resolved_functions:
        for key in resolved_functions:
            resolve_function_definition(key)
        selected_keys = resolved_functions
    else:
        selected_keys = list(base.function_keys)

    load_targets = [target for target in base.load.targets if target in selected_keys]
    if base.load.targets and not load_targets:
        raise ValueError(
            f"selected functions do not satisfy load targets for scenario '{base.base_scenario}'"
        )

    payloads = {
        key: str(base.payloads[key])
        for key in selected_keys
        if key in base.payloads
    }

    return resolve_scenario_spec(
        ScenarioSpec(
            name=base.name,
            base_scenario=base.base_scenario,
            runtime=runtime,
            function_preset=resolved_preset,
            functions=[] if resolved_preset else selected_keys,
            namespace=namespace if namespace is not None else base.namespace,
            local_registry=local_registry or base.local_registry,
            payloads=payloads,
            invoke=base.invoke.model_copy(deep=True),
            load=base.load.model_copy(update={"targets": load_targets}, deep=True),
            prefect=base.prefect.model_copy(deep=True),
        ),
        source_path=base.source_path,
    )


def load_scenario_file(path: Path) -> ResolvedScenario:
    scenario_path = _resolve_input_path(path)
    data = tomllib.loads(scenario_path.read_text(encoding="utf-8"))
    spec = ScenarioSpec.model_validate(data)
    return resolve_scenario_spec(spec, source_path=scenario_path)
