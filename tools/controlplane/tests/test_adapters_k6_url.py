from pathlib import Path

from controlplane_tool.orchestation.adapters import AdapterResult, ShellCommandAdapter
from controlplane_tool.infra.runtimes import ControlPlaneSession
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.infra.runtimes import MockK8sSession
from controlplane_tool.core.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.infra.runtimes import PrometheusSession
from controlplane_tool.scenario.scenario_loader import load_scenario_file
from controlplane_tool.sut.sut_preflight import SutFixture


class FakePrometheusManager:
    def ensure_available(self, run_dir: Path) -> PrometheusSession:  # noqa: ARG002
        return PrometheusSession(url="http://127.0.0.1:9090", owned_container_name=None)

    def cleanup(self, session: PrometheusSession) -> None:  # noqa: ARG002
        return None


class FakeSutPreflight:
    def __init__(self, function_name: str = "tool-metrics-echo") -> None:
        self.function_name = function_name

    def ensure_fixture(self) -> SutFixture:
        return SutFixture(
            function_name=self.function_name,
            registered=True,
            warmup_status_code=200,
        )


class FakeMockK8sManager:
    def ensure_available(self, run_dir: Path) -> MockK8sSession:  # noqa: ARG002
        return MockK8sSession(url="http://127.0.0.1:18080")

    def cleanup(self, session: MockK8sSession) -> None:  # noqa: ARG002
        return None


class FakeControlPlaneManager:
    def ensure_available(self, run_dir: Path, kubernetes_api_url: str) -> ControlPlaneSession:  # noqa: ARG002
        return ControlPlaneSession(
            base_url="http://127.0.0.1:8080",
            management_url="http://127.0.0.1:8081",
            api_port=8080,
            management_port=8081,
        )

    def cleanup(self, session: ControlPlaneSession) -> None:  # noqa: ARG002
        return None


class RecordingAdapter(ShellCommandAdapter):
    def __init__(self, repo_root: Path) -> None:
        super().__init__(repo_root=repo_root)
        self.commands: list[list[str]] = []

        recording_self = self
        self._gradle._run = lambda cmd, run_dir, log_name: (  # type: ignore[method-assign]
            recording_self.commands.append(cmd) or AdapterResult(ok=True, detail="ok")
        )
        self._k6._run = lambda cmd, run_dir, log_name: (  # type: ignore[method-assign]
            recording_self.commands.append(cmd) or AdapterResult(ok=True, detail="ok")
        )
        self._bootstrap._create_prometheus_manager = lambda p: FakePrometheusManager()  # type: ignore[method-assign]
        self._bootstrap._create_mockk8s_manager = lambda p: FakeMockK8sManager()  # type: ignore[method-assign]
        self._bootstrap._create_control_plane_manager = lambda p: FakeControlPlaneManager()  # type: ignore[method-assign]
        self._bootstrap._create_sut_preflight = lambda url, name: FakeSutPreflight(function_name=name)  # type: ignore[method-assign]


def _prepare_fake_repo(root: Path) -> None:
    metrics_java = (
        root
        / "control-plane"
        / "src"
        / "main"
        / "java"
        / "it"
        / "unimib"
        / "datai"
        / "nanofaas"
        / "controlplane"
        / "service"
        / "Metrics.java"
    )
    metrics_java.parent.mkdir(parents=True, exist_ok=True)
    metrics_java.write_text(
        'class Metrics { void x(){ "function_dispatch_total".toString(); "function_latency_ms".toString(); "function_e2e_latency_ms".toString(); } }',
        encoding="utf-8",
    )
    k6_script = root / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
    k6_script.parent.mkdir(parents=True, exist_ok=True)
    k6_script.write_text("export default function(){}", encoding="utf-8")


def test_metrics_k6_uses_control_plane_base_url(tmp_path: Path, monkeypatch) -> None:
    _prepare_fake_repo(tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True)

    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(
            required=[
                "function_dispatch_total",
                "function_latency_ms",
                "function_e2e_latency_ms",
            ]
        ),
    )

    adapter = RecordingAdapter(repo_root=tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_metric_names",
        lambda base_url: {  # noqa: ARG005
            "function_dispatch_total",
            "function_latency_ms",
            "function_e2e_latency_ms",
        },
    )
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )
    adapter.run_metrics_tests(profile, run_dir)

    gradle_command = next(command for command in adapter.commands if command and command[0].endswith("/gradlew"))
    assert gradle_command[:2] == [str(tmp_path / "gradlew"), ":control-plane:test"]
    assert "-PcontrolPlaneModules=none" in gradle_command
    k6_command = next(command for command in adapter.commands if command and command[0] == "k6")
    assert "NANOFAAS_URL=http://127.0.0.1:8080" in k6_command
    assert "NANOFAAS_FUNCTION=tool-metrics-echo" in k6_command


def test_loadtest_k6_uses_resolved_scenario_manifest_and_target(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _prepare_fake_repo(tmp_path)
    run_dir = tmp_path / "run-loadtest"
    run_dir.mkdir(parents=True)

    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(required=["function_dispatch_total"]),
    )
    request = LoadtestRequest(
        name="qa",
        profile=profile,
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"]),
    )

    adapter = RecordingAdapter(repo_root=tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )

    context = adapter.bootstrap_loadtest(profile, request, run_dir)
    try:
        ok, detail = adapter.run_loadtest_k6(request, context, run_dir)
    finally:
        adapter.cleanup_loadtest(context)

    assert ok is True
    assert "k6" in detail
    k6_command = next(command for command in adapter.commands if command and command[0] == "k6")
    assert "NANOFAAS_URL=http://127.0.0.1:8080" in k6_command
    assert "NANOFAAS_FUNCTION=word-stats-java" in k6_command
    assert any(item.startswith("NANOFAAS_SCENARIO_MANIFEST=") for item in k6_command)
    assert "--stage" in k6_command


def test_loadtest_k6_runs_all_requested_targets_in_order(
    tmp_path: Path,
    monkeypatch,
) -> None:
    _prepare_fake_repo(tmp_path)
    run_dir = tmp_path / "run-all-targets"
    run_dir.mkdir(parents=True)

    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(required=["function_dispatch_total"]),
    )
    request = LoadtestRequest(
        name="qa",
        profile=profile,
        scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
        load_profile=resolve_load_profile("quick"),
        metrics_gate=MetricsGate(required_metrics=["function_dispatch_total"]),
    )

    adapter = RecordingAdapter(repo_root=tmp_path)
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.loadtest.metrics_gate.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )

    context = adapter.bootstrap_loadtest(profile, request, run_dir)
    try:
        ok, detail = adapter.run_loadtest_k6(request, context, run_dir)
    finally:
        adapter.cleanup_loadtest(context)

    assert ok is True
    assert "word-stats-java" in detail
    assert "json-transform-java" in detail
    k6_commands = [command for command in adapter.commands if command and command[0] == "k6"]
    assert len(k6_commands) == 2
    assert "NANOFAAS_FUNCTION=word-stats-java" in k6_commands[0]
    assert "NANOFAAS_FUNCTION=json-transform-java" in k6_commands[1]
