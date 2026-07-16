"""Menu navigation for the interactive control-plane tool."""

from __future__ import annotations

from collections.abc import Callable

from tui_toolkit import Choice, select

MAIN_MENU = [
    Choice(
        "Validation",
        "validation",
        "Validate container and Kubernetes execution paths.",
    ),
    Choice(
        "CLI",
        "cli",
        "Validate the nanofaas CLI against a selected environment.",
    ),
    Choice(
        "Load Testing",
        "loadtest",
        "Run the current k6 and autoscaling workflow.",
    ),
    Choice(
        "Tools",
        "tools",
        "Inspect scenarios and check local prerequisites.",
    ),
    Choice("Exit", "exit", "Leave the interactive control-plane tool."),
]

VALIDATION_MENU = [
    Choice("Container", "container", "Validate the local container execution path."),
    Choice("Kubernetes", "kubernetes", "Validate the Kubernetes execution path."),
]
CLI_MENU = [
    Choice("Validate CLI", "validate", "Validate the nanofaas CLI workflow."),
]
LOADTEST_MENU = [
    Choice("Run load test", "run", "Run the current k6 and autoscaling workflow."),
]
TOOLS_MENU = [
    Choice("Inspect scenario", "inspect", "Inspect a supported scenario."),
    Choice("Doctor", "doctor", "Check required local executables."),
]

_SECTION_MENUS = {
    "validation": VALIDATION_MENU,
    "cli": CLI_MENU,
    "loadtest": LOADTEST_MENU,
    "tools": TOOLS_MENU,
}
_SECTION_TITLES = {
    "validation": "Validation",
    "cli": "CLI",
    "loadtest": "Load Testing",
    "tools": "Tools",
}
_SCENARIO_FILES = {
    ("validation", "container"): "validate-container.yaml",
    ("validation", "kubernetes"): "validate-k8s.yaml",
    ("cli", "validate"): "cli.yaml",
    ("loadtest", "run"): "loadtest.yaml",
}


def _defer_scenario(_scenario_file: str) -> None:
    """Task 6 supplies workflow execution at this boundary."""


class NanofaasTUI:
    """Navigate the stable product menu and dispatch selected scenarios."""

    MAIN_MENU = MAIN_MENU
    SECTION_MENUS = _SECTION_MENUS
    SECTION_TITLES = _SECTION_TITLES
    SCENARIO_FILES = _SCENARIO_FILES

    def __init__(
        self,
        choose: Callable[..., str] = select,
        dispatch_scenario: Callable[[str], None] = _defer_scenario,
    ) -> None:
        self._choose = choose
        self._dispatch_scenario = dispatch_scenario

    def run(self) -> None:
        while True:
            try:
                section = self._choose(
                    "What would you like to do?",
                    choices=self.MAIN_MENU,
                    title="Main",
                    breadcrumb="Main",
                )
            except KeyboardInterrupt:
                return
            if section == "exit":
                return
            try:
                self._dispatch_section(section)
            except KeyboardInterrupt:
                continue

    def _dispatch_section(self, section: str) -> None:
        menu = self.SECTION_MENUS.get(section)
        if menu is None:
            raise ValueError(f"Unsupported TUI section: {section}")

        title = self.SECTION_TITLES[section]
        while True:
            action = self._choose(
                title,
                choices=menu,
                include_back=True,
                title=title,
                breadcrumb=f"Main / {title}",
            )
            if action == "back":
                return
            scenario_file = self.SCENARIO_FILES.get((section, action))
            if scenario_file is not None:
                self._dispatch_scenario(scenario_file)
