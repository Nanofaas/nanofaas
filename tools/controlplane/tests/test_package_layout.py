from __future__ import annotations

from pathlib import Path


def test_controlplane_tool_has_no_root_runtime_modules() -> None:
    package_root = Path(__file__).resolve().parents[1] / "src" / "controlplane_tool"

    root_modules = sorted(path.name for path in package_root.glob("*.py"))

    assert root_modules == ["__init__.py"]


def test_remaining_root_modules_are_importable_from_semantic_packages() -> None:
    from controlplane_tool.cli_validation.cli_vm_runner import CliVmRunner  # noqa: F401
    from controlplane_tool.scenario.scenario_flows import build_scenario_flow  # noqa: F401
