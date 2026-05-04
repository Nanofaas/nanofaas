from __future__ import annotations

import json
from pathlib import Path

from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.loadtest.loadtest_tasks import bootstrap_loadtest_task, write_loadtest_report_task
from controlplane_tool.core.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.core.run_models import StepResult
from controlplane_tool.scenario.scenario_loader import load_scenario_file


class FakeAdapter:
    def __init__(self, missing: list[str] | None = None) -> None:
        self.missing = list(missing or [])
        self.bootstrap_calls = 0

    def preflight(self, profile: Profile) -> list[str]:  # noqa: ARG002
        return list(self.missing)

    def bootstrap_loadtest(
        self,
        profile: Profile,  # noqa: ARG002
        request: LoadtestRequest,  # noqa: ARG002
        run_dir: Path,  # noqa: ARG002
    ) -> object:
        self.bootstrap_calls += 1
        return {"base_url": "http://127.0.0.1:8080"}


def _request() -> LoadtestRequest:
    profile = Profile(
        name="perf-java",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(required=["function_dispatch_total"]),
    )
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    return LoadtestRequest(
        name="perf-java",
        profile=profile,
        scenario=scenario,
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"]),
    )


def test_bootstrap_task_returns_failed_preflight_step_without_bootstrap(tmp_path: Path) -> None:
    result = bootstrap_loadtest_task(
        adapter=FakeAdapter(missing=["k6", "docker"]),
        request=_request(),
        run_dir=tmp_path,
    )

    assert result.context is None
    assert [step.name for step in result.steps] == ["preflight"]
    assert result.steps[0].status == "failed"
    assert "k6, docker" in result.steps[0].detail


def test_write_report_task_persists_summary_and_html(tmp_path: Path) -> None:
    step = write_loadtest_report_task(
        request=_request(),
        run_dir=tmp_path,
        steps=[StepResult(name="bootstrap", status="passed", detail="ok", duration_ms=1)],
        final_status="passed",
    )

    summary = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))

    assert step.name == "report"
    assert summary["final_status"] == "passed"
    assert summary["steps"][0]["name"] == "bootstrap"
    assert "mock Kubernetes API" in summary["loadtest"]["execution_description"]
    assert "LOCAL fixture functions" in summary["loadtest"]["execution_description"]
    assert "not Kubernetes pods" in summary["loadtest"]["execution_description"]
    assert "sequentially" in summary["loadtest"]["execution_description"]
    assert (tmp_path / "report.html").exists()
    html = (tmp_path / "report.html").read_text(encoding="utf-8")
    assert "Execution Semantics" in html
    assert "mock Kubernetes API" in html
    assert "LOCAL fixture functions" in html
