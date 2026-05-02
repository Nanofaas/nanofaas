"""
Tests for module_catalog.py — MODULES catalog and module_choices().
"""
from __future__ import annotations

from controlplane_tool.module_catalog import MODULES, ModuleInfo, module_choices


def test_modules_is_non_empty() -> None:
    assert len(MODULES) > 0


def test_modules_contains_async_queue() -> None:
    keys = {m.key for m in MODULES}
    assert "async-queue" in keys


def test_modules_contains_autoscaler() -> None:
    keys = {m.key for m in MODULES}
    assert "autoscaler" in keys


def test_all_modules_have_non_empty_name_and_description() -> None:
    for m in MODULES:
        assert m.name.strip(), f"Module {m.key!r} has empty name"
        assert m.description.strip(), f"Module {m.key!r} has empty description"


def test_module_keys_are_unique() -> None:
    keys = [m.key for m in MODULES]
    assert len(keys) == len(set(keys))


def test_module_choices_returns_list() -> None:
    choices = module_choices()
    assert isinstance(choices, list)


def test_module_choices_returns_all_modules() -> None:
    assert len(module_choices()) == len(MODULES)


def test_module_choices_returns_module_info_instances() -> None:
    for item in module_choices():
        assert isinstance(item, ModuleInfo)


def test_module_choices_is_independent_copy() -> None:
    choices = module_choices()
    choices.clear()
    assert len(module_choices()) > 0
