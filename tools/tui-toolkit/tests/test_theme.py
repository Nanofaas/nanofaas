"""Tests for tui_toolkit.theme — Theme dataclass and style adapters."""
from __future__ import annotations

from pathlib import Path

import pytest
from tui_toolkit.theme import (
    DEFAULT_THEME,
    Theme,
    _to_pt,
    to_questionary_style,
)

GOLDEN_DIR = Path(__file__).parent / "golden"


def test_default_theme_uses_cyan_palette():
    assert DEFAULT_THEME.accent == "cyan"
    assert DEFAULT_THEME.accent_strong == "bold cyan"
    assert DEFAULT_THEME.accent_dim == "cyan dim"
    assert DEFAULT_THEME.success == "green"
    assert DEFAULT_THEME.warning == "yellow"
    assert DEFAULT_THEME.error == "red"
    assert DEFAULT_THEME.muted == "dim"
    assert DEFAULT_THEME.brand == "bold cyan"


def test_default_theme_default_icons():
    assert DEFAULT_THEME.icon_running == "▸"
    assert DEFAULT_THEME.icon_completed == "✓"
    assert DEFAULT_THEME.icon_failed == "✗"
    assert DEFAULT_THEME.icon_warning == "⚠"
    assert DEFAULT_THEME.icon_skipped == "⊘"
    assert DEFAULT_THEME.icon_updated == "↺"
    assert DEFAULT_THEME.icon_cancelled == "⊘"


def test_with_overrides_returns_new_immutable_theme():
    derived = DEFAULT_THEME.with_overrides(accent="green", success="blue")
    assert derived.accent == "green"
    assert derived.success == "blue"
    # original is unchanged
    assert DEFAULT_THEME.accent == "cyan"
    assert DEFAULT_THEME.success == "green"
    # frozen
    with pytest.raises(AttributeError):
        DEFAULT_THEME.accent = "red"  # type: ignore[misc]


@pytest.mark.parametrize("rich_style, expected_pt", [
    ("cyan", "fg:cyan"),
    ("bold cyan", "fg:cyan bold"),
    ("cyan dim", "fg:cyan"),  # 'dim' as modifier is dropped
    ("dim", "fg:grey"),       # 'dim' alone → grey foreground (legacy mapping)
    ("grey", "fg:grey"),
    ("green", "fg:green"),
    ("red", "fg:red"),
    ("yellow", "fg:yellow"),
    ("bold", "bold"),
    ("", ""),
])
def test_to_pt_translates_rich_format(rich_style, expected_pt):
    assert _to_pt(rich_style) == expected_pt


def test_to_questionary_style_matches_legacy_byte_for_byte():
    """Parity gate: DEFAULT_THEME must produce the exact same selector→style
    mapping as the legacy `_STYLE` from controlplane_tool.tui_widgets."""
    qs = to_questionary_style(DEFAULT_THEME)
    # Serialize in the same format as the captured golden file.
    actual_lines = [f"{sel}\t{style}" for sel, style in qs.style_rules]
    expected = (GOLDEN_DIR / "legacy_questionary_style.txt").read_text(encoding="utf-8")
    expected_lines = [line for line in expected.splitlines() if line.rstrip("\t")]
    assert actual_lines == expected_lines


def test_theme_overrides_propagate_to_questionary_style():
    custom = DEFAULT_THEME.with_overrides(accent_strong="bold green", accent="green")
    qs = to_questionary_style(custom)
    rules = dict(qs.style_rules)
    assert rules["pointer"] == "fg:green bold"
    assert rules["selected"] == "fg:green"
