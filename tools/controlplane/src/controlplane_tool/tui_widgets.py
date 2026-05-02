"""SHIM — moved to tui_toolkit.pickers.

This file will be deleted in PR2. New code should import select/multiselect
from tui_toolkit directly.

Widget primitives used by tui_app and tui.py.  The full-screen prompt_toolkit
picker implementation is kept here for backward compatibility; tui-toolkit
provides an equivalent in tui_toolkit.pickers.
"""
from __future__ import annotations

import sys
from typing import Any

import questionary
from prompt_toolkit.application import Application
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.keys import Keys
from prompt_toolkit.layout import Dimension, Layout
from prompt_toolkit.layout.containers import HSplit, VSplit, Window
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.widgets import CheckboxList, Frame
from questionary.prompts.common import Choice, InquirerControl

from tui_toolkit import Choice as _DescribedChoice  # legacy alias: same shape as original dataclass
from tui_toolkit.theme import DEFAULT_THEME, to_questionary_style

from controlplane_tool.console import get_content_width as _get_content_width
from controlplane_tool.tui_chrome import APP_ASCII_LOGO, APP_WORDMARK

# ── questionary theme consistent with Rich cyan palette ──────────────────────
_STYLE = to_questionary_style(DEFAULT_THEME)

_BACK_VALUE = "back"
_PICKER_SELECTOR_MIN_WIDTH = 48
_PICKER_DESCRIPTION_MIN_WIDTH = 40
_PICKER_PANEL_WEIGHT = 1
_PICKER_BRAND_HEIGHT = APP_ASCII_LOGO.count("\n") + 1


def _ask(prompt_fn):
    """Execute a questionary prompt; exit cleanly on Ctrl-C / None."""
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


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


def _checkbox_values(
    message: str,
    *,
    choices: list[Any],
    default_values: list[str] | None = None,
) -> list[Any]:
    return _ask(
        lambda: _select_described_checkbox_values(
            message,
            choices,
            default_values=default_values,
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


def _checkbox_description_fragments(
    checkbox_list: CheckboxList,
    choices: list[Any],
) -> list[tuple[str, str]]:
    selected_index = getattr(checkbox_list, "_selected_index", 0)
    current = choices[selected_index]
    description = getattr(current, "description", None) or ""
    selected_count = len(getattr(checkbox_list, "current_values", []))
    return [
        ("class:text", description),
        ("", "\n\n"),
        ("class:instruction", f"Space toggle | Enter confirm | Selected: {selected_count}"),
    ]


def _build_header_block(
    *,
    message: str,
    screen_title: str,
    screen_breadcrumb: str,
) -> HSplit:
    prompt = Window(
        height=1,
        content=FormattedTextControl(lambda: _select_prompt_fragments(message)),
    )
    return HSplit(
        [
            Window(
                height=_PICKER_BRAND_HEIGHT,
                content=FormattedTextControl(lambda: [("class:brand", APP_ASCII_LOGO)]),
            ),
            Window(
                height=1,
                content=FormattedTextControl(
                    lambda: [
                        ("class:brand", APP_WORDMARK),
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

    header_block = _build_header_block(
        message=message,
        screen_title=screen_title,
        screen_breadcrumb=screen_breadcrumb,
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
        width=Dimension(preferred=_get_content_width()),
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


def _build_described_checkbox_application(
    message: str,
    choices: list[Any],
    *,
    default_values: list[str] | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
    input=None,  # noqa: ANN001
    output=None,  # noqa: ANN001
) -> Application:
    normalized_choices = _normalize_choices(choices)
    if not normalized_choices:
        raise ValueError("choices must not be empty")

    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title)
    screen_footer = footer_hint or "Space toggle | Enter confirm | Esc cancel | Ctrl+C exit"
    checkbox_list = CheckboxList(
        values=[(choice.value, choice.title) for choice in normalized_choices],
        default_values=default_values,
    )
    bindings = KeyBindings()

    def _move_cursor(delta: int) -> None:
        total_choices = len(checkbox_list.values)
        checkbox_list._selected_index = (checkbox_list._selected_index + delta) % total_choices

    def _toggle_current() -> None:
        current_value = checkbox_list.values[checkbox_list._selected_index][0]
        if current_value in checkbox_list.current_values:
            checkbox_list.current_values = [value for value in checkbox_list.current_values if value != current_value]
            return
        checkbox_list.current_values = [*checkbox_list.current_values, current_value]

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event) -> None:  # noqa: ANN001
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    @bindings.add("j", eager=True)
    @bindings.add(Keys.ControlN, eager=True)
    def _down(event) -> None:  # noqa: ANN001
        _move_cursor(1)

    @bindings.add(Keys.Up, eager=True)
    @bindings.add("k", eager=True)
    @bindings.add(Keys.ControlP, eager=True)
    def _up(event) -> None:  # noqa: ANN001
        _move_cursor(-1)

    @bindings.add(" ", eager=True)
    def _toggle(event) -> None:  # noqa: ANN001
        _toggle_current()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event) -> None:  # noqa: ANN001
        ordered_values = [
            value for value, _ in checkbox_list.values if value in checkbox_list.current_values
        ]
        event.app.exit(result=ordered_values)

    header_block = _build_header_block(
        message=message,
        screen_title=screen_title,
        screen_breadcrumb=screen_breadcrumb,
    )
    selector = Frame(
        checkbox_list,
        title="Select",
        width=Dimension(weight=_PICKER_PANEL_WEIGHT, min=_PICKER_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(
                lambda: _checkbox_description_fragments(checkbox_list, normalized_choices)
            ),
            wrap_lines=True,
            dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PICKER_PANEL_WEIGHT, min=_PICKER_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2,
        width=Dimension(preferred=_get_content_width()),
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
            focused_element=checkbox_list,
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


def _select_described_checkbox_values(
    message: str,
    choices: list[Any],
    *,
    default_values: list[str] | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> list[Any] | None:
    """Show an interactive multi-select with a live description panel on the right."""
    normalized_choices = _normalize_choices(choices)
    if not normalized_choices:
        raise ValueError("choices must not be empty")

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return questionary.checkbox(
            message,
            choices=normalized_choices,
            default=default_values,
            style=_STYLE,
        ).ask()

    app = _build_described_checkbox_application(
        message,
        normalized_choices,
        default_values=default_values,
        title=title,
        breadcrumb=breadcrumb,
        footer_hint=footer_hint,
    )
    return app.run()


__all__ = [
    "_select_value",
    "_checkbox_values",
    "_select_described_value",
    "_select_described_checkbox_values",
    "_DescribedChoice",
    "_STYLE",
    "_BACK_VALUE",
    "_ask",
    "_back_choice",
    "_with_back_choice",
    "_with_back_described_choice",
    "_build_described_select_application",
    "_build_described_checkbox_application",
]
