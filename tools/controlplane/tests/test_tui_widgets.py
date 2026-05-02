from tui_toolkit import get_content_width
from tui_toolkit.pickers import (
    Choice as _DescribedChoice,
    _back_choice,
    _build_multiselect_application as _build_described_checkbox_application,
    _build_select_application as _build_described_select_application,
    multiselect as _checkbox_values,
    select as _select_value,
)
from tui_toolkit.pickers import _with_back as _with_back_described_choice
from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput
from rich.console import Console
from rich.text import Text
import questionary

# Ensure the nanofaas brand is active for all tests in this module.
from controlplane_tool.ui_setup import setup_ui as _setup_ui
_setup_ui()


def test_described_choice_is_frozen_dataclass() -> None:
    choice = _DescribedChoice(title="foo", value="foo", description="bar")
    assert choice.title == "foo"
    assert choice.description == "bar"


def test_with_back_described_choice_adds_back_entry() -> None:
    choices = [_DescribedChoice("A", "a", "desc a")]
    result = _with_back_described_choice(choices)
    assert result[-1].value == "back"


def test_with_back_described_choice_does_not_duplicate_back() -> None:
    back = _DescribedChoice("back", "back", "Return")
    choices = [back]
    result = _with_back_described_choice(choices)
    # Still only one back entry
    assert sum(1 for c in result if c.value == "back") == 1


def test_back_choice_value_is_back() -> None:
    choice = _back_choice()
    assert choice.value == "back"


def test_select_value_routes_standard_choices_through_described_selector(monkeypatch) -> None:
    import tui_toolkit.pickers as pickers

    captured: dict[str, object] = {}

    class FakePrompt:
        def ask(self):
            return "build"

    def fake_questionary_select(message, choices, default=None, style=None, show_description=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["default"] = default
        return FakePrompt()

    monkeypatch.setattr(pickers.questionary, "select", fake_questionary_select)
    monkeypatch.setattr(pickers.sys, "stdin", type("T", (), {"isatty": lambda self: False})())
    monkeypatch.setattr(pickers.sys, "stdout", type("T", (), {"isatty": lambda self: True})())

    result = _select_value(
        "Action:",
        choices=[
            questionary.Choice(
                "build — compile + unit tests",
                "build",
                description="Compile the workspace and run unit tests.",
            ),
            "test",
        ],
        default="build",
        include_back=True,
    )

    assert result == "build"
    assert captured["message"] == "Action:"
    assert captured["default"] == "build"
    assert any(getattr(choice, "value", choice) == "back" for choice in captured["choices"])


def test_checkbox_values_route_standard_choices_through_described_checkbox(monkeypatch) -> None:
    import tui_toolkit.pickers as pickers

    captured: dict[str, object] = {}

    class FakePrompt:
        def ask(self):
            return ["autoscaler"]

    def fake_questionary_checkbox(message, choices, default=None, style=None):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["default_values"] = default
        return FakePrompt()

    monkeypatch.setattr(pickers.questionary, "checkbox", fake_questionary_checkbox)
    monkeypatch.setattr(pickers.sys, "stdin", type("T", (), {"isatty": lambda self: False})())
    monkeypatch.setattr(pickers.sys, "stdout", type("T", (), {"isatty": lambda self: True})())

    result = _checkbox_values(
        "Select control-plane modules:",
        choices=[
            questionary.Choice(
                "Autoscaler",
                "autoscaler",
                description="Adaptive scaling decisions based on runtime metrics and queue pressure.",
            ),
        ],
        default_values=["autoscaler"],
    )

    assert result == ["autoscaler"]
    assert captured["message"] == "Select control-plane modules:"
    assert captured["default_values"] == ["autoscaler"]
    assert captured["choices"][0].description


def test_described_picker_requires_enter_to_confirm_after_space() -> None:
    choices = [
        _DescribedChoice("one", "one", "first"),
        _DescribedChoice("two", "two", "second"),
        _DescribedChoice("three", "three", "third"),
    ]

    with create_pipe_input() as pipe_input:
        app = _build_described_select_application(
            "Scenario:",
            choices,
            default=None,
            title=None,
            breadcrumb=None,
            footer_hint=None,
            input=pipe_input,
            output=DummyOutput(),
        )
        pipe_input.send_text("j j\r")

        assert app.run() == "three"


def test_described_checkbox_requires_enter_to_confirm_after_space() -> None:
    choices = [
        _DescribedChoice("one", "one", "first"),
        _DescribedChoice("two", "two", "second"),
        _DescribedChoice("three", "three", "third"),
    ]

    with create_pipe_input() as pipe_input:
        app = _build_described_checkbox_application(
            "Modules:",
            choices,
            default_values=None,
            title=None,
            breadcrumb=None,
            footer_hint=None,
            input=pipe_input,
            output=DummyOutput(),
        )
        pipe_input.send_text(" j \r")

        assert app.run() == ["one", "two"]


def test_described_picker_uses_full_screen_with_wider_stable_description_panel() -> None:
    app = _build_described_select_application(
        "Scenario:",
        [_DescribedChoice("one", "one", "first")],
        default=None,
        title=None,
        breadcrumb=None,
        footer_hint=None,
        output=DummyOutput(),
    )

    root = app.layout.container
    body = root.children[1]
    selector = body.children[0]
    description = body.children[1]

    assert app.full_screen is True
    assert body.width.preferred == get_content_width()
    assert selector.width.min == 48
    assert description.width.min == 40
    assert description.width.weight == selector.width.weight


def test_described_checkbox_uses_full_screen_with_wider_stable_description_panel() -> None:
    app = _build_described_checkbox_application(
        "Modules:",
        [_DescribedChoice("one", "one", "first")],
        default_values=None,
        title=None,
        breadcrumb=None,
        footer_hint=None,
        output=DummyOutput(),
    )

    root = app.layout.container
    body = root.children[1]
    selector = body.children[0]
    description = body.children[1]

    assert app.full_screen is True
    assert body.width.preferred == get_content_width()
    assert selector.width.min == 48
    assert description.width.min == 40
    assert description.width.weight == selector.width.weight


def test_render_screen_frame_renders_brand_title_and_footer() -> None:
    from tui_toolkit import render_screen_frame
    from controlplane_tool.ui_setup import NANOFAAS_BRAND
    APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo
    APP_WORDMARK = NANOFAAS_BRAND.wordmark

    frame = render_screen_frame(
        title="Validation",
        body=Text("Body"),
        breadcrumb="Main / Validation",
        footer_hint="Esc back | Ctrl+C exit",
    )

    console = Console(record=True, width=140)
    console.print(frame)
    text = console.export_text()

    assert APP_WORDMARK in text
    assert APP_ASCII_LOGO.splitlines()[0] in text
    assert "Validation" in text
    assert "Main / Validation" in text
    assert "Esc back" in text
    assert "OpenFaaS" not in text


def test_described_picker_header_contains_legacy_ascii_logo() -> None:
    from controlplane_tool.ui_setup import NANOFAAS_BRAND
    APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo

    app = _build_described_select_application(
        "Scenario:",
        [_DescribedChoice("one", "one", "first")],
        default=None,
        title=None,
        breadcrumb=None,
        footer_hint=None,
        output=DummyOutput(),
    )

    root = app.layout.container
    header = root.children[0]
    logo_window = header.children[0]
    logo_fragments = logo_window.content.text()

    assert APP_ASCII_LOGO.splitlines()[0] in logo_fragments[0][1]


def test_described_checkbox_header_contains_legacy_ascii_logo() -> None:
    from controlplane_tool.ui_setup import NANOFAAS_BRAND
    APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo

    app = _build_described_checkbox_application(
        "Modules:",
        [_DescribedChoice("one", "one", "first")],
        default_values=None,
        title=None,
        breadcrumb=None,
        footer_hint=None,
        output=DummyOutput(),
    )

    root = app.layout.container
    header = root.children[0]
    logo_window = header.children[0]
    logo_fragments = logo_window.content.text()

    assert APP_ASCII_LOGO.splitlines()[0] in logo_fragments[0][1]
