from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_docs_reference_controlplane_tool() -> None:
    quickstart = (ROOT / "docs" / "quickstart.md").read_text(encoding="utf-8")
    testing = (ROOT / "docs" / "testing.md").read_text(encoding="utf-8")

    assert "scripts/controlplane-tool.sh" in quickstart
    assert "controlplane-tool" in testing
