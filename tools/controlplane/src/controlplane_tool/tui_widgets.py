"""
tui_widgets.py — Reusable questionary / prompt_toolkit widget primitives.

Extracted from tui_app.py. No dependency on runners, flows, or business logic.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from typing import Any, Callable

import questionary
from prompt_toolkit.application import Application
from prompt_toolkit.application.current import get_app
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.layout import Dimension, Layout
from prompt_toolkit.layout.containers import HSplit, VSplit, Window
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.widgets import Frame, RadioList
from questionary import Style

# ── questionary theme consistent with Rich cyan palette ──────────────────────
_STYLE = Style(
    [
        ("qmark", "fg:cyan bold"),
        ("question", "bold"),
        ("answer", "fg:cyan bold"),
        ("pointer", "fg:cyan bold"),
        ("highlighted", "fg:cyan bold"),
        ("selected", "fg:cyan"),
        ("separator", "fg:grey"),
        ("instruction", "fg:grey"),
    ]
)

_BACK_VALUE = "back"


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
        lambda: questionary.select(
            message,
            choices=prompt_choices,
            default=default,
            style=_STYLE,
        ).ask()
    )


class _AcceptingRadioList(RadioList):
    def __init__(
        self,
        values: list[tuple[str, str]],
        *,
        on_accept: Callable[[str], None],
    ) -> None:
        super().__init__(values)
        self._on_accept = on_accept

    def _handle_enter(self) -> None:
        super()._handle_enter()
        self._on_accept(_selected_radiolist_value(self))


def _selected_radiolist_value(radio_list: RadioList) -> str:
    selected_index = getattr(radio_list, "_selected_index", 0)
    return str(radio_list.values[selected_index][0])


def _selected_described_choice(
    radio_list: RadioList,
    choices: list[_DescribedChoice],
) -> _DescribedChoice:
    selected_value = _selected_radiolist_value(radio_list)
    return next(choice for choice in choices if choice.value == selected_value)


def _select_described_value(
    message: str,
    choices: list[_DescribedChoice],
    *,
    include_back: bool = False,
) -> str | None:
    """Show an interactive selector with a live description panel on the right."""
    if include_back:
        choices = _with_back_described_choice(choices)
    if not choices:
        raise ValueError("choices must not be empty")

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return questionary.select(
            message,
            choices=[questionary.Choice(choice.title, choice.value) for choice in choices],
            style=_STYLE,
        ).ask()

    radio_list = _AcceptingRadioList(
        [(choice.value, choice.title) for choice in choices],
        on_accept=lambda value: get_app().exit(result=value),
    )

    def _description_fragments() -> list[tuple[str, str]]:
        selected = _selected_described_choice(radio_list, choices)
        return [
            ("class:question", selected.description),
            ("", "\n\n"),
            ("class:instruction", "Enter to confirm • Esc to cancel"),
        ]

    body = VSplit(
        [
            Frame(radio_list, title=message),
            Frame(
                Window(
                    FormattedTextControl(_description_fragments),
                    wrap_lines=True,
                ),
                title="Description",
            ),
        ],
        padding=1,
        width=Dimension(preferred=100),
    )
    root = HSplit(
        [
            Window(
                height=1,
                content=FormattedTextControl(
                    [("class:instruction", "Use arrow keys to move through the list.")]
                ),
            ),
            body,
        ]
    )

    bindings = KeyBindings()

    @bindings.add("escape")
    @bindings.add("c-c")
    def _cancel(event) -> None:  # noqa: ANN001
        event.app.exit(result=None)

    app = Application(
        layout=Layout(root, focused_element=radio_list),
        key_bindings=bindings,
        full_screen=True,
        style=_STYLE,
        mouse_support=False,
    )
    return app.run()
