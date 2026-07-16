from __future__ import annotations

from collections.abc import Callable

from controlplane_tool.tui import NanofaasTUI


def test_tui_exits_from_the_main_menu() -> None:
    calls: list[str] = []

    def choose(message: str, **kwargs: object) -> str:
        calls.append(message)
        return "exit"

    NanofaasTUI(choose=choose).run()

    assert calls == ["What would you like to do?"]


def test_tui_dispatches_a_stable_scenario_filename() -> None:
    answers: list[str] = ["cli", "validate", "back", "exit"]
    dispatched: list[str] = []

    def choose(message: str, **kwargs: object) -> str:
        return answers.pop(0)

    dispatch: Callable[[str], None] = dispatched.append
    NanofaasTUI(choose=choose, dispatch_scenario=dispatch).run()

    assert dispatched == ["cli.yaml"]
