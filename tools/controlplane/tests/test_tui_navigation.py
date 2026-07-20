from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest

from controlplane_tool.tui.app import NanofaasTUI


EXPECTED_SCENARIOS = {
    ("validation", "container"): "validate-container.yaml",
    ("validation", "kubernetes"): "validate-k8s.yaml",
    ("validation", "offload"): "validate-offload.yaml",
    ("cli", "validate"): "cli.yaml",
    ("loadtest", "run"): "loadtest.yaml",
    ("loadtest", "offload"): "offload-loadtest.yaml",
}


class ScriptedChooser:
    def __init__(self, answers: Iterator[str | type[KeyboardInterrupt]]) -> None:
        self._answers = answers
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def __call__(self, message: str, **kwargs: Any) -> str:
        self.calls.append((message, kwargs))
        answer = next(self._answers)
        if isinstance(answer, str):
            return answer
        raise KeyboardInterrupt


def test_main_menu_contains_only_supported_product_sections() -> None:
    assert [choice.value for choice in NanofaasTUI.MAIN_MENU] == [
        "validation",
        "cli",
        "loadtest",
        "tools",
        "exit",
    ]


def test_main_menu_uses_the_exact_product_copy() -> None:
    assert [
        (choice.title, choice.value, choice.description)
        for choice in NanofaasTUI.MAIN_MENU
    ] == [
        (
            "Validation",
            "validation",
            "Validate container and Kubernetes execution paths.",
        ),
        (
            "CLI",
            "cli",
            "Validate the nanofaas CLI against a selected environment.",
        ),
        (
            "Load Testing",
            "loadtest",
            "Run the current k6 and autoscaling workflow.",
        ),
        (
            "Tools",
            "tools",
            "Inspect scenarios and check local prerequisites.",
        ),
        ("Exit", "exit", "Leave the interactive control-plane tool."),
    ]


@pytest.mark.parametrize(("route", "scenario"), EXPECTED_SCENARIOS.items())
def test_navigation_dispatches_only_stable_scenario_routes(
    route: tuple[str, str], scenario: str
) -> None:
    section, action = route
    chooser = ScriptedChooser(iter([section, action, "back", "exit"]))
    dispatched: list[str] = []

    NanofaasTUI(choose=chooser, dispatch_scenario=dispatched.append).run()

    assert dispatched == [scenario]
    submenu_call = chooser.calls[1]
    assert submenu_call[1]["include_back"] is True


@pytest.mark.parametrize("section", ["validation", "cli", "loadtest", "tools"])
def test_back_from_each_submenu_returns_to_main(section: str) -> None:
    chooser = ScriptedChooser(iter([section, "back", "exit"]))

    NanofaasTUI(choose=chooser).run()

    assert [message for message, _ in chooser.calls] == [
        "What would you like to do?",
        NanofaasTUI.SECTION_TITLES[section],
        "What would you like to do?",
    ]


def test_keyboard_interrupt_at_main_exits_navigation_cleanly() -> None:
    chooser = ScriptedChooser(iter([KeyboardInterrupt]))

    NanofaasTUI(choose=chooser).run()

    assert [message for message, _ in chooser.calls] == ["What would you like to do?"]


@pytest.mark.parametrize("section", ["validation", "cli", "loadtest", "tools"])
def test_keyboard_interrupt_in_each_submenu_exits_navigation(section: str) -> None:
    chooser = ScriptedChooser(iter([section, KeyboardInterrupt]))

    NanofaasTUI(choose=chooser).run()

    assert [message for message, _ in chooser.calls] == [
        "What would you like to do?",
        NanofaasTUI.SECTION_TITLES[section],
    ]


@pytest.mark.parametrize("section", ["validation", "cli", "loadtest", "tools"])
def test_each_submenu_opts_escape_into_back_navigation(section: str) -> None:
    chooser = ScriptedChooser(iter([section, "back", "exit"]))

    NanofaasTUI(choose=chooser).run()

    assert chooser.calls[1][1]["escape_value"] == "back"


def test_navigation_does_not_restore_old_aliases() -> None:
    old_aliases = {"building", "environment", "catalog", "vm", "registry", "e2e"}
    values = {
        choice.value
        for menu in NanofaasTUI.SECTION_MENUS.values()
        for choice in menu
    } | {choice.value for choice in NanofaasTUI.MAIN_MENU}

    assert NanofaasTUI.SCENARIO_FILES == EXPECTED_SCENARIOS
    assert values.isdisjoint(old_aliases)
