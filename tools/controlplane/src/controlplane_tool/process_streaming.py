from __future__ import annotations

import os
from pathlib import Path
import subprocess
from threading import Thread

from controlplane_tool.console import workflow_log
from controlplane_tool.workflow_models import WorkflowContext


def spawn_logged_process(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    log_path: Path,
    workflow_context: WorkflowContext | None = None,
) -> subprocess.Popen[str]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("a", encoding="utf-8")
    process = subprocess.Popen(
        command,
        cwd=str(cwd),
        env={**os.environ, **(env or {})},
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )

    def _pump() -> None:
        try:
            assert process.stdout is not None
            for line in process.stdout:
                log_file.write(line)
                log_file.flush()
                workflow_log(line.rstrip("\n"), stream="stdout", context=workflow_context)
        finally:
            if process.stdout is not None:
                process.stdout.close()
            log_file.close()

    Thread(target=_pump, daemon=True).start()
    return process
