from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ModuleInfo:
    key: str
    name: str
    description: str


MODULES: tuple[ModuleInfo, ...] = (
    ModuleInfo(
        key="async-queue",
        name="Async Queue",
        description="Bounded async invocation queue with retry-aware enqueue semantics.",
    ),
    ModuleInfo(
        key="autoscaler",
        name="Autoscaler",
        description="Adaptive scaling decisions based on runtime metrics and queue pressure.",
    ),
    ModuleInfo(
        key="building-metadata",
        name="Build Metadata",
        description="Expose building and module metadata for diagnostics and observability.",
    ),
    ModuleInfo(
        key="image-validator",
        name="Image Validator",
        description="Validate function images before registration and dispatch.",
    ),
    ModuleInfo(
        key="runtime-config",
        name="Runtime Config",
        description="Apply runtime-level control-plane and dispatch configuration at startup.",
    ),
    ModuleInfo(
        key="sync-queue",
        name="Sync Queue",
        description="Bounded synchronous path queue with configurable backpressure behavior.",
    ),
)


def module_choices() -> list[ModuleInfo]:
    return list(MODULES)
