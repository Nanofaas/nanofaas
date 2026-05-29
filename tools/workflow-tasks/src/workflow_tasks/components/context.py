from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from workflow_tasks.vm.models import VmRequest


class ResolvedFunctionView(Protocol):
    """Structural view of a resolved function, as read by components."""

    key: str
    family: str | None
    runtime: str
    image: str | None


class ResolvedScenarioView(Protocol):
    """Structural view of a resolved scenario, as read by components.

    The concrete ResolvedScenario (pydantic) in controlplane satisfies this by shape.
    """

    namespace: str | None
    functions: Sequence[ResolvedFunctionView]


@dataclass(frozen=True, slots=True)
class ScenarioExecutionContext:
    """Neutral execution context consumed by scenario components.

    Deliberately free of product request types (E2eRequest/CliTestRequest): the
    factory that builds it lives in controlplane.
    """

    repo_root: Path
    scenario_name: str
    runtime: str
    namespace: str | None
    local_registry: str
    resolved_scenario: ResolvedScenarioView | None
    vm_request: VmRequest
    cleanup_vm: bool
    manifest_path: Path | None = None
    release: str | None = None
    loadgen_vm_request: VmRequest | None = None
