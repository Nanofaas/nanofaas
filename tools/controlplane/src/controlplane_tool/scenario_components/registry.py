"""
registry.py — Explicit component registry for ScenarioComponentDefinition.

Replaces the hardcoded _COMPONENT_LIBRARY dict in composer.py.
"""
from __future__ import annotations

from controlplane_tool.scenario_components.models import ScenarioComponentDefinition


class ComponentRegistry:
    """Holds all known ScenarioComponentDefinitions, keyed by component_id."""

    def __init__(self) -> None:
        self._store: dict[str, ScenarioComponentDefinition] = {}

    def register(self, component: ScenarioComponentDefinition) -> None:
        if component.component_id in self._store:
            raise ValueError(
                f"Component '{component.component_id}' already registered"
            )
        self._store[component.component_id] = component

    def get(self, component_id: str) -> ScenarioComponentDefinition:
        try:
            return self._store[component_id]
        except KeyError:
            raise ValueError(f"Unknown scenario component: {component_id}") from None

    def all_ids(self) -> list[str]:
        return list(self._store.keys())
