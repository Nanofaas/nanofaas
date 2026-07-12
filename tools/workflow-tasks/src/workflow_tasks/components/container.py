from __future__ import annotations

import subprocess
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from workflow_tasks.core.resource_task import ResourceTask


def managed_process_resource(
    *,
    task_id: str,
    title: str,
    argv: tuple[str, ...],
    cwd: Path | None = None,
    spawn: Callable[..., Any] = subprocess.Popen,
    ready: Callable[[], bool],
    readiness_attempts: int = 90,
    readiness_interval: float = 1.0,
) -> ResourceTask:
    """Start a local process and guarantee termination after the workflow."""
    process: Any | None = None

    def release() -> None:
        nonlocal process
        if process is None or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def acquire() -> None:
        nonlocal process
        process = spawn(argv, cwd=cwd, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        current = process
        if current is None:  # pragma: no cover - invalid injected spawn contract
            raise RuntimeError(f"{title} failed to start")
        for _ in range(readiness_attempts):
            if current.poll() is not None:
                break
            if ready():
                return
            time.sleep(readiness_interval)
        release()
        raise RuntimeError(f"{title} did not become ready")

    return ResourceTask(
        task_id=task_id,
        title=title,
        acquire=acquire,
        release=release,
    )
