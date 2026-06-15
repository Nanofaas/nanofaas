from __future__ import annotations

import pytest

from controlplane_tool.building.image_plan import (
    DEFAULT_ARCHES,
    select_image_targets,
)


def test_select_targets_all_returns_current_catalog() -> None:
    assert set(select_image_targets("all")) == {
        "control-plane",
        "function-runtime",
        "java-word-stats",
        "java-json-transform",
        "java-lite-word-stats",
        "java-lite-json-transform",
        "go-word-stats",
        "go-json-transform",
        "python-word-stats",
        "python-json-transform",
        "javascript-word-stats",
        "javascript-json-transform",
        "bash-word-stats",
        "bash-json-transform",
        "watchdog",
    }


def test_select_targets_csv_preserves_order() -> None:
    assert select_image_targets("watchdog,control-plane") == ["watchdog", "control-plane"]


def test_select_targets_rejects_unknown_target() -> None:
    with pytest.raises(ValueError, match="Unknown image target"):
        select_image_targets("watchdog,nope")


def test_default_arches_are_amd64_then_arm64() -> None:
    assert DEFAULT_ARCHES == ("amd64", "arm64")
