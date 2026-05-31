from __future__ import annotations

import pytest

from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.registry import ComponentRegistry


def _comp(cid: str) -> ScenarioComponentDefinition:
    return ScenarioComponentDefinition(component_id=cid, summary=cid)


def test_register_and_get_roundtrip() -> None:
    reg = ComponentRegistry()
    comp = _comp("a.b")
    reg.register(comp)
    assert reg.get("a.b") is comp
    assert reg.all_ids() == ["a.b"]


def test_register_duplicate_raises() -> None:
    reg = ComponentRegistry()
    reg.register(_comp("a.b"))
    with pytest.raises(ValueError, match="already registered"):
        reg.register(_comp("a.b"))


def test_get_unknown_raises() -> None:
    reg = ComponentRegistry()
    with pytest.raises(ValueError, match="Unknown scenario component"):
        reg.get("missing")
