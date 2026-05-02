from pathlib import Path

from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.loadtest_runner import LoadtestRunner
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.scenario_loader import load_scenario_file


class FakeAdapter:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def preflight(self, profile: Profile) -> list[str]:  # noqa: ARG002
        self.calls.append("preflight")
        return []

    def bootstrap_loadtest(
        self,
        profile: Profile,  # noqa: ARG002
        request: LoadtestRequest,  # noqa: ARG002
        run_dir: Path,  # noqa: ARG002
    ) -> object:
        self.calls.append("bootstrap")
        return {"base_url": "http://127.0.0.1:8080"}

    def run_loadtest_k6(
        self,
        request: LoadtestRequest,  # noqa: ARG002
        context: object,  # noqa: ARG002
        run_dir: Path,  # noqa: ARG002
    ) -> tuple[bool, str]:
        self.calls.append("load_k6")
        return (True, "k6 ok")

    def evaluate_metrics_gate(
        self,
        profile: Profile,  # noqa: ARG002
        request: LoadtestRequest,  # noqa: ARG002
        context: object,  # noqa: ARG002
        run_dir: Path,  # noqa: ARG002
    ) -> tuple[bool, str]:
        self.calls.append("metrics_gate")
        return (True, "metrics ok")

    def cleanup_loadtest(self, context: object) -> None:  # noqa: ARG002
        self.calls.append("cleanup")


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


def test_loadtest_runner_executes_preflight_k6_and_metrics_gate(tmp_path: Path) -> None:
    adapter = FakeAdapter()

    result = LoadtestRunner(adapter=adapter).run(_request(), runs_root=tmp_path)

    assert [step.name for step in result.steps] == [
        "preflight",
        "bootstrap",
        "load_k6",
        "metrics_gate",
        "report",
    ]
    assert result.final_status == "passed"
    assert (result.run_dir / "summary.json").exists()
    assert (result.run_dir / "report.html").exists()
    assert adapter.calls == [
        "preflight",
        "bootstrap",
        "load_k6",
        "metrics_gate",
        "cleanup",
    ]


def test_loadtest_runner_executes_every_selected_target(tmp_path: Path) -> None:
    adapter = FakeAdapter()

    result = LoadtestRunner(adapter=adapter).run(_request(), runs_root=tmp_path)

    assert "word-stats-java" in result.steps[-1].detail
    assert "json-transform-java" in result.steps[-1].detail


def test_loadtest_runner_emits_step_progress_events(tmp_path: Path) -> None:
    adapter = FakeAdapter()
    events = []

    LoadtestRunner(adapter=adapter).run(_request(), runs_root=tmp_path, event_listener=events.append)

    assert [(event.step_name, event.status) for event in events] == [
        ("preflight", "running"),
        ("preflight", "passed"),
        ("bootstrap", "running"),
        ("bootstrap", "passed"),
        ("load_k6", "running"),
        ("load_k6", "passed"),
        ("metrics_gate", "running"),
        ("metrics_gate", "passed"),
        ("report", "running"),
        ("report", "passed"),
    ]


def test_loadtest_runner_no_longer_inlines_process_sequencing() -> None:
    source = (
        Path(__file__).resolve().parents[3]
        / "tools"
        / "controlplane"
        / "src"
        / "controlplane_tool"
        / "loadtest_runner.py"
    ).read_text(encoding="utf-8")

    assert "bootstrap_loadtest(" not in source
    assert "run_loadtest_k6(" not in source
    assert "evaluate_metrics_gate(" not in source
    assert "cleanup_loadtest(" not in source
