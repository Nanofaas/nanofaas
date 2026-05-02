"""Tests for tui_toolkit.context — UIContext + init_ui / get_ui / bind_ui."""
from __future__ import annotations

from unittest.mock import patch
import os

import pytest
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme


@pytest.fixture(autouse=True)
def _reset_ui_singleton():
    """Each test starts from the bare default — init_ui mutates module state."""
    import tui_toolkit.context as ctx_mod
    saved_shared = ctx_mod._ctx_shared
    yield
    ctx_mod._ctx_shared = saved_shared
    ctx_mod._ctx_var.set(saved_shared)


def test_default_context_is_default_theme_and_default_brand():
    assert get_ui().theme is DEFAULT_THEME
    assert get_ui().brand is DEFAULT_BRAND


def test_init_ui_caps_terminal_width():
    with patch("shutil.get_terminal_size", return_value=os.terminal_size((200, 24))):
        ui = init_ui(UIContext(max_content_cols=140))
    assert ui.content_width == 140


def test_init_ui_uses_real_width_when_smaller_than_cap():
    with patch("shutil.get_terminal_size", return_value=os.terminal_size((100, 24))):
        ui = init_ui(UIContext(max_content_cols=140))
    assert ui.content_width == 100


def test_init_ui_idempotent_last_call_wins():
    ui1 = init_ui(UIContext(theme=Theme(accent="green")))
    ui2 = init_ui(UIContext(theme=Theme(accent="red")))
    assert get_ui() is ui2
    assert get_ui().theme.accent == "red"


def test_init_ui_default_argument_uses_defaults():
    ui = init_ui()
    assert ui.theme is DEFAULT_THEME
    assert ui.brand is DEFAULT_BRAND


def test_bind_ui_temporary_override_restores_previous():
    init_ui(UIContext(theme=Theme(accent="cyan")))
    custom = UIContext(theme=Theme(accent="magenta"), brand=AppBrand(name="x"))
    with bind_ui(custom):
        assert get_ui().theme.accent == "magenta"
        assert get_ui().brand.name == "x"
    assert get_ui().theme.accent == "cyan"


def test_init_ui_returns_resolved_context_with_width_populated():
    ui = init_ui(UIContext())
    assert ui.content_width is not None
    assert ui.content_width > 0
