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


def test_vm_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_loadtest_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_vm_multipass_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.multipass")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_vm_azure_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.azure")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_shell_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.shell")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.operations")
    importlib.import_module("workflow_tasks.components.models")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_infra_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.infra.ansible")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_vm_orchestrator_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.orchestrator")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_registry_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.registry")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_context_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.context")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_namespace_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.namespace")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_images_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.images")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_loadtest_remote_k6_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest.remote_k6")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_loadtest_two_vm_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest.two_vm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_helm_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.helm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_platform_commands_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.platform_commands")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_remote_script_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.remote_script")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_verification_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.verification")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_cleanup_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.cleanup")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_bootstrap_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.bootstrap")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_function_tasks_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.function_tasks")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
