"""Tests for tui_toolkit.pickers — select, multiselect, Choice, Separator."""
from __future__ import annotations

import asyncio
from unittest.mock import patch

from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput
import pytest
import questionary

from tui_toolkit.brand import AppBrand
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.pickers import (
    Choice,
    Separator,
    _ESCAPE_RESULT,
    _INTERRUPT_RESULT,
    _build_select_application,
    multiselect,
    select,
)
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


def test_non_tty_questionary_cancellation_stays_indistinguishable(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    class _Q:
        result: str | None = None

        def ask(self):
            return self.result

    prompt = _Q()
    monkeypatch.setattr(questionary, "select", lambda *a, **kw: prompt)
    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")])
    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")], escape_value="back")

    prompt.result = "back"
    assert (
        select(
            "x",
            choices=[Choice("t", "v")],
            include_back=True,
            escape_value="back",
        )
        == "back"
    )


@pytest.mark.parametrize(
    ("key", "expected"),
    [("\x1b", _ESCAPE_RESULT), ("\x03", _INTERRUPT_RESULT)],
)
def test_full_screen_select_distinguishes_escape_from_ctrl_c(key, expected):
    with create_pipe_input() as pipe_input:
        app = _build_select_application(
            "Pick one",
            [Choice("Title", "value")],
            default=None,
            title=None,
            breadcrumb=None,
            footer_hint=None,
            input=pipe_input,
            output=DummyOutput(),
        )

        def send_key_after_start() -> None:
            asyncio.get_running_loop().call_later(0.01, pipe_input.send_text, key)

        assert app.run(pre_run=send_key_after_start) is expected


def test_select_escape_value_is_opt_in_and_ctrl_c_still_interrupts(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("sys.stdout.isatty", lambda: True)

    class FakeApplication:
        result = _ESCAPE_RESULT

        def run(self):
            return self.result

    app = FakeApplication()
    monkeypatch.setattr(
        "tui_toolkit.pickers._build_select_application",
        lambda *args, **kwargs: app,
    )

    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")])
    assert select("x", choices=[Choice("t", "v")], escape_value="back") == "back"

    app.result = _INTERRUPT_RESULT
    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")], escape_value="back")


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


def test_full_screen_ctrl_q_interrupts_when_escape_value_is_opted_in(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("sys.stdout.isatty", lambda: True)

    with create_pipe_input() as pipe_input:
        app = _build_select_application(
            "Pick one",
            [Choice("Title", "value")],
            default=None,
            title=None,
            breadcrumb=None,
            footer_hint=None,
            input=pipe_input,
            output=DummyOutput(),
        )
        run_application = app.run

        def run_with_ctrl_q():
            def send_ctrl_q_after_start() -> None:
                asyncio.get_running_loop().call_later(
                    0.01, pipe_input.send_text, "\x11"
                )

            return run_application(pre_run=send_ctrl_q_after_start)

        monkeypatch.setattr(app, "run", run_with_ctrl_q)
        monkeypatch.setattr(
            "tui_toolkit.pickers._build_select_application",
            lambda *args, **kwargs: app,
        )

        with pytest.raises(KeyboardInterrupt):
            select(
                "Pick one",
                choices=[Choice("Title", "value")],
                escape_value="back",
            )
