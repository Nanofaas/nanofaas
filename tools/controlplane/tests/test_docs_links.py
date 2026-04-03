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

    assert "scripts/controlplane.sh" in quickstart
    assert "scripts/control-plane-build.sh" in quickstart
    assert "scripts/controlplane-tool.sh" in quickstart
    assert "scripts/control-plane-build.sh" in control_plane
    assert "scripts/controlplane.sh vm up" in control_plane
    assert "scripts/control-plane-build.sh" in modules
    assert "scripts/control-plane-build.sh" in root_readme
    assert "scripts/control-plane-build.sh image --profile all" in root_readme
    assert "scripts/controlplane.sh functions list" in root_readme
    assert "scripts/controlplane.sh vm up" in root_readme
    assert "scripts/control-plane-build.sh jar --profile container-local" in control_plane
    assert "scripts/control-plane-build.sh matrix" in testing
    assert "scripts/controlplane.sh e2e run k8s-vm" in testing
    assert "scripts/controlplane.sh loadtest list-profiles" in testing
    assert "scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run" in testing
    assert "scripts/e2e-loadtest.sh --profile demo-java --dry-run" in testing
    assert "--function-preset demo-java" in testing
    assert "--scenario-file tools/controlplane/scenarios/k8s-demo-java.toml" in testing
    assert "--saved-profile demo-java" in testing
    assert "scripts/e2e-k8s-vm.sh" in testing
    assert "wrapper" in testing.lower()
    assert "scripts/controlplane.sh e2e all" in tool_readme
    assert "scripts/controlplane.sh loadtest list-profiles" in tool_readme
    assert "scripts/controlplane.sh loadtest show-profile quick" in tool_readme
    assert "scripts/controlplane.sh loadtest run --saved-profile demo-java --dry-run" in tool_readme
    assert "scripts/controlplane.sh vm up" in tool_readme
    assert "scripts/controlplane.sh functions show-preset demo-java" in tool_readme
    assert "demo-loadtest" in tool_readme
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
    assert "ops/ansible" in tool_readme
    assert "tools/controlplane/scenarios/" in tool_readme
    assert "Selection precedence is" in tool_readme
    assert "scenarioManifest" in testing
    assert "pipeline-run remains a compatibility alias" in tool_readme
