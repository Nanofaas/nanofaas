from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

from controlplane_tool.scenario_components.operations import ScenarioOperation


def _noop_planner(_: object) -> tuple[ScenarioOperation, ...]:
    return ()


@dataclass(frozen=True, slots=True)
class ScenarioComponentDefinition:
    component_id: str
    summary: str
    planner: Callable[[object], tuple[ScenarioOperation, ...]] = field(
        default=_noop_planner,
        repr=False,
        compare=False,
    )


@dataclass(frozen=True, slots=True)
class ScenarioRecipe:
    name: str
    component_ids: list[str]
    requires_managed_vm: bool = True
