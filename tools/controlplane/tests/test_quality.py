from __future__ import annotations

import sys

from controlplane_tool.devtools import quality


def test_quality_gate_includes_console_entrypoint_import_smoke() -> None:
    entrypoint_check = dict(quality.CHECKS)["entrypoint-imports"]

    assert entrypoint_check[0] == sys.executable
    assert "controlplane_tool.app.main" in entrypoint_check[-1]
    assert "controlplane_tool.cli.commands" in entrypoint_check[-1]
    assert "controlplane_tool.building.gradle_executor" in entrypoint_check[-1]
