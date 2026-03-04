from __future__ import annotations

import json
from pathlib import Path

from controlplane_tool.adapters import AdapterResult, ShellCommandAdapter
from controlplane_tool.metrics_contract import CORE_REQUIRED_METRICS, LEGACY_STRICT_REQUIRED_METRICS
from controlplane_tool.models import ControlPlaneConfig, MetricsConfig, Profile, TestsConfig
from controlplane_tool.prometheus_runtime import PrometheusSession
from controlplane_tool.sut_preflight import SutFixture


class _FakePrometheusManager:
    def __init__(self, session: PrometheusSession) -> None:
        self._session = session
        self.ensure_calls = 0
        self.cleanup_calls = 0

    def ensure_available(self, run_dir: Path) -> PrometheusSession:  # noqa: ARG002
        self.ensure_calls += 1
        return self._session

    def cleanup(self, session: PrometheusSession) -> None:  # noqa: ARG002
        self.cleanup_calls += 1


class _FakeMockK8sSession:
    def __init__(self, url: str) -> None:
        self.url = url


class _FakeMockK8sManager:
    def __init__(self, session: _FakeMockK8sSession, should_fail: bool = False) -> None:
        self._session = session
        self.should_fail = should_fail
        self.ensure_calls = 0
        self.cleanup_calls = 0

    def ensure_available(self, run_dir: Path) -> _FakeMockK8sSession:  # noqa: ARG002
        self.ensure_calls += 1
        if self.should_fail:
            raise RuntimeError("mock k8s unavailable")
        return self._session

    def cleanup(self, session: _FakeMockK8sSession) -> None:  # noqa: ARG002
        self.cleanup_calls += 1


class _FakeControlPlaneSession:
    def __init__(self, base_url: str = "http://127.0.0.1:8080") -> None:
        self.base_url = base_url


class _FakeControlPlaneManager:
    def __init__(
        self,
        session: _FakeControlPlaneSession,
        should_fail: bool = False,
    ) -> None:
        self._session = session
        self.should_fail = should_fail
        self.ensure_calls = 0
        self.cleanup_calls = 0
        self.captured_mock_url: str | None = None

    def ensure_available(self, run_dir: Path, kubernetes_api_url: str) -> _FakeControlPlaneSession:
        self.ensure_calls += 1
        self.captured_mock_url = kubernetes_api_url
        if self.should_fail:
            raise RuntimeError("control-plane boot failed")
        return self._session

    def cleanup(self, session: _FakeControlPlaneSession) -> None:  # noqa: ARG002
        self.cleanup_calls += 1


class _FakeSutPreflight:
    def __init__(self, should_fail: bool = False) -> None:
        self.should_fail = should_fail
        self.ensure_calls = 0

    def ensure_fixture(self) -> SutFixture:
        self.ensure_calls += 1
        if self.should_fail:
            raise RuntimeError("fixture bootstrap failed")
        return SutFixture(
            function_name="tool-metrics-echo",
            registered=True,
            warmup_status_code=200,
        )


class _RecordingAdapter(ShellCommandAdapter):
    def __init__(
        self,
        repo_root: Path,
        manager: _FakePrometheusManager,
        mockk8s_manager: _FakeMockK8sManager | None = None,
        control_plane_manager: _FakeControlPlaneManager | None = None,
        preflight: _FakeSutPreflight | None = None,
    ) -> None:
        super().__init__(repo_root=repo_root)
        self.manager = manager
        self.mockk8s_manager = mockk8s_manager or _FakeMockK8sManager(
            _FakeMockK8sSession(url="http://127.0.0.1:18080")
        )
        self.control_plane_manager = control_plane_manager or _FakeControlPlaneManager(
            _FakeControlPlaneSession()
        )
        self.preflight = preflight or _FakeSutPreflight()
        self.commands: list[list[str]] = []

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> AdapterResult:  # noqa: ARG002
        self.commands.append(command)
        return AdapterResult(ok=True, detail="ok")

    def _create_prometheus_manager(self, profile: Profile) -> _FakePrometheusManager:  # noqa: ARG002
        return self.manager

    def _create_sut_preflight(self, profile: Profile):  # noqa: ANN001, ARG002
        return self.preflight

    def _create_sut_preflight_for_base_url(self, profile: Profile, base_url: str):  # noqa: ANN001, ARG002
        return self.preflight

    def _create_mockk8s_manager(self, profile: Profile):  # noqa: ANN001, ARG002
        return self.mockk8s_manager

    def _create_control_plane_manager(self, profile: Profile):  # noqa: ANN001, ARG002
        return self.control_plane_manager


def _profile() -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(
            required=[
                "function_dispatch_total",
                "function_latency_ms",
            ]
        ),
    )


def test_metrics_step_bootstraps_prometheus_when_missing(tmp_path: Path, monkeypatch) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-1",
    )
    manager = _FakePrometheusManager(session=session)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is True
    assert "prometheus" in detail
    observed = json.loads((run_dir / "metrics" / "observed-metrics.json").read_text(encoding="utf-8"))
    assert observed["source"] == "prometheus-api"
    assert observed["owned_container"] is True
    assert manager.ensure_calls == 1
    assert manager.cleanup_calls == 1


def test_metrics_step_bootstraps_mockk8s_and_control_plane(
    tmp_path: Path, monkeypatch
) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-managed",
    )
    manager = _FakePrometheusManager(session=session)
    mockk8s_manager = _FakeMockK8sManager(_FakeMockK8sSession(url="http://127.0.0.1:18080"))
    control_plane_manager = _FakeControlPlaneManager(_FakeControlPlaneSession())
    adapter = _RecordingAdapter(
        repo_root=tmp_path,
        manager=manager,
        mockk8s_manager=mockk8s_manager,
        control_plane_manager=control_plane_manager,
    )
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is True
    assert "prometheus" in detail
    assert mockk8s_manager.ensure_calls == 1
    assert mockk8s_manager.cleanup_calls == 1
    assert control_plane_manager.ensure_calls == 1
    assert control_plane_manager.cleanup_calls == 1
    assert control_plane_manager.captured_mock_url == "http://127.0.0.1:18080"


def test_metrics_step_fails_when_mockk8s_bootstrap_fails(tmp_path: Path, monkeypatch) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-mockfail",
    )
    manager = _FakePrometheusManager(session=session)
    mockk8s_manager = _FakeMockK8sManager(
        _FakeMockK8sSession(url="http://127.0.0.1:18080"),
        should_fail=True,
    )
    control_plane_manager = _FakeControlPlaneManager(_FakeControlPlaneSession())
    adapter = _RecordingAdapter(
        repo_root=tmp_path,
        manager=manager,
        mockk8s_manager=mockk8s_manager,
        control_plane_manager=control_plane_manager,
    )
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],  # noqa: ARG001
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is False
    assert "mock kubernetes bootstrap failed" in detail
    assert mockk8s_manager.ensure_calls == 1
    assert mockk8s_manager.cleanup_calls == 0
    assert control_plane_manager.ensure_calls == 0
    assert manager.ensure_calls == 0


def test_metrics_step_fails_when_control_plane_bootstrap_fails(
    tmp_path: Path, monkeypatch
) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-cpfail",
    )
    manager = _FakePrometheusManager(session=session)
    mockk8s_manager = _FakeMockK8sManager(_FakeMockK8sSession(url="http://127.0.0.1:18080"))
    control_plane_manager = _FakeControlPlaneManager(
        _FakeControlPlaneSession(),
        should_fail=True,
    )
    adapter = _RecordingAdapter(
        repo_root=tmp_path,
        manager=manager,
        mockk8s_manager=mockk8s_manager,
        control_plane_manager=control_plane_manager,
    )
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],  # noqa: ARG001
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is False
    assert "control-plane bootstrap failed" in detail
    assert mockk8s_manager.ensure_calls == 1
    assert mockk8s_manager.cleanup_calls == 1
    assert control_plane_manager.ensure_calls == 1
    assert control_plane_manager.cleanup_calls == 0
    assert manager.ensure_calls == 0


def test_metrics_step_always_cleans_up_owned_prometheus(tmp_path: Path, monkeypatch) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-2",
    )
    manager = _FakePrometheusManager(session=session)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    def _raise_query_error(base_url: str) -> set[str]:  # noqa: ARG001
        raise RuntimeError("query failed")

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        _raise_query_error,
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],  # noqa: ARG001
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is False
    assert "prometheus metrics query failed" in detail
    assert manager.ensure_calls == 1
    assert manager.cleanup_calls == 1


def test_metrics_step_uses_run_window_series_for_missing_detection(
    tmp_path: Path, monkeypatch
) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-3",
    )
    manager = _FakePrometheusManager(session=session)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],  # noqa: ARG001
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is False
    assert "missing required metrics" in detail
    observed = json.loads((run_dir / "metrics" / "observed-metrics.json").read_text(encoding="utf-8"))
    assert observed["observed_run_window"] == []
    assert sorted(observed["available_in_prometheus"]) == [
        "function_dispatch_total",
        "function_latency_ms",
    ]


def test_metrics_step_requires_successful_invocation_preflight(
    tmp_path: Path, monkeypatch
) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-4",
    )
    manager = _FakePrometheusManager(session=session)
    preflight = _FakeSutPreflight(should_fail=True)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager, preflight=preflight)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [],  # noqa: ARG001
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is False
    assert "sut preflight failed" in detail
    assert preflight.ensure_calls == 1
    k6_commands = [command for command in adapter.commands if command and command[0] == "k6"]
    assert k6_commands == []


def test_metrics_step_registers_fixture_before_k6(tmp_path: Path, monkeypatch) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-5",
    )
    manager = _FakePrometheusManager(session=session)
    preflight = _FakeSutPreflight()
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager, preflight=preflight)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    k6_script = tmp_path / "tooling" / "controlplane_tui" / "assets" / "k6" / "tool-metrics-echo.js"
    k6_script.parent.mkdir(parents=True, exist_ok=True)
    k6_script.write_text("export default function(){}", encoding="utf-8")

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {"function_dispatch_total", "function_latency_ms"},  # noqa: ARG005
    )
    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_range_series",
        lambda base_url, metric_name, start, end, step_seconds=2: [  # noqa: ARG001
            {"timestamp": start.isoformat(), "value": 1.0},
            {"timestamp": end.isoformat(), "value": 2.0},
        ],
    )

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is True
    assert "prometheus" in detail
    assert preflight.ensure_calls == 1
    k6_command = next(command for command in adapter.commands if command and command[0] == "k6")
    assert "NANOFAAS_FUNCTION=tool-metrics-echo" in k6_command


def test_metrics_step_uses_core_gate_for_legacy_required_list(
    tmp_path: Path, monkeypatch
) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-6",
    )
    manager = _FakePrometheusManager(session=session)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    profile = Profile(
        name="legacy",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=False, e2e_mockk8s=False, metrics=True),
        metrics=MetricsConfig(
            required=list(LEGACY_STRICT_REQUIRED_METRICS),
            strict_required=False,
        ),
    )

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: set(CORE_REQUIRED_METRICS),  # noqa: ARG005
    )

    def _series(base_url, metric_name, start, end, step_seconds=2):  # noqa: ARG001
        if metric_name in CORE_REQUIRED_METRICS:
            return [
                {"timestamp": start.isoformat(), "value": 1.0},
                {"timestamp": end.isoformat(), "value": 2.0},
            ]
        return []

    monkeypatch.setattr("controlplane_tool.adapters.query_prometheus_range_series", _series)

    ok, detail = adapter.run_metrics_tests(profile, run_dir)

    assert ok is True
    assert "prometheus checks passed" in detail
    observed = json.loads((run_dir / "metrics" / "observed-metrics.json").read_text(encoding="utf-8"))
    assert sorted(observed["required_gate"]) == sorted(CORE_REQUIRED_METRICS)
    assert len(observed["missing"]) == 0


def test_metrics_step_resolves_timer_metric_aliases(tmp_path: Path, monkeypatch) -> None:
    session = PrometheusSession(
        url="http://127.0.0.1:19090",
        owned_container_name="controlplane-tool-prom-7",
    )
    manager = _FakePrometheusManager(session=session)
    adapter = _RecordingAdapter(repo_root=tmp_path, manager=manager)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        "controlplane_tool.adapters.query_prometheus_metric_names",
        lambda base_url: {  # noqa: ARG005
            "function_dispatch_total",
            "function_success_total",
            "function_warm_start_total",
            "function_latency_ms_seconds_count",
            "function_queue_wait_ms_seconds_count",
            "function_e2e_latency_ms_seconds_count",
        },
    )

    def _series(base_url, metric_name, start, end, step_seconds=2):  # noqa: ARG001
        if metric_name in {
            "function_dispatch_total",
            "function_success_total",
            "function_warm_start_total",
            "function_latency_ms_seconds_count",
            "function_queue_wait_ms_seconds_count",
            "function_e2e_latency_ms_seconds_count",
        }:
            return [
                {"timestamp": start.isoformat(), "value": 1.0},
                {"timestamp": end.isoformat(), "value": 2.0},
            ]
        return []

    monkeypatch.setattr("controlplane_tool.adapters.query_prometheus_range_series", _series)

    ok, detail = adapter.run_metrics_tests(_profile(), run_dir)

    assert ok is True
    assert "prometheus checks passed" in detail
    observed = json.loads((run_dir / "metrics" / "observed-metrics.json").read_text(encoding="utf-8"))
    assert observed["missing"] == []
