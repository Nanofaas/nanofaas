from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

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
