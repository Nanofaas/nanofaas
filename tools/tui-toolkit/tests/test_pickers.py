"""Tests for tui_toolkit.pickers — select, multiselect, Choice, Separator."""
from __future__ import annotations

from unittest.mock import patch

import pytest
import questionary

from tui_toolkit.brand import AppBrand
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.pickers import Choice, Separator, multiselect, select
from tui_toolkit.theme import Theme


def test_choice_dataclass_basic():
    c = Choice(title="Title", value="v", description="desc")
    assert c.title == "Title"
    assert c.value == "v"
    assert c.description == "desc"


def test_choice_default_description_is_empty():
    c = Choice(title="t", value="v")
    assert c.description == ""


def test_separator_re_export():
    assert Separator is questionary.Separator


def test_select_non_tty_falls_back_to_questionary(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)
    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["message"] = message
        captured["choices"] = kwargs.get("choices")
        captured["default"] = kwargs.get("default")
        captured["style"] = kwargs.get("style")
        class _Q:
            def ask(self):
                return "v1"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)

    result = select(
        "Pick one",
        choices=[Choice("Title 1", "v1", "desc1"), Choice("Title 2", "v2", "desc2")],
    )
    assert result == "v1"
    assert captured["message"] == "Pick one"
    assert captured["style"] is not None


def test_select_with_back_choice_appends_back_option(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["choices"] = kwargs["choices"]
        class _Q:
            def ask(self):
                return "v1"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)
    select(
        "Pick one",
        choices=[Choice("Title 1", "v1")],
        include_back=True,
    )
    values = [getattr(c, "value", None) for c in captured["choices"]]
    assert "back" in values


def test_multiselect_non_tty_falls_back_to_questionary(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_checkbox(message, **kwargs):
        captured["message"] = message
        captured["default"] = kwargs.get("default")
        class _Q:
            def ask(self):
                return ["v1", "v2"]
        return _Q()

    monkeypatch.setattr(questionary, "checkbox", fake_checkbox)
    result = multiselect(
        "Pick many",
        choices=[Choice("T1", "v1"), Choice("T2", "v2")],
        default_values=["v1"],
    )
    assert result == ["v1", "v2"]
    assert captured["default"] == ["v1"]


def test_select_empty_choices_raises():
    with pytest.raises(ValueError, match="choices"):
        select("x", choices=[])


def test_multiselect_empty_choices_raises():
    with pytest.raises(ValueError, match="choices"):
        multiselect("x", choices=[])


def test_select_keyboard_interrupt_when_questionary_returns_none(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    class _Q:
        def ask(self):
            return None

    monkeypatch.setattr(questionary, "select", lambda *a, **kw: _Q())
    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")])


def test_select_uses_theme_via_to_questionary_style(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["style"] = kwargs["style"]
        class _Q:
            def ask(self):
                return "v"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)
    with bind_ui(UIContext(theme=Theme(accent="green", accent_strong="bold green"))):
        select("x", choices=[Choice("t", "v")])

    rules = dict(captured["style"].style_rules)
    assert rules["selected"] == "fg:green"
    assert rules["pointer"] == "fg:green bold"
