"""Unified loadtest flow driver.

One ordered driver threads a shared RunContext through the canonical loadtest
phases (ensure stack -> prelude -> register -> ensure loadgen -> prepare ->
loadgen body -> cleanup), emitting the same native Workflow/workflow_step events
the per-scenario run()s use today. Per-lifecycle differences are supplied by a
LoadtestConnectivityAdapter (see loadtest_adapter.py).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Any


class FlowPhase(Enum):
    """Insertion points for adapter-supplied extra steps."""
    AFTER_STACK_READY = auto()   # after stack ensured + provisioned (proxmox: publish CP port)
    BEFORE_LOADGEN = auto()      # after loadgen ensured, before the loadgen body (proxmox: publish prom port)


@dataclass
class RunContext:
    """Shared mutable state threaded through run_loadtest_flow.

    Fields are None until the step that produces them runs; adapter resolvers are
    only called after their inputs exist.
    """
    stack_info: Any = None
    stack_host: str | None = None
    loadgen_info: Any = None
    control_plane_url: str | None = None
    prometheus_url: str | None = None
    run_dir: Path | None = None
    remote_paths: Any = None
