from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BUILD_WRAPPER = "scripts/control" + "-plane-build.sh"
TUI_WRAPPER = "scripts/controlplane" + "-tool.sh"
DOCKER_E2E_WRAPPER = "e2e" + ".sh"
BUILDPACK_E2E_WRAPPER = "e2e-buildpack" + ".sh"
K3S_JUNIT_CURL_WRAPPER = "e2e-k3s-junit-curl" + ".sh"
HELM_STACK_WRAPPER = "e2e-k3s-helm" + ".sh"
CLI_VM_WRAPPER = "e2e" + "-cli.sh"
CLI_HOST_WRAPPER = "e2e-cli-host" + "-platform.sh"
CLI_DEPLOY_WRAPPER = "e2e-cli-deploy" + "-host.sh"
PIPELINE_ALIAS = "pipeline" + "-run"

STRICT_CANONICAL_FILES = (
    ROOT / "README.md",
    ROOT / "CLAUDE.md",
    ROOT / "docs" / "control-plane.md",
    ROOT / "docs" / "quickstart.md",
    ROOT / "docs" / "e2e-tutorial.md",
    ROOT / "tools" / "controlplane" / "README.md",
    ROOT / ".github" / "workflows" / "gitops.yml",
)

STALE_TOKENS = (
    BUILD_WRAPPER,
    TUI_WRAPPER,
    DOCKER_E2E_WRAPPER,
    BUILDPACK_E2E_WRAPPER,
    K3S_JUNIT_CURL_WRAPPER,
    HELM_STACK_WRAPPER,
    CLI_VM_WRAPPER,
    CLI_HOST_WRAPPER,
    CLI_DEPLOY_WRAPPER,
    PIPELINE_ALIAS,
)


def test_primary_docs_and_workflows_use_canonical_controlplane_surface() -> None:
    for path in STRICT_CANONICAL_FILES:
        text = path.read_text(encoding="utf-8")
        assert "scripts/controlplane.sh" in text, path
        for token in STALE_TOKENS:
            assert token not in text, f"{path} still references {token}"


def test_python_runtime_primitives_are_available() -> None:
    """Fails until runtime_primitives.py and control_plane_api.py are created (M8)."""
    from controlplane_tool.runtime_primitives import CommandRunner
    from controlplane_tool.control_plane_api import ControlPlaneApi

    assert CommandRunner is not None
    assert ControlPlaneApi is not None


def test_compatibility_notes_are_centralized_when_legacy_wrappers_are_mentioned() -> None:
    testing = (ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    cli_doc = (ROOT / "docs" / "nanofaas-cli.md").read_text(encoding="utf-8")

    assert "scripts/controlplane.sh cli-test run vm" in testing
    assert "scripts/controlplane.sh cli-test run cli-stack" in testing
    assert "scripts/controlplane.sh cli-test run host-platform" in testing
    assert "scripts/controlplane.sh cli-test run deploy-host" in testing
    assert "scripts/controlplane.sh e2e run k3s-junit-curl" in testing
    assert "self-bootstrapping VM-backed scenarios" in testing
    assert "host-platform` is a compatibility path" in testing
    assert "wrapper" in testing.lower()
    assert BUILD_WRAPPER not in testing
    assert TUI_WRAPPER not in testing
    assert PIPELINE_ALIAS not in testing

    assert "scripts/controlplane.sh cli-test run vm" in cli_doc
    assert "scripts/controlplane.sh cli-test run cli-stack" not in cli_doc
    assert "compatibility wrapper" in cli_doc.lower()
    assert PIPELINE_ALIAS not in cli_doc
