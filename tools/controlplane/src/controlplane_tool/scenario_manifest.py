from __future__ import annotations

from pathlib import Path
import json
from uuid import uuid4

from controlplane_tool.paths import default_tool_paths
from controlplane_tool.scenario_models import ResolvedScenario

SCENARIO_MANIFEST_SYSTEM_PROPERTY = "nanofaas.e2e.scenarioManifest"


def _repo_relative_path(path: Path | None) -> str | None:
    if path is None:
        return None
    try:
        return path.resolve().relative_to(default_tool_paths().workspace_root.resolve()).as_posix()
    except ValueError:
        return None


def scenario_manifest_payload(scenario: ResolvedScenario) -> dict[str, object]:
    return {
        "name": scenario.name,
        "baseScenario": scenario.base_scenario,
        "runtime": scenario.runtime,
        "functionPreset": scenario.function_preset,
        "functionKeys": list(scenario.function_keys),
        "namespace": scenario.namespace,
        "localRegistry": scenario.local_registry,
        "sourcePath": str(scenario.source_path) if scenario.source_path is not None else None,
        "functions": [
            {
                "key": function.key,
                "family": function.family,
                "runtime": function.runtime,
                "description": function.description,
                "exampleDir": str(function.example_dir) if function.example_dir is not None else None,
                "image": function.image,
                "payloadPath": str(function.payload_path) if function.payload_path is not None else None,
                "repoRelativePayloadPath": _repo_relative_path(function.payload_path),
            }
            for function in scenario.functions
        ],
        "payloads": {key: str(path) for key, path in scenario.payloads.items()},
        "invoke": scenario.invoke.model_dump(mode="python", exclude_none=True),
        "load": scenario.load.model_dump(mode="python", exclude_none=True, by_alias=True),
    }


def scenario_manifest_system_property_arg(path: str) -> str:
    return f"-D{SCENARIO_MANIFEST_SYSTEM_PROPERTY}={path}"


def write_scenario_manifest(scenario: ResolvedScenario, *, root: Path) -> Path:
    destination_root = Path(root)
    destination_root.mkdir(parents=True, exist_ok=True)
    slug = scenario.name.replace(" ", "-")
    destination = destination_root / f"{slug}-{uuid4().hex}.json"
    destination.write_text(
        json.dumps(scenario_manifest_payload(scenario), indent=2, sort_keys=True),
        encoding="utf-8",
    )
    return destination
