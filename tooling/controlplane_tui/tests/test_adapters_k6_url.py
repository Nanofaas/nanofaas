from pathlib import Path

from controlplane_tool.adapters import AdapterResult, ShellCommandAdapter
from controlplane_tool.control_plane_runtime import ControlPlaneSession
from controlplane_tool.mockk8s_runtime import MockK8sSession
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.prometheus_runtime import PrometheusSession
from controlplane_tool.sut_preflight import SutFixture


class FakePrometheusManager:
    def ensure_available(self, run_dir: Path) -> PrometheusSession:  # noqa: ARG002
        return PrometheusSession(url="http://127.0.0.1:9090", owned_container_name=None)

    def cleanup(self, session: PrometheusSession) -> None:  # noqa: ARG002
        return None


class FakeSutPreflight:
    def ensure_fixture(self) -> SutFixture:
        return SutFixture(
            function_name="tool-metrics-echo",
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

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> AdapterResult:  # noqa: ARG002
        self.commands.append(command)
        return AdapterResult(ok=True, detail="ok")

    def _create_prometheus_manager(self, profile: Profile) -> FakePrometheusManager:  # noqa: ARG002
        return FakePrometheusManager()

    def _create_sut_preflight_for_base_url(
        self,
        profile: Profile,  # noqa: ARG002
        base_url: str,  # noqa: ARG002
    ) -> FakeSutPreflight:
        return FakeSutPreflight()

    def _create_mockk8s_manager(self, profile: Profile) -> FakeMockK8sManager:  # noqa: ARG002
        return FakeMockK8sManager()

    def _create_control_plane_manager(
        self,
        profile: Profile,  # noqa: ARG002
    ) -> FakeControlPlaneManager:
        return FakeControlPlaneManager()


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
    k6_script = root / "tooling" / "controlplane_tui" / "assets" / "k6" / "tool-metrics-echo.js"
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
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {  # noqa: ARG005
            "function_dispatch_total",
            "function_latency_ms",
            "function_e2e_latency_ms",
        },
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )
    adapter.run_metrics_tests(profile, run_dir)

    k6_command = next(command for command in adapter.commands if command and command[0] == "k6")
    assert "NANOFAAS_URL=http://127.0.0.1:8080" in k6_command
    assert "NANOFAAS_FUNCTION=tool-metrics-echo" in k6_command
