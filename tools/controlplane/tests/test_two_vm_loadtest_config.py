from __future__ import annotations


def test_bare_request_target_matches_default_registration() -> None:
    from types import SimpleNamespace

    from controlplane_tool.scenario.scenario_helpers import selected_functions
    from controlplane_tool.scenario.two_vm_loadtest_config import two_vm_target_function

    request = SimpleNamespace(resolved_scenario=None, functions=[])
    target = two_vm_target_function(request)

    # The k6 target must be one of the functions that actually get registered.
    assert target in selected_functions(None)
