import pytest
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components import ScenarioRecipe


def test_compose_recipe_resolves_known_component() -> None:
    recipe = ScenarioRecipe(name="test", component_ids=("vm.ensure_running",))
    components = compose_recipe(recipe)
    assert len(components) == 1
    assert components[0].component_id == "vm.ensure_running"


def test_compose_recipe_raises_for_unknown_component() -> None:
    recipe = ScenarioRecipe(name="test", component_ids=("does.not.exist",))
    with pytest.raises(ValueError, match="Unknown scenario component"):
        compose_recipe(recipe)
