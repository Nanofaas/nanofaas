"""Tests for tui_toolkit.chrome — render_screen_frame."""
from __future__ import annotations

import pytest
from rich.console import Console
from rich.text import Text

from tui_toolkit.brand import AppBrand
from tui_toolkit.chrome import render_screen_frame
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.theme import Theme


def _render(panel) -> str:
    rec = Console(record=True, width=120, force_terminal=True, color_system="truecolor")
    rec.print(panel)
    return rec.export_text(styles=False)


def test_render_screen_frame_with_brand_includes_logo_and_wordmark():
    brand = AppBrand(name="demo", wordmark="DEMO", ascii_logo="◆ DEMO LOGO ◆")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="Title", body=Text("hello"))
    text = _render(panel)
    assert "DEMO" in text
    assert "◆ DEMO LOGO ◆" in text
    assert "hello" in text
    assert "Title" in text


def test_render_screen_frame_without_brand_does_not_show_nanofaas():
    """Empty wordmark / logo render as no-op."""
    with bind_ui(UIContext(brand=AppBrand())):
        panel = render_screen_frame(title="Title", body=Text("body"))
    text = _render(panel)
    assert "body" in text
    assert "NANOFAAS" not in text


def test_render_screen_frame_uses_default_breadcrumb_from_brand():
    brand = AppBrand(default_breadcrumb="Home")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="x", body=Text("y"))
    text = _render(panel)
    assert "Home" in text


def test_render_screen_frame_explicit_breadcrumb_overrides_default():
    brand = AppBrand(default_breadcrumb="Home")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="x", body=Text("y"), breadcrumb="Sub")
    text = _render(panel)
    assert "Sub" in text
    assert "Home" not in text


def test_render_screen_frame_footer_hint_default_and_override():
    brand = AppBrand(default_footer_hint="Esc back")
    with bind_ui(UIContext(brand=brand)):
        default_panel = render_screen_frame(title="x", body=Text("y"))
        custom_panel = render_screen_frame(title="x", body=Text("y"), footer_hint="Q quit")
    assert "Esc back" in _render(default_panel)
    text2 = _render(custom_panel)
    assert "Q quit" in text2
    assert "Esc back" not in text2


def test_render_screen_frame_uses_theme_border_style():
    """Border colour follows theme.accent_dim."""
    theme = Theme(accent_dim="red dim")
    with bind_ui(UIContext(theme=theme, brand=AppBrand())):
        panel = render_screen_frame(title="x", body=Text("y"))
    assert panel.border_style == "red dim"
