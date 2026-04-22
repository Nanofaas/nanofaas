from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BUILD_WRAPPER = "scripts/control" + "-plane-build.sh"
TUI_WRAPPER = "scripts/controlplane" + "-tool.sh"
PIPELINE_ALIAS = "pipeline" + "-run"
WORKFLOW_BUILD_WRAPPER = "control" + "-plane-build.sh"


def test_docs_reference_canonical_controlplane_commands() -> None:
    quickstart = (ROOT / "docs" / "quickstart.md").read_text(encoding="utf-8")
    control_plane = (ROOT / "docs" / "control-plane.md").read_text(encoding="utf-8")
    modules = (ROOT / "docs" / "control-plane-modules.md").read_text(encoding="utf-8")
    root_readme = (ROOT / "README.md").read_text(encoding="utf-8")
    testing = (ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    tool_readme = (ROOT / "tools" / "controlplane" / "README.md").read_text(
        encoding="utf-8"
    )
    claude = (ROOT / "CLAUDE.md").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "gitops.yml").read_text(encoding="utf-8")
    cli_doc = (ROOT / "docs" / "nanofaas-cli.md").read_text(encoding="utf-8")

    assert "scripts/controlplane.sh run --profile core" in quickstart
    assert "scripts/controlplane.sh build --profile core --dry-run" in quickstart
    assert BUILD_WRAPPER not in quickstart
    assert TUI_WRAPPER not in quickstart

    assert "scripts/controlplane.sh vm up" in control_plane
    assert "scripts/controlplane.sh build --profile container-local --dry-run" in control_plane
    assert BUILD_WRAPPER not in control_plane

    assert "scripts/controlplane.sh jar --profile core" in modules
    assert BUILD_WRAPPER not in modules

    assert "scripts/controlplane.sh functions list" in root_readme
    assert "scripts/controlplane.sh functions show-preset demo-javascript" in root_readme
    assert "scripts/controlplane.sh vm up" in root_readme
    assert "scripts/controlplane.sh cli-test list" in root_readme
    assert "scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run" in root_readme
    assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run" in root_readme
    assert "scripts/controlplane.sh build --profile container-local --dry-run" in root_readme
    assert BUILD_WRAPPER not in root_readme
    assert TUI_WRAPPER not in root_readme
    assert PIPELINE_ALIAS not in root_readme

    assert "scripts/controlplane.sh matrix" in testing
    assert "scripts/controlplane.sh e2e run k3s-junit-curl" in testing
    assert "scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run deploy-host --function-preset demo-java --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run" in testing
    assert "scripts/controlplane.sh cli-test run host-platform --function-preset" not in testing
    assert "scripts/controlplane.sh cli-test run host-platform --scenario-file" not in testing
    assert "multi-function presets are rejected in CLI validation" in testing
    assert "presets such as `demo-java` are exercised end-to-end" in testing
    assert "self-bootstrapping VM-backed scenarios" in testing
    assert "instead of assuming host-installed Helm, kubectl, k3s, registry tooling, or `nanofaas-cli`" in testing
    assert "left pane is plan-ordered top-level phases only" in testing
    assert "nested work is separate detail, not peer phases" in testing
    assert "host-platform` is a compatibility path" in testing
    assert "scripts/controlplane.sh tui" in testing
    assert "scripts/controlplane.sh tui --profile-name" not in testing
    assert "scripts/e2e-loadtest.sh --profile demo-java --dry-run" in testing
    assert "compatibility wrapper over `scripts/controlplane.sh cli-test run vm`" in testing
    assert "cli-stack is the canonical VM-backed CLI stack scenario" in testing
    assert BUILD_WRAPPER not in testing
    assert TUI_WRAPPER not in testing
    assert PIPELINE_ALIAS not in testing

    assert "scripts/controlplane.sh e2e all" in tool_readme
    assert "scripts/controlplane.sh e2e run container-local --functions word-stats-java --dry-run" in tool_readme
    assert "scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run" in tool_readme
    assert "scripts/controlplane.sh cli-test list" in tool_readme
    assert "scripts/controlplane.sh cli-test run vm --saved-profile demo-java --dry-run" in tool_readme
    assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-java --dry-run" in tool_readme
    assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run" in tool_readme
    assert "scripts/controlplane.sh cli-test run host-platform --saved-profile demo-java --dry-run" in tool_readme
    assert "self-bootstrapping VM-backed scenarios" in tool_readme
    assert "instead of requiring host-installed Helm, kubectl, k3s, local-registry tooling, or `nanofaas-cli`" in tool_readme
    assert "left pane is plan-ordered top-level phases only" in tool_readme
    assert "nested work is separate detail, not peer phases" in tool_readme
    assert "scripts/controlplane.sh loadtest show-profile quick" in tool_readme
    assert "scripts/controlplane.sh loadtest run --saved-profile demo-java --dry-run" in tool_readme
    assert "scripts/controlplane.sh vm up" in tool_readme
    assert "scripts/controlplane.sh functions show-preset demo-java" in tool_readme
    assert "scripts/controlplane.sh functions show-preset demo-javascript" in tool_readme
    assert "cli_test.default_scenario" in tool_readme
    assert "single-function" in tool_readme
    assert BUILD_WRAPPER not in tool_readme
    assert TUI_WRAPPER not in tool_readme
    assert PIPELINE_ALIAS not in tool_readme

    assert BUILD_WRAPPER not in claude
    assert TUI_WRAPPER not in claude
    assert PIPELINE_ALIAS not in claude

    assert "./scripts/controlplane.sh image --profile all" in workflow
    assert WORKFLOW_BUILD_WRAPPER not in workflow

    assert "scripts/controlplane.sh cli-test run vm" in cli_doc
    assert "compatibility wrapper over `scripts/controlplane.sh cli-test run vm`" in cli_doc

    assert "self-bootstrapping VM-backed scenarios" in root_readme
    assert "installs scenario-specific software inside that VM" in root_readme
