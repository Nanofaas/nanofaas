from __future__ import annotations

import sys

import typer
from rich.traceback import install as install_rich_tracebacks

from controlplane_tool.cli_commands import install_cli_commands
from controlplane_tool.cli_test_commands import install_cli_test_commands
from controlplane_tool.e2e_commands import install_e2e_commands
from controlplane_tool.function_commands import install_function_commands
from controlplane_tool.loadtest_commands import install_loadtest_commands
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.vm_commands import install_vm_commands

app = typer.Typer(
    help="Control plane orchestration product for build, test, and reporting."
)


@app.command("tui")
def tui() -> None:
    from controlplane_tool.tui_app import NanofaasTUI

    NanofaasTUI().run()


@app.command("prefect-runtime-smoke", hidden=True)
def prefect_runtime_smoke() -> None:
    result = run_local_flow("controlplane.prefect_runtime_smoke", lambda: "ok")
    typer.echo(
        f"{result.flow_id} {result.status} {result.orchestrator_backend} {result.flow_run_id}"
    )


install_cli_commands(app)
install_cli_test_commands(app)
install_vm_commands(app)
install_e2e_commands(app)
install_function_commands(app)
install_loadtest_commands(app)


def main() -> None:
    install_rich_tracebacks(show_locals=False)
    # No arguments → launch the interactive Rich TUI
    if len(sys.argv) == 1:
        from controlplane_tool.tui_app import NanofaasTUI
        NanofaasTUI().run()
        return
    app()


if __name__ == "__main__":
    main()
