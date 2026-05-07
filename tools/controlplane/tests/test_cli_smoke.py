from pathlib import Path
from typer.testing import CliRunner

from controlplane_tool.app.main import app
from controlplane_tool.workspace.paths import resolve_workspace_path

PIPELINE_ALIAS = "pipeline" + "-run"


def test_cli_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    assert "Control plane" in result.stdout
    assert PIPELINE_ALIAS not in result.stdout


def test_tooling_lockfile_exists() -> None:
    assert resolve_workspace_path(Path("tools/controlplane/uv.lock")).exists()


def test_vm_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["vm", "--help"])
    assert result.exit_code == 0
    assert "vm" in result.stdout.lower()


def test_e2e_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "--help"])
    assert result.exit_code == 0
    assert "e2e" in result.stdout.lower()


def test_cli_test_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["cli-test", "--help"])
    assert result.exit_code == 0
    assert "cli" in result.stdout.lower()


def test_loadtest_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["loadtest", "--help"])
    assert result.exit_code == 0
    assert "loadtest" in result.stdout.lower()


def test_functions_group_help_exits_zero() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["functions", "--help"])
    assert result.exit_code == 0
    assert "functions" in result.stdout.lower()


def test_tui_help_exposes_interactive_entrypoint_not_profile_runner() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["tui", "--help"])

    assert result.exit_code == 0
    assert "--profile-name" not in result.stdout
    assert "--use-saved-profile" not in result.stdout


def test_top_level_help_hides_legacy_runner_groups() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["--help"])

    assert "cli-e2e" not in result.stdout
    assert "local-e2e" not in result.stdout


def test_generic_controlplane_wrapper_uses_locked_tool() -> None:
    script = resolve_workspace_path(Path("scripts/controlplane.sh")).read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked controlplane-tool" in script


def test_demo_java_profile_exists() -> None:
    assert resolve_workspace_path(Path("tools/controlplane/profiles/demo-java.toml")).exists()


def test_demo_javascript_profile_exists() -> None:
    assert resolve_workspace_path(Path("tools/controlplane/profiles/demo-javascript.toml")).exists()


def test_cli_vm_runner_not_exported_from_cli_runtime() -> None:
    """CliVmRunner must not be accessible via cli.runtime after legacy cleanup."""
    import controlplane_tool.cli.runtime as _rt
    assert not hasattr(_rt, "CliVmRunner"), (
        "CliVmRunner still accessible via cli.runtime — remove it"
    )


def test_tui_does_not_offer_legacy_vm_runner_option() -> None:
    """TUI CLI E2E menu must not offer the legacy vm runner choice after cleanup."""
    from controlplane_tool.tui import app as tui_app
    vm_values = [c.value for c in tui_app._CLI_E2E_RUNNER_CHOICES]
    assert "vm" not in vm_values, (
        f"TUI still offers vm as a CLI E2E runner choice: {vm_values}"
    )


def test_removed_pipeline_run_command_is_rejected() -> None:
    runner = CliRunner()
    result = runner.invoke(app, [PIPELINE_ALIAS, "--help"])
    assert result.exit_code != 0
    assert PIPELINE_ALIAS in result.stdout + result.stderr
