from controlplane_tool.tui_widgets import (
    _DescribedChoice,
    _back_choice,
    _build_described_select_application,
    _select_value,
    _with_back_described_choice,
)
from prompt_toolkit.input import create_pipe_input
from prompt_toolkit.output import DummyOutput
from rich.console import Console
from rich.text import Text
import questionary


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
    import controlplane_tool.tui_widgets as tui_widgets

    captured: dict[str, object] = {}

    def fake_select_described_value(message, choices, default=None, include_back=False):  # noqa: ANN001
        captured["message"] = message
        captured["choices"] = choices
        captured["default"] = default
        captured["include_back"] = include_back
        return "build"

    monkeypatch.setattr(tui_widgets, "_select_described_value", fake_select_described_value)

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
    assert captured["include_back"] is False
    assert any(getattr(choice, "value", choice) == "back" for choice in captured["choices"])


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
            input=pipe_input,
            output=DummyOutput(),
        )
        pipe_input.send_text("j j\r")

        assert app.run() == "three"


def test_described_picker_uses_full_screen_with_wider_stable_description_panel() -> None:
    app = _build_described_select_application(
        "Scenario:",
        [_DescribedChoice("one", "one", "first")],
        output=DummyOutput(),
    )

    root = app.layout.container
    body = root.children[1]
    selector = body.children[0]
    description = body.children[1]

    assert app.full_screen is True
    assert body.width.preferred == 140
    assert selector.width.min == 48
    assert description.width.min == 40
    assert description.width.weight == selector.width.weight


def test_render_screen_frame_renders_brand_title_and_footer() -> None:
    from controlplane_tool.tui_chrome import APP_ASCII_LOGO, APP_WORDMARK, render_screen_frame

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
    from controlplane_tool.tui_chrome import APP_ASCII_LOGO

    app = _build_described_select_application(
        "Scenario:",
        [_DescribedChoice("one", "one", "first")],
        output=DummyOutput(),
    )

    root = app.layout.container
    header = root.children[0]
    logo_window = header.children[0]
    logo_fragments = logo_window.content.text()

    assert APP_ASCII_LOGO.splitlines()[0] in logo_fragments[0][1]
