"""Pickers — select() and multiselect() with a description side panel.

Ported from controlplane_tool.tui_widgets. The prompt_toolkit-driven
full-screen picker is preserved; the adapter layer reads theme + brand
from the active UIContext.

Non-TTY environments fall back to plain questionary prompts.
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
from prompt_toolkit.widgets import CheckboxList, Frame
from questionary.prompts.common import Choice as _QChoice, InquirerControl

from tui_toolkit.console import get_content_width
from tui_toolkit.context import get_ui
from tui_toolkit.theme import to_questionary_style


@dataclass(frozen=True, slots=True)
class Choice:
    title: str
    value: str
    description: str = ""


Separator = questionary.Separator

_BACK_VALUE = "back"
_SELECTOR_MIN_WIDTH = 48
_DESCRIPTION_MIN_WIDTH = 40
_PANEL_WEIGHT = 1


def _normalize_choice(choice: Any) -> Any:
    if isinstance(choice, Choice):
        return questionary.Choice(choice.title, choice.value, description=choice.description)
    if isinstance(choice, questionary.Separator):
        return choice
    if isinstance(choice, _QChoice):
        return choice
    return _QChoice.build(choice)


def _normalize_choices(choices: list[Any]) -> list[Any]:
    return [_normalize_choice(c) for c in choices]


def _back_choice() -> questionary.Choice:
    return questionary.Choice("back — return to previous menu", _BACK_VALUE)


def _with_back(choices: list[Any]) -> list[Any]:
    if any(getattr(c, "value", c) == _BACK_VALUE for c in choices):
        return choices
    return [*choices, questionary.Separator(), _back_choice()]


def _screen_title(message: str) -> str:
    return message.rstrip(" ?:") or "Menu"


def _screen_breadcrumb(screen_title: str, default_breadcrumb: str) -> str:
    return f"{default_breadcrumb} / {screen_title}"


def _select_prompt_fragments(message: str) -> list[tuple[str, str]]:
    return [
        ("class:qmark", "?"),
        ("class:question", f" {message} "),
        ("class:instruction", "(Use arrow keys, Enter to confirm)"),
    ]


def _description_fragments(control: InquirerControl) -> list[tuple[str, str]]:
    current = control.get_pointed_at()
    return [("class:text", getattr(current, "description", "") or "")]


def _checkbox_description_fragments(checkbox_list: CheckboxList, choices: list[Any]) -> list[tuple[str, str]]:
    selected_index = getattr(checkbox_list, "_selected_index", 0)
    current = choices[selected_index]
    description = getattr(current, "description", "") or ""
    selected_count = len(getattr(checkbox_list, "current_values", []))
    return [
        ("class:text", description),
        ("", "\n\n"),
        ("class:instruction", f"Space toggle | Enter confirm | Selected: {selected_count}"),
    ]


def _build_header_block(*, message: str, screen_title: str, screen_breadcrumb: str) -> HSplit:
    brand = get_ui().brand
    ascii_logo = brand.ascii_logo
    wordmark = brand.wordmark
    logo_lines = ascii_logo.count("\n") + 1 if ascii_logo else 0

    children: list[Window] = []
    if ascii_logo:
        children.append(Window(
            height=logo_lines,
            content=FormattedTextControl(lambda: [("class:brand", ascii_logo)]),
        ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: [
            ("class:brand", wordmark),
            ("class:text", f"  {screen_title}" if wordmark else screen_title),
        ]),
    ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: [("class:breadcrumb", screen_breadcrumb)]),
    ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: _select_prompt_fragments(message)),
    ))
    return HSplit(children)


def _build_select_application(
    message: str,
    choices: list[Any],
    *,
    default: str | None,
    title: str | None,
    breadcrumb: str | None,
    footer_hint: str | None,
    input=None,
    output=None,
) -> Application:
    brand = get_ui().brand
    style = to_questionary_style(get_ui().theme)
    normalized = _normalize_choices(choices)
    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title, brand.default_breadcrumb)
    screen_footer = footer_hint or brand.default_footer_hint

    control = InquirerControl(
        normalized, default=default, initial_choice=default,
        use_indicator=False, show_selected=False, show_description=False,
        use_arrow_keys=True,
    )

    def _down() -> None:
        control.select_next()
        while not control.is_selection_valid():
            control.select_next()

    def _up() -> None:
        control.select_previous()
        while not control.is_selection_valid():
            control.select_previous()

    bindings = KeyBindings()

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event):
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    @bindings.add("j", eager=True)
    @bindings.add(Keys.ControlN, eager=True)
    def _go_down(event):
        _down()

    @bindings.add(Keys.Up, eager=True)
    @bindings.add("k", eager=True)
    @bindings.add(Keys.ControlP, eager=True)
    def _go_up(event):
        _up()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event):
        event.app.exit(result=control.get_pointed_at().value)

    @bindings.add(Keys.Any)
    def _ignore(event):
        pass

    header_block = _build_header_block(
        message=message, screen_title=screen_title, screen_breadcrumb=screen_breadcrumb,
    )
    selector = Window(
        content=control, dont_extend_height=True,
        width=Dimension(weight=_PANEL_WEIGHT, min=_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(lambda: _description_fragments(control)),
            wrap_lines=True, dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PANEL_WEIGHT, min=_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2, width=Dimension(preferred=get_content_width()),
    )

    return Application(
        layout=Layout(
            HSplit([
                header_block, body,
                Window(height=1, content=FormattedTextControl(lambda: [("class:footer", screen_footer)])),
            ]),
            focused_element=selector,
        ),
        key_bindings=bindings,
        full_screen=True,
        style=style,
        mouse_support=False,
        input=input,
        output=output,
    )


def _build_multiselect_application(
    message: str,
    choices: list[Any],
    *,
    default_values: list[str] | None,
    title: str | None,
    breadcrumb: str | None,
    footer_hint: str | None,
    input=None,
    output=None,
) -> Application:
    brand = get_ui().brand
    style = to_questionary_style(get_ui().theme)
    normalized = _normalize_choices(choices)
    if not normalized:
        raise ValueError("choices must not be empty")

    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title, brand.default_breadcrumb)
    screen_footer = footer_hint or "Space toggle | Enter confirm | Esc cancel | Ctrl+C exit"

    checkbox_list = CheckboxList(
        values=[(c.value, c.title) for c in normalized],
        default_values=default_values,
    )
    bindings = KeyBindings()

    def _move(delta: int) -> None:
        total = len(checkbox_list.values)
        checkbox_list._selected_index = (checkbox_list._selected_index + delta) % total

    def _toggle() -> None:
        cv = checkbox_list.values[checkbox_list._selected_index][0]
        if cv in checkbox_list.current_values:
            checkbox_list.current_values = [v for v in checkbox_list.current_values if v != cv]
            return
        checkbox_list.current_values = [*checkbox_list.current_values, cv]

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event):
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    @bindings.add("j", eager=True)
    @bindings.add(Keys.ControlN, eager=True)
    def _down(event):
        _move(1)

    @bindings.add(Keys.Up, eager=True)
    @bindings.add("k", eager=True)
    @bindings.add(Keys.ControlP, eager=True)
    def _up(event):
        _move(-1)

    @bindings.add(" ", eager=True)
    def _toggle_key(event):
        _toggle()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event):
        ordered = [v for v, _ in checkbox_list.values if v in checkbox_list.current_values]
        event.app.exit(result=ordered)

    header_block = _build_header_block(
        message=message, screen_title=screen_title, screen_breadcrumb=screen_breadcrumb,
    )
    selector = Frame(
        checkbox_list, title="Select",
        width=Dimension(weight=_PANEL_WEIGHT, min=_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(lambda: _checkbox_description_fragments(checkbox_list, normalized)),
            wrap_lines=True, dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PANEL_WEIGHT, min=_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2, width=Dimension(preferred=get_content_width()),
    )

    return Application(
        layout=Layout(
            HSplit([
                header_block, body,
                Window(height=1, content=FormattedTextControl(lambda: [("class:footer", screen_footer)])),
            ]),
            focused_element=checkbox_list,
        ),
        key_bindings=bindings,
        full_screen=True,
        style=style,
        mouse_support=False,
        input=input,
        output=output,
    )


def _ask(prompt_fn):
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


def select(
    message: str,
    *,
    choices: list[Any],
    default: str | None = None,
    include_back: bool = False,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> str:
    """Interactive single-select picker with a description side panel.

    Falls back to questionary.select() when stdin or stdout is not a TTY.
    Pressing Ctrl-C / Esc raises KeyboardInterrupt.
    """
    if include_back:
        choices = _with_back(list(choices))
    if not choices:
        raise ValueError("choices must not be empty")

    style = to_questionary_style(get_ui().theme)

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return _ask(lambda: questionary.select(
            message, choices=_normalize_choices(choices), default=default,
            style=style, show_description=True,
        ).ask())

    app = _build_select_application(
        message, choices,
        default=default, title=title, breadcrumb=breadcrumb, footer_hint=footer_hint,
    )
    result = app.run()
    if result is None:
        raise KeyboardInterrupt
    return result


def multiselect(
    message: str,
    *,
    choices: list[Any],
    default_values: list[str] | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> list[str]:
    """Interactive multi-select picker with a description side panel."""
    if not choices:
        raise ValueError("choices must not be empty")

    style = to_questionary_style(get_ui().theme)

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return _ask(lambda: questionary.checkbox(
            message, choices=_normalize_choices(choices),
            default=default_values, style=style,
        ).ask())

    app = _build_multiselect_application(
        message, choices,
        default_values=default_values, title=title, breadcrumb=breadcrumb, footer_hint=footer_hint,
    )
    result = app.run()
    if result is None:
        raise KeyboardInterrupt
    return result
