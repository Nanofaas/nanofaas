import pytest
from controlplane_tool.scenario.components.registry import ComponentRegistry
from controlplane_tool.scenario.components import ScenarioComponentDefinition


def _make_component(component_id: str) -> ScenarioComponentDefinition:
    return ScenarioComponentDefinition(
        component_id=component_id,
        summary=f"summary for {component_id}",
    )


def test_registry_returns_registered_component() -> None:
    reg = ComponentRegistry()
    comp = _make_component("vm.ensure_running")
    reg.register(comp)
    assert reg.get("vm.ensure_running") is comp


def test_registry_raises_for_unknown_component() -> None:
    reg = ComponentRegistry()
    with pytest.raises(ValueError, match="Unknown scenario component: missing.id"):
        reg.get("missing.id")


def test_registry_register_twice_raises() -> None:
    reg = ComponentRegistry()
    comp = _make_component("vm.ensure_running")
    reg.register(comp)
    with pytest.raises(ValueError, match="already registered"):
        reg.register(comp)


def test_registry_all_returns_registered_components() -> None:
    reg = ComponentRegistry()
    a = _make_component("a")
    b = _make_component("b")
    reg.register(a)
    reg.register(b)
    assert set(reg.all_ids()) == {"a", "b"}
