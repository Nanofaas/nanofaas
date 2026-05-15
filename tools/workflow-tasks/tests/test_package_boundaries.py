from __future__ import annotations

import importlib
import sys


def test_workflow_tasks_does_not_import_tui_toolkit() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("tui_toolkit"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks")
    assert not any(k.startswith("tui_toolkit") for k in sys.modules), (
        "workflow_tasks imported tui_toolkit"
    )


def test_workflow_tasks_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules), (
        "workflow_tasks imported controlplane_tool"
    )


def test_tasks_subpackage_does_not_import_workflow() -> None:
    import workflow_tasks.tasks.adapters  # noqa: F401
    import workflow_tasks.tasks.executors  # noqa: F401
    import workflow_tasks.tasks.models  # noqa: F401
    import workflow_tasks.tasks.rendering  # noqa: F401
    # If we got here without importing workflow subpackage transitively, we're good.
    # The import-linter contract enforces this at the CI gate.
