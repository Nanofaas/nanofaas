from __future__ import annotations

from controlplane_tool.tui import app as tui_app


def _choice_values(choices) -> list[str]:
    return [choice.value for choice in choices]


def test_platform_validation_choices_include_one_vm_helm_loadtest() -> None:
    values = _choice_values(tui_app._PLATFORM_VALIDATION_CHOICES)
    assert "loadtest-one-vm" in values
    # Keep it next to its siblings so the menu reads stack -> one-vm -> two-vm.
    assert values.index("loadtest-helm-legacy") < values.index("loadtest-one-vm") < values.index("loadtest-two-vm")


def test_one_vm_helm_loadtest_routes_through_vm_e2e_dispatch() -> None:
    import inspect

    source = inspect.getsource(tui_app)
    dispatch_line = next(
        line for line in source.splitlines()
        if "scenario_choice in (" in line
    )
    assert "loadtest-one-vm" in dispatch_line
    vm_e2e_source = inspect.getsource(tui_app.NanofaasTUI._run_vm_e2e_scenario)
    membership_line = next(
        line for line in vm_e2e_source.splitlines()
        if "scenario in {" in line and "loadtest-helm-legacy" in line
    )
    assert "loadtest-one-vm" in membership_line
