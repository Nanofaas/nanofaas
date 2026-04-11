from __future__ import annotations

from controlplane_tool.scenario_components.composer import compose_recipe, recipe_task_ids
from controlplane_tool.scenario_components.recipes import build_scenario_recipe


def test_compose_recipe_returns_ordered_component_definitions() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")

    components = compose_recipe(recipe)

    assert [component.component_id for component in components] == recipe.component_ids
    assert components[0].component_id == "vm.ensure_running"
    assert components[-1].component_id == "vm.down"
    assert recipe_task_ids(recipe) == recipe.component_ids
