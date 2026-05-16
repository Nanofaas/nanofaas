from __future__ import annotations

from controlplane_tool.scenario.scenario_flows import scenario_task_ids


def test_two_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register, not cli.fn_apply_selected."""
    ids = scenario_task_ids("two-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_azure_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register for azure-vm-loadtest."""
    ids = scenario_task_ids("azure-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_two_vm_loadtest_task_ids_order() -> None:
    """functions.register must appear between cli.build_install_dist and loadgen.ensure_running."""
    ids = scenario_task_ids("two-vm-loadtest")
    build_dist_idx = ids.index("cli.build_install_dist")
    register_idx = ids.index("functions.register")
    loadgen_idx = ids.index("loadgen.ensure_running")
    assert build_dist_idx < register_idx < loadgen_idx
