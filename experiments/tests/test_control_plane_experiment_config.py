from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from control_plane_experiment_config import (  # noqa: E402
    build_deploy_env,
    build_control_plane_modules_selector,
    discover_module_dependencies,
    split_module_selection_details,
    resolve_module_selection_with_dependencies,
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


def test_build_deploy_env_forces_non_native_for_rust_and_sets_module_selector():
    env = build_deploy_env(
        vm_name="vm-x",
        cpus="6",
        memory="12G",
        disk="40G",
        namespace="nanofaas",
        keep_vm=True,
        tag="exp-123",
        control_plane_runtime="rust",
        control_plane_native_build=True,
        control_plane_only=True,
        host_rebuild_images=True,
        loadtest_workloads="word-stats,json-transform",
        loadtest_runtimes="java,python,exec,java-lite",
        selected_modules=["async-queue", "sync-queue"],
    )
    assert env["VM_NAME"] == "vm-x"
    assert env["CPUS"] == "6"
    assert env["MEMORY"] == "12G"
    assert env["DISK"] == "40G"
    assert env["NAMESPACE"] == "nanofaas"
    assert env["KEEP_VM"] == "true"
    assert env["TAG"] == "exp-123"
    assert env["CONTROL_PLANE_RUNTIME"] == "rust"
    assert env["CONTROL_PLANE_NATIVE_BUILD"] == "false"
    assert env["CONTROL_PLANE_BUILD_ON_HOST"] == "true"
    assert env["CONTROL_PLANE_ONLY"] == "true"
    assert env["HOST_REBUILD_IMAGES"] == "true"
    assert env["LOADTEST_WORKLOADS"] == "word-stats,json-transform"
    assert env["LOADTEST_RUNTIMES"] == "java,python,exec,java-lite"
    assert env["E2E_K3S_HELM_NONINTERACTIVE"] == "true"
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
        control_plane_runtime="java",
        control_plane_native_build=False,
        control_plane_only=False,
        host_rebuild_images=False,
        loadtest_workloads="word-stats",
        loadtest_runtimes="java",
        selected_modules=[],
    )
    assert env["KEEP_VM"] == "false"
    assert env["CONTROL_PLANE_NATIVE_BUILD"] == "false"
    assert env["CONTROL_PLANE_ONLY"] == "false"
    assert env["HOST_REBUILD_IMAGES"] == "false"
    assert env["CONTROL_PLANE_RUNTIME"] == "java"
    assert env["LOADTEST_WORKLOADS"] == "word-stats"
    assert env["LOADTEST_RUNTIMES"] == "java"
    assert env["CONTROL_PLANE_MODULES"] == "none"


def test_resolve_module_selection_auto_adds_dependencies():
    available = ["async-queue", "sync-queue", "autoscaler"]
    dependencies = {"sync-queue": ["async-queue"]}
    resolved = resolve_module_selection_with_dependencies(
        available_modules=available,
        selected_modules=["sync-queue"],
        module_dependencies=dependencies,
    )
    assert resolved == ["async-queue", "sync-queue"]


def test_resolve_module_selection_applies_transitive_dependencies():
    available = ["a", "b", "c", "d"]
    dependencies = {
        "d": ["c"],
        "c": ["b"],
        "b": ["a"],
    }
    resolved = resolve_module_selection_with_dependencies(
        available_modules=available,
        selected_modules=["d"],
        module_dependencies=dependencies,
    )
    assert resolved == ["a", "b", "c", "d"]


def test_resolve_module_selection_rejects_missing_dependency():
    available = ["sync-queue"]
    dependencies = {"sync-queue": ["async-queue"]}
    try:
        resolve_module_selection_with_dependencies(
            available_modules=available,
            selected_modules=["sync-queue"],
            module_dependencies=dependencies,
        )
    except ValueError as exc:
        assert "depends on missing module" in str(exc)
    else:
        raise AssertionError("Expected ValueError for missing dependency")


def test_discover_module_dependencies_reads_build_gradle(tmp_path):
    modules_root = tmp_path / "control-plane-modules"
    sync_module = modules_root / "sync-queue"
    async_module = modules_root / "async-queue"
    sync_module.mkdir(parents=True)
    async_module.mkdir(parents=True)

    (sync_module / "build.gradle").write_text(
        "\n".join(
            [
                "dependencies {",
                "    implementation project(':control-plane-modules:async-queue')",
                "}",
            ]
        ),
        encoding="utf-8",
    )
    (async_module / "build.gradle").write_text(
        "dependencies { implementation project(':common') }",
        encoding="utf-8",
    )

    deps = discover_module_dependencies(modules_root)
    assert deps["sync-queue"] == ["async-queue"]
    assert deps["async-queue"] == []


def test_split_module_selection_details_preserves_order():
    explicit, auto = split_module_selection_details(
        resolved_modules=["async-queue", "sync-queue", "autoscaler"],
        explicitly_selected_modules=["sync-queue", "autoscaler"],
    )
    assert explicit == ["sync-queue", "autoscaler"]
    assert auto == ["async-queue"]
