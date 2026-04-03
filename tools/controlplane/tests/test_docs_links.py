from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_docs_reference_controlplane_tool() -> None:
    quickstart = (ROOT / "docs" / "quickstart.md").read_text(encoding="utf-8")
    control_plane = (ROOT / "docs" / "control-plane.md").read_text(encoding="utf-8")
    modules = (ROOT / "docs" / "control-plane-modules.md").read_text(encoding="utf-8")
    root_readme = (ROOT / "README.md").read_text(encoding="utf-8")
    testing = (ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    tool_readme = (ROOT / "tools" / "controlplane" / "README.md").read_text(
        encoding="utf-8"
    )

    assert "scripts/control-plane-build.sh" in quickstart
    assert "scripts/controlplane-tool.sh" in quickstart
    assert "scripts/control-plane-build.sh" in control_plane
    assert "scripts/control-plane-build.sh" in modules
    assert "scripts/control-plane-build.sh" in root_readme
    assert "scripts/control-plane-build.sh image --profile all" in root_readme
    assert "scripts/control-plane-build.sh jar --profile container-local" in control_plane
    assert "scripts/control-plane-build.sh matrix" in testing
    assert "scripts/control-plane-build.sh matrix" in tool_readme
    assert "./gradlew :control-plane:bootJar -PcontrolPlaneModules=" not in modules
    assert "exit code" in quickstart.lower()
    assert "controlplane-tool" in testing
    assert "NANOFAAS_URL=http://localhost:8080" in testing
    assert "wizard does not ask for a Prometheus URL" in quickstart
    assert "started as a local Docker container" in testing
    assert "tools/controlplane" in tool_readme
    assert "Prometheus URL is not requested in the wizard" in tool_readme
    assert "tool-metrics-echo" in quickstart
    assert "demo-word-stats-deployment" in quickstart
    assert "mock Kubernetes API backend" in quickstart
    assert "tool-managed control-plane runtime" in testing
    assert "strict_required = true" in tool_readme
    assert "deterministic fixture function" in testing
