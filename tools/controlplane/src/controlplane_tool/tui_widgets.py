"""
tui_widgets.py — Reusable questionary / prompt_toolkit widget primitives.

Extracted from tui_app.py. No dependency on runners, flows, or business logic.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from typing import Any

import questionary
from prompt_toolkit.application import Application
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.keys import Keys
from prompt_toolkit.layout import Dimension, Layout
from prompt_toolkit.layout.containers import HSplit, VSplit, Window
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.widgets import Frame
from questionary import Style
from questionary.prompts.common import Choice, InquirerControl

from controlplane_tool.tui_chrome import APP_BRAND

# ── questionary theme consistent with Rich cyan palette ──────────────────────
_STYLE = Style(
    [
        ("brand", "fg:cyan bold"),
        ("breadcrumb", "fg:grey"),
        ("footer", "fg:grey"),
        ("qmark", "fg:cyan bold"),
        ("question", "bold"),
        ("answer", "fg:cyan bold"),
        ("pointer", "fg:cyan bold"),
        ("highlighted", "fg:cyan bold"),
        ("selected", "fg:cyan"),
        ("text", ""),
        ("disabled", "fg:grey"),
        ("separator", "fg:grey"),
        ("instruction", "fg:grey"),
    ]
)

_BACK_VALUE = "back"
_PICKER_BODY_PREFERRED_WIDTH = 140
_PICKER_SELECTOR_MIN_WIDTH = 48
_PICKER_DESCRIPTION_MIN_WIDTH = 40
_PICKER_PANEL_WEIGHT = 1


def _ask(prompt_fn):
    """Execute a questionary prompt; exit cleanly on Ctrl-C / None."""
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


@dataclass(frozen=True)
class _DescribedChoice:
    title: str
    value: str
    description: str


def _back_choice() -> questionary.Choice:
    return questionary.Choice("back — return to previous menu", _BACK_VALUE)


def _with_back_choice(choices: list[Any]) -> list[Any]:
    if any(getattr(choice, "value", choice) == _BACK_VALUE for choice in choices):
        return choices
    return [*choices, questionary.Separator(), _back_choice()]


def _with_back_described_choice(choices: list[_DescribedChoice]) -> list[_DescribedChoice]:
    if any(choice.value == _BACK_VALUE for choice in choices):
        return choices
    return [
        *choices,
        _DescribedChoice(
            "back — return to previous menu",
            _BACK_VALUE,
            "Return to the previous menu without starting a workflow.",
        ),
    ]


def _select_value(
    message: str,
    *,
    choices: list[Any],
    default: str | None = None,
    include_back: bool = False,
) -> Any:
    prompt_choices = _with_back_choice(list(choices)) if include_back else choices
    return _ask(
        lambda: _select_described_value(
            message,
            prompt_choices,
            default=default,
        )
    )


def _normalize_choice(choice: Any) -> Any:
    if isinstance(choice, _DescribedChoice):
        return questionary.Choice(choice.title, choice.value, description=choice.description)
    if isinstance(choice, questionary.Separator):
        return choice
    if isinstance(choice, Choice):
        return choice
    return Choice.build(choice)


def _normalize_choices(choices: list[Any]) -> list[Any]:
    return [_normalize_choice(choice) for choice in choices]


def _select_prompt_fragments(message: str) -> list[tuple[str, str]]:
    return [
        ("class:qmark", "?"),
        ("class:question", f" {message} "),
        ("class:instruction", "(Use arrow keys, Enter to confirm)"),
    ]


def _screen_title(message: str) -> str:
    return message.rstrip(" ?:") or "Menu"


def _screen_breadcrumb(screen_title: str) -> str:
    return f"Main / {screen_title}"


def _screen_footer() -> str:
    return "Enter confirm | Esc back | Ctrl+C exit"


def _description_fragments(control: InquirerControl) -> list[tuple[str, str]]:
    current = control.get_pointed_at()
    description = getattr(current, "description", None) or ""
    return [("class:text", description)]


def _build_described_select_application(
    message: str,
    choices: list[Any],
    *,
    default: str | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
    input=None,  # noqa: ANN001
    output=None,  # noqa: ANN001
) -> Application:
    normalized_choices = _normalize_choices(choices)
    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title)
    screen_footer = footer_hint or _screen_footer()
    control = InquirerControl(
        normalized_choices,
        default=default,
        initial_choice=default,
        use_indicator=False,
        show_selected=False,
        show_description=False,
        use_arrow_keys=True,
    )

    def _move_cursor_down() -> None:
        control.select_next()
        while not control.is_selection_valid():
            control.select_next()

    def _move_cursor_up() -> None:
        control.select_previous()
        while not control.is_selection_valid():
            control.select_previous()

    bindings = KeyBindings()

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event) -> None:  # noqa: ANN001
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    def _down(event) -> None:  # noqa: ANN001
        _move_cursor_down()

    @bindings.add(Keys.Up, eager=True)
    def _up(event) -> None:  # noqa: ANN001
        _move_cursor_up()

    @bindings.add("j", eager=True)
    def _vi_down(event) -> None:  # noqa: ANN001
        _move_cursor_down()

    @bindings.add("k", eager=True)
    def _vi_up(event) -> None:  # noqa: ANN001
        _move_cursor_up()

    @bindings.add(Keys.ControlN, eager=True)
    def _emacs_down(event) -> None:  # noqa: ANN001
        _move_cursor_down()

    @bindings.add(Keys.ControlP, eager=True)
    def _emacs_up(event) -> None:  # noqa: ANN001
        _move_cursor_up()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event) -> None:  # noqa: ANN001
        event.app.exit(result=control.get_pointed_at().value)

    @bindings.add(Keys.Any)
    def _ignore_other_keys(event) -> None:  # noqa: ANN001
        """Ignore all remaining keys, including space."""

    prompt = Window(
        height=1,
        content=FormattedTextControl(lambda: _select_prompt_fragments(message)),
    )
    header_block = HSplit(
        [
            Window(
                height=1,
                content=FormattedTextControl(
                    lambda: [
                        ("class:brand", APP_BRAND),
                        ("class:text", f"  {screen_title}"),
                    ]
                ),
            ),
            Window(
                height=1,
                content=FormattedTextControl(
                    lambda: [("class:breadcrumb", screen_breadcrumb)]
                ),
            ),
            prompt,
        ]
    )
    selector = Window(
        content=control,
        dont_extend_height=True,
        width=Dimension(weight=_PICKER_PANEL_WEIGHT, min=_PICKER_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(lambda: _description_fragments(control)),
            wrap_lines=True,
            dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PICKER_PANEL_WEIGHT, min=_PICKER_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2,
        width=Dimension(preferred=_PICKER_BODY_PREFERRED_WIDTH),
    )

    return Application(
        layout=Layout(
            HSplit(
                [
                    header_block,
                    body,
                    Window(
                        height=1,
                        content=FormattedTextControl(
                            lambda: [("class:footer", screen_footer)]
                        ),
                    ),
                ]
            ),
            focused_element=selector,
        ),
        key_bindings=bindings,
        full_screen=True,
        style=_STYLE,
        mouse_support=False,
        input=input,
        output=output,
    )


def _select_described_value(
    message: str,
    choices: list[Any],
    *,
    default: str | None = None,
    include_back: bool = False,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> str | None:
    """Show an interactive selector with a live description panel on the right."""
    if include_back:
        choices = _with_back_described_choice(choices)
    if not choices:
        raise ValueError("choices must not be empty")

    normalized_choices = _normalize_choices(choices)

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return questionary.select(
            message,
            choices=normalized_choices,
            default=default,
            style=_STYLE,
            show_description=True,
        ).ask()

    app = _build_described_select_application(
        message,
        normalized_choices,
        default=default,
        title=title,
        breadcrumb=breadcrumb,
        footer_hint=footer_hint,
    )
    return app.run()
