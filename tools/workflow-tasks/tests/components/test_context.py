from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import (
    ResolvedScenarioView,
    ScenarioExecutionContext,
)
from workflow_tasks.vm.models import VmRequest


@dataclass
class _FakeFunction:
    key: str
    family: str | None
    runtime: str
    image: str | None


@dataclass
class _FakeResolvedScenario:
    namespace: str | None
    functions: list[_FakeFunction]


def test_context_holds_neutral_fields() -> None:
    ctx = ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=None,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )
    assert ctx.scenario_name == "k3s-junit-curl"
    assert ctx.manifest_path is None
    assert ctx.release is None
    assert ctx.loadgen_vm_request is None


def test_resolved_scenario_view_is_satisfied_structurally() -> None:
    rs: ResolvedScenarioView = _FakeResolvedScenario(
        namespace="ns",
        functions=[_FakeFunction(key="echo", family="echo", runtime="java", image=None)],
    )
    ctx = ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime="java",
        namespace=None,
        local_registry="r",
        resolved_scenario=rs,
        vm_request=VmRequest(lifecycle="multipass", name="x"),
        cleanup_vm=False,
    )
    assert ctx.resolved_scenario is not None
    assert ctx.resolved_scenario.namespace == "ns"
    assert ctx.resolved_scenario.functions[0].runtime == "java"


def test_context_has_no_request_field() -> None:
    assert "request" not in ScenarioExecutionContext.__dataclass_fields__
