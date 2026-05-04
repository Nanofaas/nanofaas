from __future__ import annotations

from controlplane_tool.loadtest.loadtest_models import LoadProfileDefinition, LoadStage
from controlplane_tool.core.models import LoadProfile

_LOAD_PROFILES: tuple[LoadProfileDefinition, ...] = (
    LoadProfileDefinition(
        name="quick",
        description="Fast validation run for local dry-runs and smoke checks.",
        stages=[
            LoadStage(duration="15s", target=1),
            LoadStage(duration="30s", target=3),
        ],
        summary_window_seconds=30,
    ),
    LoadProfileDefinition(
        name="smoke",
        description="Short steady-state profile for pre-merge verification.",
        stages=[
            LoadStage(duration="30s", target=2),
            LoadStage(duration="60s", target=4),
        ],
        summary_window_seconds=60,
    ),
    LoadProfileDefinition(
        name="stress",
        description="Higher sustained load profile for benchmark and autoscaling checks.",
        stages=[
            LoadStage(duration="45s", target=5),
            LoadStage(duration="90s", target=10),
        ],
        summary_window_seconds=120,
    ),
)


def list_load_profiles() -> list[LoadProfileDefinition]:
    return [profile.model_copy(deep=True) for profile in _LOAD_PROFILES]


def resolve_load_profile(name: LoadProfile | str) -> LoadProfileDefinition:
    for profile in _LOAD_PROFILES:
        if profile.name == name:
            return profile.model_copy(deep=True)
    raise KeyError(f"unknown load profile: {name}")
