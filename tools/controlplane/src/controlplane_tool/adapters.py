from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from controlplane_tool.build_requests import BuildRequest
from controlplane_tool.gradle_planner import build_gradle_command
from controlplane_tool.metrics import (
    missing_required_metrics,
    query_prometheus_metric_names,
    query_prometheus_range_series,
)
from controlplane_tool.metrics_contract import (
    CORE_REQUIRED_METRICS,
    LEGACY_STRICT_REQUIRED_METRICS,
)
from controlplane_tool.control_plane_runtime import (
    ControlPlaneRuntimeManager,
    ControlPlaneSession,
)
from controlplane_tool.mockk8s import default_mockk8s_test_selectors
from controlplane_tool.mockk8s_runtime import MockK8sRuntimeManager, MockK8sSession
from controlplane_tool.models import Profile
from controlplane_tool.prometheus_runtime import PrometheusRuntimeManager, PrometheusSession
from controlplane_tool.sut_preflight import SutPreflight


@dataclass(frozen=True)
class AdapterResult:
    ok: bool
    detail: str


class ShellCommandAdapter:
    def __init__(self, repo_root: Path | None = None) -> None:
        self.repo_root = repo_root or Path.cwd()

    def _modules_selector(self, profile: Profile) -> str:
        if not profile.modules:
            return "none"
        return ",".join(profile.modules)

    def _build_gradle_command(
        self,
        action: str,
        profile: Profile,
        extra_gradle_args: list[str] | None = None,
    ) -> list[str]:
        request = BuildRequest(
            action=action,
            profile="core",
            modules=self._modules_selector(profile),
        )
        return build_gradle_command(
            repo_root=self.repo_root,
            request=request,
            extra_gradle_args=extra_gradle_args,
        )

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> AdapterResult:
        log_path = run_dir / log_name
        start = time.time()
        with log_path.open("a", encoding="utf-8") as log_file:
            log_file.write(f"$ {' '.join(command)}\n")
            completed = subprocess.run(
                command,
                cwd=self.repo_root,
                capture_output=True,
                text=True,
                check=False,
            )
            if completed.stdout:
                log_file.write(completed.stdout)
            if completed.stderr:
                log_file.write(completed.stderr)
        duration_ms = int((time.time() - start) * 1000)
        if completed.returncode == 0:
            return AdapterResult(ok=True, detail=f"ok ({duration_ms} ms)")
        return AdapterResult(
            ok=False,
            detail=f"exit={completed.returncode} ({duration_ms} ms), see {log_path.name}",
        )

    def _resolve_prometheus_url(self, profile: Profile) -> str | None:
        if profile.metrics.prometheus_url and profile.metrics.prometheus_url.strip():
            return profile.metrics.prometheus_url.strip()
        env_url = os.getenv("NANOFAAS_TOOL_PROMETHEUS_URL", "").strip()
        return env_url or None

    def _create_prometheus_manager(self, profile: Profile) -> PrometheusRuntimeManager:
        return PrometheusRuntimeManager(
            repo_root=self.repo_root,
            preferred_url=self._resolve_prometheus_url(profile),
        )

    def _create_mockk8s_manager(self, profile: Profile) -> MockK8sRuntimeManager:  # noqa: ARG002
        return MockK8sRuntimeManager(repo_root=self.repo_root)

    def _create_control_plane_manager(
        self,
        profile: Profile,  # noqa: ARG002
    ) -> ControlPlaneRuntimeManager:
        return ControlPlaneRuntimeManager(repo_root=self.repo_root)

    def _create_sut_preflight_for_base_url(
        self,
        profile: Profile,  # noqa: ARG002
        base_url: str,
    ) -> SutPreflight:
        return SutPreflight(base_url=base_url, fixture_name="tool-metrics-echo")

    def _gate_required_metrics(self, profile: Profile) -> list[str]:
        configured = list(profile.metrics.required)
        if profile.metrics.strict_required:
            return configured
        if not configured:
            return list(CORE_REQUIRED_METRICS)
        if set(configured) == set(LEGACY_STRICT_REQUIRED_METRICS):
            return list(CORE_REQUIRED_METRICS)
        return configured

    def _query_candidates_for_metric(self, metric_name: str) -> list[str]:
        candidates = [metric_name]
        if metric_name.endswith("_ms"):
            candidates.append(f"{metric_name}_seconds_count")
            candidates.append(f"{metric_name}_count")
        return candidates

    def _query_series_with_aliases(
        self,
        base_url: str,
        metric_name: str,
        start: datetime,
        end: datetime,
    ) -> list[dict[str, float | str]]:
        for candidate in self._query_candidates_for_metric(metric_name):
            points = query_prometheus_range_series(
                base_url=base_url,
                metric_name=candidate,
                start=start,
                end=end,
                step_seconds=2,
            )
            if points:
                return points
        return []

    def preflight(self, profile: Profile) -> list[str]:
        missing: list[str] = []
        if shutil.which("docker") is None:
            missing.append("docker")
        requires_gradle = profile.control_plane.implementation == "java" or (
            profile.tests.enabled
            and (profile.tests.api or profile.tests.e2e_mockk8s or profile.tests.metrics)
        )
        if requires_gradle:
            if not (self.repo_root / "gradlew").exists():
                missing.append("gradlew")
        if profile.control_plane.implementation == "rust":
            if shutil.which("cargo") is None:
                missing.append("cargo")
        if profile.tests.enabled and profile.tests.metrics and shutil.which("k6") is None:
            missing.append("k6")
        return missing

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            manifest = rust_dir / "Cargo.toml"
            if not manifest.exists():
                return (
                    False,
                    f"Rust control plane manifest not found at {manifest}",
                )
            result = self._run(
                ["cargo", "build", "--release", "--manifest-path", str(manifest)],
                run_dir,
                "build.log",
            )
            return (result.ok, result.detail)

        if profile.control_plane.build_mode == "native":
            command = self._build_gradle_command("native", profile)
        else:
            command = self._build_gradle_command("build", profile)
        result = self._run(command, run_dir, "build.log")
        return (result.ok, result.detail)

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        tag = f"nanofaas/control-plane:{profile.name}"
        if profile.control_plane.implementation == "rust":
            rust_dir = self.repo_root / "control-plane-rust"
            dockerfile = rust_dir / "Dockerfile"
            if not dockerfile.exists():
                return (False, f"Rust Dockerfile not found at {dockerfile}")
            command = [
                "docker",
                "build",
                "-f",
                str(dockerfile),
                "-t",
                tag,
                str(rust_dir),
            ]
            result = self._run(command, run_dir, "build.log")
            return (result.ok, f"{result.detail}; image={tag}")

        if profile.control_plane.build_mode == "native":
            command = self._build_gradle_command(
                "image",
                profile,
                extra_gradle_args=[f"-PcontrolPlaneImage={tag}"],
            )
        else:
            command = self._build_gradle_command("build", profile)
            first = self._run(command, run_dir, "build.log")
            if not first.ok:
                return (False, first.detail)
            command = [
                "docker",
                "build",
                "-f",
                str(self.repo_root / "control-plane" / "Dockerfile"),
                "-t",
                tag,
                str(self.repo_root / "control-plane"),
            ]
        result = self._run(command, run_dir, "build.log")
        return (result.ok, f"{result.detail}; image={tag}")

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = self._build_gradle_command(
            "test",
            profile,
            extra_gradle_args=["--tests", "*ControlPlaneApiTest"],
        )
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        extra_gradle_args: list[str] = []
        for selector in default_mockk8s_test_selectors():
            extra_gradle_args.extend(["--tests", selector])
        command = self._build_gradle_command(
            "test",
            profile,
            extra_gradle_args=extra_gradle_args,
        )
        result = self._run(command, run_dir, "test.log")
        return (result.ok, result.detail)

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        command = self._build_gradle_command(
            "test",
            profile,
            extra_gradle_args=[
                "--tests",
                "*PrometheusEndpointTest",
                "--tests",
                "*MetricsTest",
            ],
        )
        result = self._run(command, run_dir, "test.log")
        if not result.ok:
            return (False, result.detail)

        configured_required_metrics = list(profile.metrics.required)
        gate_required_metrics = self._gate_required_metrics(profile)
        query_metrics = sorted(set(configured_required_metrics) | set(gate_required_metrics))
        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)
        mockk8s_manager = self._create_mockk8s_manager(profile)
        control_plane_manager = self._create_control_plane_manager(profile)
        prometheus_manager = self._create_prometheus_manager(profile)
        mockk8s_session: MockK8sSession | None = None
        control_plane_session: ControlPlaneSession | None = None
        session: PrometheusSession | None = None

        try:
            try:
                mockk8s_session = mockk8s_manager.ensure_available(run_dir=run_dir)
            except RuntimeError as exc:
                return (False, f"mock kubernetes bootstrap failed: {exc}")

            try:
                control_plane_session = control_plane_manager.ensure_available(
                    run_dir=run_dir,
                    kubernetes_api_url=mockk8s_session.url,
                )
            except RuntimeError as exc:
                return (False, f"control-plane bootstrap failed: {exc}")

            scrape_target = getattr(control_plane_session, "prometheus_scrape_target", None)
            if scrape_target and scrape_target.strip():
                prometheus_manager.scrape_target = scrape_target.strip()
            try:
                session = prometheus_manager.ensure_available(run_dir=run_dir)
            except RuntimeError as exc:
                return (False, f"prometheus bootstrap failed: {exc}")

            started_at = datetime.now(timezone.utc)
            target_url = control_plane_session.base_url.rstrip("/")
            sut_preflight = self._create_sut_preflight_for_base_url(profile, target_url)
            try:
                fixture = sut_preflight.ensure_fixture()
            except RuntimeError as exc:
                return (False, f"sut preflight failed: {exc}")
            k6_script = (
                self.repo_root / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
            )
            k6_ok = True
            k6_detail = "k6 skipped"
            if k6_script.exists():
                k6_summary = metrics_dir / "k6-summary.json"
                k6_result = self._run(
                    [
                        "k6",
                        "run",
                        "--summary-export",
                        str(k6_summary),
                        "-e",
                        f"NANOFAAS_URL={target_url}",
                        "-e",
                        f"NANOFAAS_FUNCTION={fixture.function_name}",
                        str(k6_script),
                    ],
                    run_dir,
                    "test.log",
                )
                k6_ok = k6_result.ok
                k6_detail = k6_result.detail
            ended_at = datetime.now(timezone.utc)
            if ended_at <= started_at:
                ended_at = started_at + timedelta(seconds=1)

            observed_run_metrics: set[str] = set()
            series: dict[str, list[dict[str, float | str]]] = {}
            for metric in query_metrics:
                points = self._query_series_with_aliases(
                    base_url=session.url,
                    metric_name=metric,
                    start=started_at,
                    end=ended_at,
                )
                if not points:
                    points = [
                        {"timestamp": started_at.isoformat(), "value": 0.0},
                        {"timestamp": ended_at.isoformat(), "value": 0.0},
                    ]
                else:
                    observed_run_metrics.add(metric)
                series[metric] = points

            missing = missing_required_metrics(gate_required_metrics, observed_run_metrics)
            available_metrics = query_prometheus_metric_names(session.url)
            (metrics_dir / "series.json").write_text(
                json.dumps(series, indent=2), encoding="utf-8"
            )
            (metrics_dir / "observed-metrics.json").write_text(
                json.dumps(
                    {
                        "source": "prometheus-api",
                        "endpoint": session.url,
                        "owned_container": bool(session.owned_container_name),
                        "observed_run_window": sorted(observed_run_metrics),
                        "available_in_prometheus": sorted(available_metrics),
                        "required_gate": gate_required_metrics,
                        "required_configured": configured_required_metrics,
                        "missing": missing,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )

            if missing:
                return (
                    False,
                    "missing required metrics: "
                    + ", ".join(missing)
                    + " (see metrics/observed-metrics.json)",
                )
            if not k6_ok:
                return (False, f"prometheus checks passed, k6 failed: {k6_detail}")
            if k6_script.exists():
                return (True, f"prometheus + k6: {k6_detail}")
            return (True, "prometheus checks passed; k6 script missing, skipped load")
        except RuntimeError as exc:
            return (False, f"prometheus metrics query failed: {exc}")
        finally:
            if session is not None:
                prometheus_manager.cleanup(session)
            if control_plane_session is not None:
                control_plane_manager.cleanup(control_plane_session)
            if mockk8s_session is not None:
                mockk8s_manager.cleanup(mockk8s_session)
