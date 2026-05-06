"""
loadtest_bootstrap.py

Loadtest environment lifecycle: bootstrap (start services) and cleanup (stop services).

Extracted from adapters.py (ShellCommandAdapter) to satisfy single-responsibility.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from controlplane_tool.infra.runtimes.control_plane_runtime import (
    ControlPlaneRuntimeManager,
    ControlPlaneSession,
)
from controlplane_tool.loadtest.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest.loadtest_models import (
    LoadtestRequest,
    MetricsGate,
    TargetRunResult,
    effective_required_metrics,
)
from controlplane_tool.infra.runtimes.mockk8s_runtime import MockK8sRuntimeManager, MockK8sSession
from controlplane_tool.core.models import Profile
from controlplane_tool.infra.runtimes.prometheus_runtime import PrometheusRuntimeManager, PrometheusSession
from controlplane_tool.scenario.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario.scenario_manifest import write_scenario_manifest
from controlplane_tool.scenario.scenario_models import ScenarioSpec
from controlplane_tool.sut.sut_preflight import SutPreflight


@dataclass
class LoadtestBootstrapContext:
    base_url: str
    prometheus_url: str
    scenario_manifest_path: Path
    target_functions: list[str]
    target_results: list[TargetRunResult]
    started_at: datetime
    mockk8s_manager: MockK8sRuntimeManager
    control_plane_manager: ControlPlaneRuntimeManager
    prometheus_manager: PrometheusRuntimeManager
    mockk8s_session: MockK8sSession | None
    control_plane_session: ControlPlaneSession | None
    prometheus_session: PrometheusSession | None


def _resolve_prometheus_url(profile: Profile) -> str | None:
    if profile.metrics.prometheus_url and profile.metrics.prometheus_url.strip():
        return profile.metrics.prometheus_url.strip()
    env_url = os.getenv("NANOFAAS_TOOL_PROMETHEUS_URL", "").strip()
    return env_url or None


class LoadtestBootstrap:
    """Manages the lifecycle of the loadtest environment (mockk8s, control-plane, prometheus)."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _create_prometheus_manager(self, profile: Profile) -> PrometheusRuntimeManager:
        return PrometheusRuntimeManager(
            repo_root=self.repo_root,
            preferred_url=_resolve_prometheus_url(profile),
        )

    def _create_mockk8s_manager(self, profile: Profile) -> MockK8sRuntimeManager:  # noqa: ARG002
        return MockK8sRuntimeManager(repo_root=self.repo_root)

    def _create_control_plane_manager(self, profile: Profile) -> ControlPlaneRuntimeManager:  # noqa: ARG002
        return ControlPlaneRuntimeManager(repo_root=self.repo_root)

    def _create_sut_preflight(self, base_url: str, fixture_name: str) -> SutPreflight:
        return SutPreflight(base_url=base_url, fixture_name=fixture_name)

    def _resolve_loadtest_targets(self, request: LoadtestRequest) -> list[str]:
        if request.targets is not None and request.targets.targets:
            return list(request.targets.targets)
        if request.scenario.function_keys:
            return list(request.scenario.function_keys)
        raise ValueError("loadtest request does not define any targets")

    def legacy_loadtest_request(self, profile: Profile) -> LoadtestRequest:
        """Build a LoadtestRequest from the legacy profile-based config."""
        scenario = resolve_scenario_spec(
            ScenarioSpec(
                name=f"{profile.name}-metrics",
                base_scenario=profile.scenario.base_scenario or "k3s-junit-curl",
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
            metrics_gate=MetricsGate(required_metrics=effective_required_metrics(profile)),
        )

    def bootstrap(
        self,
        profile: Profile,
        request: LoadtestRequest,
        run_dir: Path,
    ) -> LoadtestBootstrapContext:
        self._ensure_metrics_dir(run_dir)
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
                self._create_sut_preflight(target_url, target_function).ensure_fixture()
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

    def cleanup(self, context: LoadtestBootstrapContext) -> None:
        if context.prometheus_session is not None:
            context.prometheus_manager.cleanup(context.prometheus_session)
        if context.control_plane_session is not None:
            context.control_plane_manager.cleanup(context.control_plane_session)
        if context.mockk8s_session is not None:
            context.mockk8s_manager.cleanup(context.mockk8s_session)

    def _ensure_metrics_dir(self, run_dir: Path) -> Path:
        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)
        return metrics_dir
