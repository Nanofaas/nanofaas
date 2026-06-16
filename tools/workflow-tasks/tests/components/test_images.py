from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.images import (
    BUILD_CORE,
    BUILD_SELECTED_FUNCTIONS,
    control_image,
    function_image_specs,
    plan_build_core,
    plan_build_selected_functions,
    runtime_image,
)
from workflow_tasks.vm.models import VmRequest


REPO_ROOT = Path(__file__).resolve().parents[4]
LIVE_E2E_SCENARIO_IMAGE_CONSUMERS = (
    "tools/workflow-tasks/src/workflow_tasks/components/images.py",
    "tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py",
    "tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py",
)
REMOVED_RELEASE_IMAGE_CLI_SYNTAX = ("--arch-suffix", "--arch multi")


@dataclass
class _Fn:
    key: str
    family: str | None
    runtime: str
    image: str | None


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, runtime: str = "java", functions: list | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime=runtime,
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="ns", functions=functions or []),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_image_name_helpers() -> None:
    assert control_image("reg:5000") == "reg:5000/nanofaas/control-plane:e2e"
    assert runtime_image("reg:5000") == "reg:5000/nanofaas/function-runtime:e2e"


def test_e2e_image_components_keep_local_e2e_tags() -> None:
    assert control_image("localhost:5000") == "localhost:5000/nanofaas/control-plane:e2e"
    assert runtime_image("localhost:5000") == "localhost:5000/nanofaas/function-runtime:e2e"


def test_live_e2e_scenario_image_consumers_do_not_use_removed_images_cli_syntax() -> None:
    violations = {
        str(path.relative_to(REPO_ROOT)): syntax
        for relative_path in LIVE_E2E_SCENARIO_IMAGE_CONSUMERS
        for path in [REPO_ROOT / relative_path]
        for syntax in REMOVED_RELEASE_IMAGE_CLI_SYNTAX
        if syntax in path.read_text(encoding="utf-8")
    }

    assert violations == {}


def test_function_image_specs_skips_fixtures_and_familyless() -> None:
    fns = [
        _Fn(key="a", family="echo", runtime="java", image=None),
        _Fn(key="b", family=None, runtime="java", image=None),
        _Fn(key="c", family="x", runtime="fixture", image=None),
    ]
    specs = function_image_specs(_RS(namespace=None, functions=fns), "fallback:img")
    assert [s[3] for s in specs] == ["a"]
    assert specs[0][0] == "fallback:img"


def test_plan_build_core_java_builds_jars_and_pushes() -> None:
    ids = [op.operation_id for op in plan_build_core(_ctx(runtime="java"))]
    assert "images.build_core.boot_jars" in ids
    assert "images.build_core.control_image" in ids
    assert "images.build_core.push_runtime_image" in ids


def test_plan_build_core_rust_skips_boot_jars() -> None:
    ids = [op.operation_id for op in plan_build_core(_ctx(runtime="rust"))]
    assert "images.build_core.boot_jars" not in ids
    assert "images.build_core.control_image" in ids


def test_plan_build_selected_functions_emits_build_push_prune() -> None:
    fns = [_Fn(key="echo", family="echo", runtime="java", image="reg:5000/echo:e2e")]
    ids = [op.operation_id for op in plan_build_selected_functions(_ctx(functions=fns))]
    assert "images.build_selected_functions.echo" in ids
    assert "images.push_selected_functions.echo" in ids
    assert any(i.startswith("images.prune_selected_functions") for i in ids)


def test_component_definitions_wire_planners() -> None:
    assert BUILD_CORE.planner is plan_build_core
    assert BUILD_SELECTED_FUNCTIONS.planner is plan_build_selected_functions
