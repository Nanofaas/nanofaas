import sys

from typer.testing import CliRunner

import controlplane_tool.app.main as main_mod
import controlplane_tool.tui.app as tui_app


def test_run_command_supports_passthrough_gradle_args_in_dry_run() -> None:
    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        [
            "run",
            "--profile",
            "container-local",
            "--dry-run",
            "--",
            "--args=--nanofaas.deployment.default-backend=container-local",
        ],
    )

    assert result.exit_code == 0
    assert ":control-plane:bootRun" in result.stdout
    assert "container-deployment-provider" in result.stdout
    assert "--args=--nanofaas.deployment.default-backend=container-local" in result.stdout


def test_tui_command_launches_interactive_tui(monkeypatch) -> None:
    calls: list[str] = []

    class FakeTUI:
        def run(self) -> None:
            calls.append("run")

    monkeypatch.setattr(tui_app, "NanofaasTUI", FakeTUI)

    runner = CliRunner()
    result = runner.invoke(main_mod.app, ["tui"])

    assert result.exit_code == 0
    assert calls == ["run"]


def test_main_no_args_launches_interactive_tui(monkeypatch) -> None:
    calls: list[str] = []

    class FakeTUI:
        def run(self) -> None:
            calls.append("run")

    monkeypatch.setattr(tui_app, "NanofaasTUI", FakeTUI)
    monkeypatch.setattr(main_mod, "install_rich_tracebacks", lambda **kwargs: None)
    monkeypatch.setattr(sys, "argv", ["controlplane-tool"])

    main_mod.main()

    assert calls == ["run"]
