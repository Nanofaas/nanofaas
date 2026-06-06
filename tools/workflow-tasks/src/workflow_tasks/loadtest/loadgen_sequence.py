"""Shared loadgen sequence builders.

The loadgen body (K6Config + the install_k6/run_k6/fetch/prometheus/report task
sequence) is identical across the multipass/azure/proxmox loadtest scenarios; the
only differences are already-resolved inputs (endpoints, URLs, paths, runner). These
builders capture the shared shape so the sequence is defined once.

This module must not import controlplane_tool (import-linter contract): callers pass
already-resolved primitives in.
"""
from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

from workflow_tasks.loadtest.models import K6Config, K6Stage


def make_loadtest_k6_config(
    *,
    remote_paths: Any,
    control_plane_url: str,
    target_function: str,
    stages: Sequence[tuple[str, int]],
    vus: int | None,
    duration: str | None,
) -> K6Config:
    """Build the canonical loadtest K6Config from already-resolved inputs.

    ``remote_paths`` is duck-typed: it needs ``.script_path``, ``.summary_path`` and
    ``.payload_path`` (the result of ``two_vm_remote_paths``).
    """
    payload_path = remote_paths.payload_path
    return K6Config(
        script_path=Path(remote_paths.script_path),
        target_url=control_plane_url,
        summary_output_path=Path(remote_paths.summary_path),
        stages=tuple(K6Stage(duration=d, target=t) for d, t in stages),
        env={
            "NANOFAAS_URL": control_plane_url,
            "NANOFAAS_FUNCTION": target_function,
            **({"NANOFAAS_PAYLOAD": str(payload_path)} if payload_path else {}),
        },
        vus=vus,
        duration=duration,
        payload_path=Path(payload_path) if payload_path else None,
    )
