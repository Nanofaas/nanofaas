from __future__ import annotations

import typer

from workflow_tasks.orchestration import FlowRunResult


def exit_on_failed_flow(flow_result: FlowRunResult) -> None:
    """Print the flow error (it was previously swallowed — see PR #118) and exit 1.

    No-op when the flow completed.
    """
    if flow_result.status == "completed":
        return
    detail = (flow_result.error or "flow failed with no recorded error").rstrip()
    typer.echo(f"Flow {flow_result.flow_id} failed:\n{detail}", err=True)
    raise typer.Exit(code=1)
