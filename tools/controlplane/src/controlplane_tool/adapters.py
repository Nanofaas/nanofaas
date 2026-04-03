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
from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import (
    LoadProfileDefinition,
    LoadtestRequest,
    MetricsGate,
    TargetRunResult,
)
from controlplane_tool.mockk8s import default_mockk8s_test_selectors
from controlplane_tool.mockk8s_runtime import MockK8sRuntimeManager, MockK8sSession
from controlplane_tool.models import Profile
from controlplane_tool.prometheus_runtime import PrometheusRuntimeManager, PrometheusSession
from controlplane_tool.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario_manifest import write_scenario_manifest
from controlplane_tool.scenario_models import ScenarioSpec
from controlplane_tool.sut_preflight import SutPreflight


@dataclass(frozen=True)
class AdapterResult:
    ok: bool
    detail: str


@dataclass
class LoadtestBootstrapContext:
    base_url: str
    prometheus_url: str
    scenario_manifest_path: Path
    target_functions: list[str]
    target_results: list[TargetRunResult]
    started_at: datetime
    mockk8s_manager: object
    control_plane_manager: object
    prometheus_manager: object
    mockk8s_session: MockK8sSession | None
    control_plane_session: ControlPlaneSession | None
    prometheus_session: PrometheusSession | None


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

    def _create_sut_preflight_for_target(
        self,
        profile: Profile,  # noqa: ARG002
        base_url: str,
        fixture_name: str,
    ) -> SutPreflight:
        return SutPreflight(base_url=base_url, fixture_name=fixture_name)

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

    def _resolve_loadtest_targets(self, request: LoadtestRequest) -> list[str]:
        if request.targets is not None and request.targets.targets:
            return list(request.targets.targets)
        if request.scenario.function_keys:
            return list(request.scenario.function_keys)
        raise ValueError("loadtest request does not define any targets")

    def _k6_stage_args(self, load_profile: LoadProfileDefinition) -> list[str]:
        args: list[str] = []
        for stage in load_profile.stages:
            args.extend(["--stage", f"{stage.duration}:{stage.target}"])
        return args

    def _legacy_loadtest_request(self, profile: Profile) -> LoadtestRequest:
        scenario = resolve_scenario_spec(
            ScenarioSpec(
                name=f"{profile.name}-metrics",
                base_scenario=profile.scenario.base_scenario or "k8s-vm",
                runtime=profile.control_plane.implementation,
                function_preset=profile.scenario.function_preset or "metrics-smoke",
                functions=list(profile.scenario.functions),
                namespace=profile.scenario.namespace,
                local_registry=profile.scenario.local_registry,
            )
        )
        return LoadtestRequest(
            name=profile.name,
            profile=profile,
            scenario=scenario,
            load_profile=resolve_load_profile(profile.tests.load_profile),
            metrics_gate=MetricsGate(required_metrics=self._gate_required_metrics(profile)),
        )

    def bootstrap_loadtest(
        self,
        profile: Profile,
        request: LoadtestRequest,
        run_dir: Path,
    ) -> LoadtestBootstrapContext:
        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)
        mockk8s_manager = self._create_mockk8s_manager(profile)
        control_plane_manager = self._create_control_plane_manager(profile)
        prometheus_manager = self._create_prometheus_manager(profile)

        try:
            mockk8s_session = mockk8s_manager.ensure_available(run_dir=run_dir)
        except RuntimeError as exc:
            raise RuntimeError(f"mock kubernetes bootstrap failed: {exc}") from exc

        try:
            control_plane_session = control_plane_manager.ensure_available(
                run_dir=run_dir,
                kubernetes_api_url=mockk8s_session.url,
            )
        except RuntimeError as exc:
            mockk8s_manager.cleanup(mockk8s_session)
            raise RuntimeError(f"control-plane bootstrap failed: {exc}") from exc

        scrape_target = getattr(control_plane_session, "prometheus_scrape_target", None)
        if scrape_target and scrape_target.strip():
            prometheus_manager.scrape_target = scrape_target.strip()
        try:
            prometheus_session = prometheus_manager.ensure_available(run_dir=run_dir)
        except RuntimeError as exc:
            control_plane_manager.cleanup(control_plane_session)
            mockk8s_manager.cleanup(mockk8s_session)
            raise RuntimeError(f"prometheus bootstrap failed: {exc}") from exc

        target_url = control_plane_session.base_url.rstrip("/")
        target_functions = self._resolve_loadtest_targets(request)
        try:
            for target_function in target_functions:
                sut_preflight = self._create_sut_preflight_for_target(
                    profile,
                    target_url,
                    target_function,
                )
                sut_preflight.ensure_fixture()
        except RuntimeError as exc:
            prometheus_manager.cleanup(prometheus_session)
            control_plane_manager.cleanup(control_plane_session)
            mockk8s_manager.cleanup(mockk8s_session)
            raise RuntimeError(f"sut preflight failed: {exc}") from exc

        scenario_manifest_path = write_scenario_manifest(request.scenario, root=run_dir / "scenario")
        return LoadtestBootstrapContext(
            base_url=target_url,
            prometheus_url=prometheus_session.url,
            scenario_manifest_path=scenario_manifest_path,
            target_functions=target_functions,
            target_results=[],
            started_at=datetime.now(timezone.utc),
            mockk8s_manager=mockk8s_manager,
            control_plane_manager=control_plane_manager,
            prometheus_manager=prometheus_manager,
            mockk8s_session=mockk8s_session,
            control_plane_session=control_plane_session,
            prometheus_session=prometheus_session,
        )

    def cleanup_loadtest(self, context: LoadtestBootstrapContext) -> None:
        if context.prometheus_session is not None:
            context.prometheus_manager.cleanup(context.prometheus_session)
        if context.control_plane_session is not None:
            context.control_plane_manager.cleanup(context.control_plane_session)
        if context.mockk8s_session is not None:
            context.mockk8s_manager.cleanup(context.mockk8s_session)

    def run_loadtest_k6(
        self,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]:
        k6_script = self.repo_root / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
        if not k6_script.exists():
            return (True, "k6 skipped")

        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)
        target_results: list[TargetRunResult] = []
        for target_function in context.target_functions:
            target_metrics_dir = metrics_dir / target_function
            target_metrics_dir.mkdir(parents=True, exist_ok=True)
            k6_summary = target_metrics_dir / "k6-summary.json"
            command = [
                "k6",
                "run",
                "--summary-export",
                str(k6_summary),
                *self._k6_stage_args(request.load_profile),
                "-e",
                f"NANOFAAS_URL={context.base_url}",
                "-e",
                f"NANOFAAS_FUNCTION={target_function}",
                "-e",
                f"NANOFAAS_SCENARIO_MANIFEST={context.scenario_manifest_path}",
                str(k6_script),
            ]
            result = self._run(command, run_dir, "test.log")
            target_results.append(
                TargetRunResult(
                    function_key=target_function,
                    k6_summary_path=k6_summary,
                    status="passed" if result.ok else "failed",
                    detail=result.detail,
                )
            )

        context.target_results = target_results
        ok = all(result.status == "passed" for result in target_results)
        detail = "; ".join(
            f"{result.function_key}: {result.detail}" for result in target_results
        )
        return (ok, f"k6: {detail}")

    def evaluate_metrics_gate(
        self,
        profile: Profile,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]:
        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)

        started_at = context.started_at
        ended_at = datetime.now(timezone.utc)
        if ended_at <= started_at:
            ended_at = started_at + timedelta(seconds=max(1, request.load_profile.summary_window_seconds))

        configured_required_metrics = list(profile.metrics.required)
        gate_required_metrics = (
            list(request.metrics_gate.required_metrics)
            if request.metrics_gate.required_metrics
            else self._gate_required_metrics(profile)
        )
        query_metrics = sorted(set(configured_required_metrics) | set(gate_required_metrics))

        observed_run_metrics: set[str] = set()
        series: dict[str, list[dict[str, float | str]]] = {}
        try:
            for metric in query_metrics:
                points = self._query_series_with_aliases(
                    base_url=context.prometheus_url,
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
            available_metrics = query_prometheus_metric_names(context.prometheus_url)
        except RuntimeError as exc:
            return (False, f"prometheus metrics query failed: {exc}")

        (metrics_dir / "series.json").write_text(json.dumps(series, indent=2), encoding="utf-8")
        (metrics_dir / "observed-metrics.json").write_text(
            json.dumps(
                {
                    "source": "prometheus-api",
                    "endpoint": context.prometheus_url,
                    "owned_container": bool(getattr(context.prometheus_session, "owned_container_name", None)),
                    "scenario_manifest": str(context.scenario_manifest_path),
                    "load_profile": request.load_profile.name,
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

        if missing and request.metrics_gate.mode == "off":
            return (True, "metrics gate disabled")
        if missing and request.metrics_gate.mode == "warn":
            return (
                True,
                "metrics gate warning: missing required metrics: " + ", ".join(missing),
            )
        if missing:
            return (
                False,
                "missing required metrics: "
                + ", ".join(missing)
                + " (see metrics/observed-metrics.json)",
            )
        return (True, "prometheus checks passed")

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
        request = self._legacy_loadtest_request(profile)
        try:
            context = self.bootstrap_loadtest(profile, request, run_dir)
        except RuntimeError as exc:
            return (False, str(exc))

        try:
            k6_ok, k6_detail = self.run_loadtest_k6(request, context, run_dir)
            gate_ok, gate_detail = self.evaluate_metrics_gate(profile, request, context, run_dir)
        finally:
            self.cleanup_loadtest(context)

        if not gate_ok:
            return (False, gate_detail)
        if not k6_ok:
            return (False, f"prometheus checks passed, k6 failed: {k6_detail}")
        if "skipped" in k6_detail:
            return (True, "prometheus checks passed; k6 script missing, skipped load")
        return (True, f"prometheus + {k6_detail}")
