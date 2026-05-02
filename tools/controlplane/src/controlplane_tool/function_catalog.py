from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

from controlplane_tool.models import FunctionRuntimeKind
from controlplane_tool.paths import default_tool_paths


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


_PATHS = default_tool_paths()
_EXAMPLES_ROOT = _PATHS.workspace_root / "examples"


def _example_dir(*parts: str) -> Path:
    return _EXAMPLES_ROOT.joinpath(*parts)


_RUNTIME_DIR_TO_CATALOG_RUNTIME: dict[str, FunctionRuntimeKind] = {
    "bash": "exec",
    "go": "go",
    "java": "java",
    "javascript": "javascript",
    "python": "python",
}


def _load_function_manifest(path: Path) -> dict[str, object]:
    if not path.exists():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Invalid function manifest: {path}")
    return data


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
        if runtime_dir == "build":
            continue

        for example_dir in sorted(path for path in runtime_root.iterdir() if path.is_dir()):
            manifest = _load_function_manifest(example_dir / "function.yaml")
            catalog_data = manifest.get("catalog")
            catalog = catalog_data if isinstance(catalog_data, dict) else {}

            fallback_runtime = _runtime_from_dir(runtime_dir, example_dir.name)
            runtime = catalog.get("runtime") or fallback_runtime
            family = catalog.get("family") or _family_from_dir(example_dir.name, runtime)
            key = f"{family}-{runtime}"

            if key in seen:
                raise ValueError(f"Duplicate function key: {key}")
            seen.add(key)

            discovered.append(
                FunctionDefinition(
                    key=key,
                    family=family,
                    runtime=runtime,
                    description=(
                        catalog.get("description")
                        or f"{family} {runtime} example function."
                    ),
                    example_dir=example_dir,
                    default_image=(
                        catalog.get("defaultImage")
                        or _default_image(runtime_dir, runtime, family)
                    ),
                    default_payload_file=(
                        catalog.get("defaultPayload")
                        or _default_payload(payloads_root, family)
                    ),
                )
            )

    return discovered


FUNCTIONS: tuple[FunctionDefinition, ...] = (
    FunctionDefinition(
        key="word-stats-java",
        family="word-stats",
        runtime="java",
        description="Spring Boot Java word statistics demo.",
        example_dir=_example_dir("java", "word-stats"),
        default_image="localhost:5000/nanofaas/java-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-java",
        family="json-transform",
        runtime="java",
        description="Spring Boot Java JSON transform demo.",
        example_dir=_example_dir("java", "json-transform"),
        default_image="localhost:5000/nanofaas/java-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
    FunctionDefinition(
        key="word-stats-java-lite",
        family="word-stats",
        runtime="java-lite",
        description="Java Lite SDK word statistics demo.",
        example_dir=_example_dir("java", "word-stats-lite"),
        default_image="localhost:5000/nanofaas/java-lite-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-java-lite",
        family="json-transform",
        runtime="java-lite",
        description="Java Lite SDK JSON transform demo.",
        example_dir=_example_dir("java", "json-transform-lite"),
        default_image="localhost:5000/nanofaas/java-lite-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
    FunctionDefinition(
        key="word-stats-go",
        family="word-stats",
        runtime="go",
        description="Go SDK word statistics demo.",
        example_dir=_example_dir("go", "word-stats"),
        default_image="localhost:5000/nanofaas/go-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-go",
        family="json-transform",
        runtime="go",
        description="Go SDK JSON transform demo.",
        example_dir=_example_dir("go", "json-transform"),
        default_image="localhost:5000/nanofaas/go-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
    FunctionDefinition(
        key="word-stats-python",
        family="word-stats",
        runtime="python",
        description="Python SDK word statistics demo.",
        example_dir=_example_dir("python", "word-stats"),
        default_image="localhost:5000/nanofaas/python-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-python",
        family="json-transform",
        runtime="python",
        description="Python SDK JSON transform demo.",
        example_dir=_example_dir("python", "json-transform"),
        default_image="localhost:5000/nanofaas/python-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
    FunctionDefinition(
        key="word-stats-javascript",
        family="word-stats",
        runtime="javascript",
        description="Node.js JavaScript word statistics demo.",
        example_dir=_example_dir("javascript", "word-stats"),
        default_image="localhost:5000/nanofaas/javascript-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-javascript",
        family="json-transform",
        runtime="javascript",
        description="Node.js JavaScript JSON transform demo.",
        example_dir=_example_dir("javascript", "json-transform"),
        default_image="localhost:5000/nanofaas/javascript-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
    FunctionDefinition(
        key="word-stats-exec",
        family="word-stats",
        runtime="exec",
        description="Exec/watchdog word statistics demo.",
        example_dir=_example_dir("bash", "word-stats"),
        default_image="localhost:5000/nanofaas/bash-word-stats:e2e",
        default_payload_file="word-stats-sample.json",
    ),
    FunctionDefinition(
        key="json-transform-exec",
        family="json-transform",
        runtime="exec",
        description="Exec/watchdog JSON transform demo.",
        example_dir=_example_dir("bash", "json-transform"),
        default_image="localhost:5000/nanofaas/bash-json-transform:e2e",
        default_payload_file="json-transform-sample.json",
    ),
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

FUNCTION_INDEX = {function.key: function for function in FUNCTIONS}


def list_functions() -> list[FunctionDefinition]:
    return list(FUNCTIONS)


def resolve_function_definition(key: str) -> FunctionDefinition:
    try:
        return FUNCTION_INDEX[key]
    except KeyError as exc:
        raise ValueError(f"Unknown function: {key}") from exc


def _preset(name: str, description: str, keys: tuple[str, ...]) -> FunctionPreset:
    return FunctionPreset(
        name=name,
        description=description,
        functions=tuple(resolve_function_definition(key) for key in keys),
    )


PRESETS: tuple[FunctionPreset, ...] = (
    _preset(
        "demo-java",
        "Spring Boot Java demo functions.",
        ("word-stats-java", "json-transform-java"),
    ),
    _preset(
        "demo-java-lite",
        "Java Lite demo functions.",
        ("word-stats-java-lite", "json-transform-java-lite"),
    ),
    _preset(
        "demo-go",
        "Go demo functions.",
        ("word-stats-go", "json-transform-go"),
    ),
    _preset(
        "demo-python",
        "Python demo functions.",
        ("word-stats-python", "json-transform-python"),
    ),
    _preset(
        "demo-javascript",
        "Node.js JavaScript demo functions.",
        ("word-stats-javascript", "json-transform-javascript"),
    ),
    _preset(
        "demo-exec",
        "Exec/watchdog demo functions.",
        ("word-stats-exec", "json-transform-exec"),
    ),
    _preset(
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
    _preset(
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
    _preset(
        "metrics-smoke",
        "Metrics fixture selection for smoke verification.",
        ("tool-metrics-echo",),
    ),
)

PRESET_INDEX = {preset.name: preset for preset in PRESETS}
SCENARIO_FUNCTION_RUNTIME_ALLOWLIST: dict[str, frozenset[FunctionRuntimeKind]] = {
    "helm-stack": frozenset({"java", "java-lite", "python", "exec"}),
}


def list_function_presets() -> list[FunctionPreset]:
    return list(PRESETS)


def resolve_function_preset(name: str) -> FunctionPreset:
    try:
        return PRESET_INDEX[name]
    except KeyError as exc:
        raise ValueError(f"Unknown function preset: {name}") from exc


def function_runtime_allowlist_for_scenario(
    scenario: str,
) -> frozenset[FunctionRuntimeKind] | None:
    return SCENARIO_FUNCTION_RUNTIME_ALLOWLIST.get(scenario)
