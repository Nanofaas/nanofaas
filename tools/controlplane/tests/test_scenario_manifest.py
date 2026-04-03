import json
from pathlib import Path

from controlplane_tool.scenario_loader import load_scenario_file
from controlplane_tool.scenario_manifest import write_scenario_manifest


def test_manifest_writer_serializes_absolute_payload_paths(tmp_path: Path) -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))

    manifest_path = write_scenario_manifest(scenario, root=tmp_path)
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))

    assert payload["baseScenario"] == "k8s-vm"
    assert payload["functions"][0]["key"] == "word-stats-java"
    assert payload["functions"][0]["payloadPath"].endswith("word-stats-sample.json")
