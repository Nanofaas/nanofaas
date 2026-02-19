from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from control_plane_experiment_config import (  # noqa: E402
    build_deploy_env,
    build_control_plane_modules_selector,
    normalize_module_selection,
)


def test_build_control_plane_modules_selector_none_when_empty():
    assert build_control_plane_modules_selector([]) == "none"


def test_build_control_plane_modules_selector_csv_when_non_empty():
    assert (
        build_control_plane_modules_selector(["async-queue", "sync-queue"])
        == "async-queue,sync-queue"
    )


def test_normalize_module_selection_preserves_available_order():
    available = ["async-queue", "sync-queue", "autoscaler", "runtime-config"]
    selected = ["runtime-config", "async-queue", "runtime-config"]
    assert normalize_module_selection(available, selected) == [
        "async-queue",
        "runtime-config",
    ]


def test_normalize_module_selection_rejects_unknown_modules():
    available = ["async-queue", "sync-queue"]
    try:
        normalize_module_selection(available, ["async-queue", "unknown"])
    except ValueError as exc:
        assert "unknown control-plane modules" in str(exc)
    else:
        raise AssertionError("Expected ValueError for unknown module name")


def test_build_deploy_env_sets_native_and_module_selector():
    env = build_deploy_env(
        vm_name="vm-x",
        cpus="6",
        memory="12G",
        disk="40G",
        namespace="nanofaas",
        keep_vm=True,
        tag="exp-123",
        selected_modules=["async-queue", "sync-queue"],
    )
    assert env["VM_NAME"] == "vm-x"
    assert env["CPUS"] == "6"
    assert env["MEMORY"] == "12G"
    assert env["DISK"] == "40G"
    assert env["NAMESPACE"] == "nanofaas"
    assert env["KEEP_VM"] == "true"
    assert env["TAG"] == "exp-123"
    assert env["CONTROL_PLANE_NATIVE_BUILD"] == "true"
    assert env["CONTROL_PLANE_MODULES"] == "async-queue,sync-queue"


def test_build_deploy_env_maps_empty_modules_to_none():
    env = build_deploy_env(
        vm_name="vm-x",
        cpus="4",
        memory="8G",
        disk="30G",
        namespace="nanofaas",
        keep_vm=False,
        tag="exp-core",
        selected_modules=[],
    )
    assert env["KEEP_VM"] == "false"
    assert env["CONTROL_PLANE_MODULES"] == "none"
