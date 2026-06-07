from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.scenario.loadtest_adapter import (
    InstallEndpoint,
    MultipassLoadtestAdapter,
)
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext


def test_install_endpoint_fields() -> None:
    ep = InstallEndpoint(host="1.2.3.4", user="ubuntu", private_key=Path("/k"), port=None)
    assert (ep.host, ep.user, ep.private_key, ep.port) == ("1.2.3.4", "ubuntu", Path("/k"), None)


def test_multipass_adapter_title_suffix_is_empty() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    assert adapter.title_suffix == ""


def test_multipass_adapter_extra_steps_are_empty() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    ctx = RunContext()
    assert adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx) == []
    assert adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx) == []
    assert adapter.extra_step_ids(FlowPhase.AFTER_STACK_READY) == []
    assert adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN) == []
