from __future__ import annotations

import pytest

from controlplane_tool.scenario_components.composer import (
    compose_recipe,
    recipe_task_ids,
)
from controlplane_tool.scenario_components.models import ScenarioRecipe
from controlplane_tool.scenario_components.recipes import build_scenario_recipe


def test_compose_recipe_returns_ordered_component_definitions() -> None:
    recipe = build_scenario_recipe("k3s-junit-curl")

    components = compose_recipe(recipe)

    assert tuple(component.component_id for component in components) == recipe.component_ids
    assert components[0].component_id == "vm.ensure_running"
    assert components[-1].component_id == "vm.down"
    assert recipe_task_ids(recipe) == list(recipe.component_ids)


def test_compose_recipe_rejects_unknown_component_ids() -> None:
    recipe = ScenarioRecipe(
        name="unknown-component",
        component_ids=("vm.ensure_running", "missing.component"),
    )

    with pytest.raises(ValueError, match="Unknown scenario component: missing.component"):
        compose_recipe(recipe)


def test_build_scenario_recipe_returns_isolated_component_ids() -> None:
    recipe = build_scenario_recipe("cli-stack")
    task_ids = recipe_task_ids(recipe)

    task_ids.append("extra.component")

    assert "extra.component" not in recipe.component_ids
    assert recipe.component_ids[-1] == "tests.verify_cli_stack_status_fails"
