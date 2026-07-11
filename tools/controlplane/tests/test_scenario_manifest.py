import json
from pathlib import Path
from typing import cast

from controlplane_tool.scenario.scenario_loader import load_scenario_file
from controlplane_tool.scenario.scenario_manifest import scenario_manifest_payload, write_scenario_manifest
from controlplane_tool.scenario.scenario_models import ResourceQuantity, ResourceSpec


def test_manifest_writer_serializes_absolute_payload_paths(tmp_path: Path) -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    manifest_path = write_scenario_manifest(scenario, root=tmp_path)
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert payload["baseScenario"] == "validate-k3s"
    assert payload["functions"][0]["key"] == "word-stats-java"
    assert payload["functions"][0]["payloadPath"].endswith("word-stats-sample.json")


def test_manifest_serializes_function_resources() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    scenario.functions[0].resources = ResourceSpec(
        requests=ResourceQuantity(cpu=0.25, memoryMiB=256),
        limits=ResourceQuantity(cpu=0.5, memoryMiB=512),
    )

    payload = scenario_manifest_payload(scenario)

    functions = cast(list[dict[str, object]], payload["functions"])
    assert functions[0]["resources"] == {
        "requests": {"cpu": 0.25, "memoryMiB": 256},
        "limits": {"cpu": 0.5, "memoryMiB": 512},
    }
