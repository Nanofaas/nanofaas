"""Tests for tui_toolkit.console — Rich Console singleton + width helpers."""
from __future__ import annotations

from rich.console import Console

import tui_toolkit.console as console_mod
from tui_toolkit.console import console, get_content_width


def test_console_is_a_rich_console():
    assert isinstance(console, Console)


def test_get_content_width_default_is_max_cap():
    width = get_content_width()
    assert width == 140


def test_apply_width_updates_console_and_helpers():
    console_mod._apply_width(100)
    assert get_content_width() == 100
    console_mod._apply_width(140)
