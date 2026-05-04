from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

from controlplane_tool.scenario.components.operations import ScenarioOperation


def _planner_not_implemented(_: object) -> tuple[ScenarioOperation, ...]:
    raise NotImplementedError("ScenarioComponentDefinition.planner is not implemented")


@dataclass(frozen=True, slots=True)
class ScenarioComponentDefinition:
    component_id: str
    summary: str
    planner: Callable[[object], tuple[ScenarioOperation, ...]] = field(
        default=_planner_not_implemented,
        repr=False,
        compare=False,
    )


@dataclass(frozen=True, slots=True)
class ScenarioRecipe:
    name: str
    component_ids: tuple[str, ...]
    requires_managed_vm: bool = True
