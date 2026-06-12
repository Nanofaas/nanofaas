from __future__ import annotations

import pytest

from controlplane_tool.scenario.catalog import (
    SCENARIOS,
    canonical_scenario_name,
    resolve_scenario,
)

# Grows in Tasks 2-4 as each category is renamed; final state = the spec table.
RENAME_PAIRS: list[tuple[str, str]] = []


def test_canonical_name_passthrough_for_canonical_and_unknown() -> None:
    assert canonical_scenario_name("cli-stack") == "cli-stack"
    # Unknown names pass through unchanged so existing validation errors stay intact.
    assert canonical_scenario_name("does-not-exist") == "does-not-exist"


def test_every_alias_resolves_to_its_canonical_definition() -> None:
    for scenario in SCENARIOS:
        for alias in scenario.aliases:
            assert canonical_scenario_name(alias) == scenario.name
            assert resolve_scenario(canonical_scenario_name(alias)).name == scenario.name


def test_no_alias_collides_with_a_canonical_name() -> None:
    canonical = {s.name for s in SCENARIOS}
    for scenario in SCENARIOS:
        for alias in scenario.aliases:
            assert alias not in canonical


@pytest.mark.parametrize(("old", "new"), RENAME_PAIRS)
def test_rename_pairs_resolve(old: str, new: str) -> None:
    assert canonical_scenario_name(old) == new
