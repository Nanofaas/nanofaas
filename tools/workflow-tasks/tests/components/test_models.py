from __future__ import annotations

import pytest

from workflow_tasks.components.models import ScenarioComponentDefinition, ScenarioRecipe


def test_recipe_defaults_to_requires_managed_vm_true() -> None:
    recipe = ScenarioRecipe(name="r", component_ids=("a", "b"))
    assert recipe.requires_managed_vm is True
    assert recipe.component_ids == ("a", "b")


def test_component_definition_default_planner_raises() -> None:
    comp = ScenarioComponentDefinition(component_id="c", summary="s")
    with pytest.raises(NotImplementedError):
        comp.planner(object())


def test_component_definition_accepts_custom_planner() -> None:
    def planner(_: object) -> tuple:
        return ()

    comp = ScenarioComponentDefinition(component_id="c", summary="s", planner=planner)
    assert comp.planner(object()) == ()
