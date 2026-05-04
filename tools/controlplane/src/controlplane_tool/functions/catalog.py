from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import cast

import yaml

from controlplane_tool.core.models import FunctionRuntimeKind
from controlplane_tool.app.paths import default_tool_paths


@dataclass(frozen=True)
class FunctionDefinition:
    key: str
    family: str
    runtime: FunctionRuntimeKind
    description: str
    example_dir: Path | None
    default_image: str | None
    default_payload_file: str | None


@dataclass(frozen=True)
class FunctionPreset:
    name: str
    description: str
    functions: tuple[FunctionDefinition, ...]


@dataclass(frozen=True)
class FunctionPresetSpec:
    name: str
    description: str
    keys: tuple[str, ...]


_PATHS = default_tool_paths()
_EXAMPLES_ROOT = _PATHS.workspace_root / "examples"


_RUNTIME_DIR_TO_CATALOG_RUNTIME: dict[str, FunctionRuntimeKind] = {
    "bash": "exec",
    "go": "go",
    "java": "java",
    "javascript": "javascript",
    "python": "python",
}
_DISCOVERABLE_RUNTIMES: frozenset[FunctionRuntimeKind] = frozenset(
    {"java", "java-lite", "go", "python", "exec", "javascript"}
)
_IGNORED_DISCOVERY_DIRS = frozenset({"build", "building"})


def _load_function_manifest(path: Path) -> dict[str, object]:
    if not path.exists():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Invalid function manifest: {path}")
    return data


def _catalog_metadata(
    manifest: dict[str, object],
    manifest_path: Path,
) -> dict[str, object]:
    catalog_data = manifest.get("catalog")
    if catalog_data is None:
        return {}
    if not isinstance(catalog_data, dict):
        raise ValueError(f"Invalid function catalog metadata: {manifest_path}")
    return catalog_data


def _catalog_string(
    catalog: dict[str, object],
    field: str,
    manifest_path: Path,
) -> str | None:
    value = catalog.get(field)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"Invalid function catalog field {field}: {manifest_path}")
    return value


def _catalog_runtime(
    catalog: dict[str, object],
    manifest_path: Path,
) -> FunctionRuntimeKind | None:
    runtime = _catalog_string(catalog, "runtime", manifest_path)
    if runtime is None:
        return None
    if runtime not in _DISCOVERABLE_RUNTIMES:
        raise ValueError(f"Unsupported function runtime: {runtime}")
    return cast(FunctionRuntimeKind, runtime)


def _runtime_from_dir(runtime_dir: str, family: str) -> FunctionRuntimeKind:
    if runtime_dir == "java" and family.endswith("-lite"):
        return "java-lite"
    try:
        return _RUNTIME_DIR_TO_CATALOG_RUNTIME[runtime_dir]
    except KeyError as exc:
        raise ValueError(f"Unsupported function runtime directory: {runtime_dir}") from exc


def _family_from_dir(function_dir_name: str, runtime: FunctionRuntimeKind) -> str:
    if runtime == "java-lite" and function_dir_name.endswith("-lite"):
        return function_dir_name.removesuffix("-lite")
    return function_dir_name


def _image_runtime_prefix(runtime_dir: str, runtime: FunctionRuntimeKind) -> str:
    if runtime == "exec":
        return "bash"
    if runtime == "java-lite":
        return "java-lite"
    return runtime_dir


def _default_image(runtime_dir: str, runtime: FunctionRuntimeKind, family: str) -> str:
    prefix = _image_runtime_prefix(runtime_dir, runtime)
    return f"localhost:5000/nanofaas/{prefix}-{family}:e2e"


def _default_payload(payloads_root: Path, family: str) -> str | None:
    candidate = f"{family}-sample.json"
    return candidate if (payloads_root / candidate).exists() else None


def _discover_example_functions(
    examples_root: Path,
    payloads_root: Path,
) -> list[FunctionDefinition]:
    if not examples_root.exists():
        return []

    discovered: list[FunctionDefinition] = []
    seen: set[str] = set()

    for runtime_root in sorted(path for path in examples_root.iterdir() if path.is_dir()):
        runtime_dir = runtime_root.name
        if runtime_dir in _IGNORED_DISCOVERY_DIRS:
            continue

        for example_dir in sorted(path for path in runtime_root.iterdir() if path.is_dir()):
            if example_dir.name in _IGNORED_DISCOVERY_DIRS:
                continue

            manifest_path = example_dir / "function.yaml"
            manifest = _load_function_manifest(manifest_path)
            catalog = _catalog_metadata(manifest, manifest_path)

            fallback_runtime = _runtime_from_dir(runtime_dir, example_dir.name)
            runtime = _catalog_runtime(catalog, manifest_path) or fallback_runtime
            family = (
                _catalog_string(catalog, "family", manifest_path)
                or _family_from_dir(example_dir.name, runtime)
            )
            key = f"{family}-{runtime}"

            if key in seen:
                raise ValueError(f"Duplicate function key: {key}")
            seen.add(key)

            discovered.append(
                FunctionDefinition(
                    key=key,
                    family=family,
                    runtime=runtime,
                    description=_catalog_string(
                        catalog, "description", manifest_path
                    )
                    or f"{family} {runtime} example function.",
                    example_dir=example_dir,
                    default_image=_catalog_string(
                        catalog, "defaultImage", manifest_path
                    )
                    or _default_image(runtime_dir, runtime, family),
                    default_payload_file=_catalog_string(
                        catalog, "defaultPayload", manifest_path
                    )
                    or _default_payload(payloads_root, family),
                )
            )

    return discovered


_FIXTURE_FUNCTIONS: tuple[FunctionDefinition, ...] = (
    FunctionDefinition(
        key="tool-metrics-echo",
        family="metrics-echo",
        runtime="fixture",
        description="Deterministic fixture used by the controlplane metrics flow.",
        example_dir=None,
        default_image=None,
        default_payload_file="echo-sample.json",
    ),
)


def _load_functions() -> tuple[FunctionDefinition, ...]:
    functions = (
        *_discover_example_functions(_EXAMPLES_ROOT, _PATHS.scenario_payloads_dir),
        *_FIXTURE_FUNCTIONS,
    )
    seen: set[str] = set()
    for function in functions:
        if function.key in seen:
            raise ValueError(f"Duplicate function key: {function.key}")
        seen.add(function.key)
    return functions


def _function_index() -> dict[str, FunctionDefinition]:
    return {function.key: function for function in _load_functions()}


def _definition_from_index(
    index: dict[str, FunctionDefinition],
    key: str,
) -> FunctionDefinition:
    try:
        return index[key]
    except KeyError as exc:
        raise ValueError(f"Unknown function: {key}") from exc


def list_functions() -> list[FunctionDefinition]:
    return list(_load_functions())


def resolve_function_definition(key: str) -> FunctionDefinition:
    return _definition_from_index(_function_index(), key)


def _resolve_preset(spec: FunctionPresetSpec) -> FunctionPreset:
    index = _function_index()
    return FunctionPreset(
        name=spec.name,
        description=spec.description,
        functions=tuple(_definition_from_index(index, key) for key in spec.keys),
    )


PRESET_SPECS: tuple[FunctionPresetSpec, ...] = (
    FunctionPresetSpec(
        "demo-java",
        "Spring Boot Java demo functions.",
        ("word-stats-java", "json-transform-java"),
    ),
    FunctionPresetSpec(
        "demo-java-lite",
        "Java Lite demo functions.",
        ("word-stats-java-lite", "json-transform-java-lite"),
    ),
    FunctionPresetSpec(
        "demo-go",
        "Go demo functions.",
        ("word-stats-go", "json-transform-go"),
    ),
    FunctionPresetSpec(
        "demo-python",
        "Python demo functions.",
        ("word-stats-python", "json-transform-python"),
    ),
    FunctionPresetSpec(
        "demo-javascript",
        "Node.js JavaScript demo functions.",
        ("word-stats-javascript", "json-transform-javascript"),
    ),
    FunctionPresetSpec(
        "demo-exec",
        "Exec/watchdog demo functions.",
        ("word-stats-exec", "json-transform-exec"),
    ),
    FunctionPresetSpec(
        "demo-loadtest",
        "Repository demo functions supported by the Helm/loadtest stack.",
        (
            "word-stats-java",
            "json-transform-java",
            "word-stats-java-lite",
            "json-transform-java-lite",
            "word-stats-python",
            "json-transform-python",
            "word-stats-exec",
            "json-transform-exec",
        ),
    ),
    FunctionPresetSpec(
        "demo-all",
        "All repository demo functions.",
        (
            "word-stats-java",
            "json-transform-java",
            "word-stats-java-lite",
            "json-transform-java-lite",
            "word-stats-go",
            "json-transform-go",
            "word-stats-python",
            "json-transform-python",
            "word-stats-javascript",
            "json-transform-javascript",
            "word-stats-exec",
            "json-transform-exec",
        ),
    ),
    FunctionPresetSpec(
        "metrics-smoke",
        "Metrics fixture selection for smoke verification.",
        ("tool-metrics-echo",),
    ),
)

PRESET_SPEC_INDEX = {spec.name: spec for spec in PRESET_SPECS}
SCENARIO_FUNCTION_RUNTIME_ALLOWLIST: dict[str, frozenset[FunctionRuntimeKind]] = {
    "helm-stack": frozenset({"java", "java-lite", "python", "exec"}),
}


def list_function_presets() -> list[FunctionPreset]:
    return [_resolve_preset(spec) for spec in PRESET_SPECS]


def resolve_function_preset(name: str) -> FunctionPreset:
    try:
        return _resolve_preset(PRESET_SPEC_INDEX[name])
    except KeyError as exc:
        raise ValueError(f"Unknown function preset: {name}") from exc


def function_runtime_allowlist_for_scenario(
    scenario: str,
) -> frozenset[FunctionRuntimeKind] | None:
    return SCENARIO_FUNCTION_RUNTIME_ALLOWLIST.get(scenario)
