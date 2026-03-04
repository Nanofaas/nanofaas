from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_docs_reference_controlplane_tool() -> None:
    quickstart = (ROOT / "docs" / "quickstart.md").read_text(encoding="utf-8")
    testing = (ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    readme = (ROOT / "tooling" / "controlplane_tui" / "README.md").read_text(
        encoding="utf-8"
    )

    assert "scripts/controlplane-tool.sh" in quickstart
    assert "exit code" in quickstart.lower()
    assert "controlplane-tool" in testing
    assert "NANOFAAS_URL=http://localhost:8080" in testing
    assert "wizard does not ask for a Prometheus URL" in quickstart
    assert "started as a local Docker container" in testing
    assert "Prometheus URL is not requested in the wizard" in readme
    assert "tool-metrics-echo" in quickstart
    assert "demo-word-stats-deployment" in quickstart
    assert "mock Kubernetes API backend" in quickstart
    assert "tool-managed control-plane runtime" in testing
    assert "strict_required = true" in readme
    assert "deterministic fixture function" in testing
