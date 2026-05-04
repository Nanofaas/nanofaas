"""
adapters.py

Facade: ShellCommandAdapter composes GradleOps, K6Ops, LoadtestBootstrap, and
evaluate_metrics_gate into the single interface consumed by PipelineRunner and
LoadtestRunner.

This module exists purely for backwards-compatibility of the public class name.
All logic lives in the dedicated ops/bootstrap/gate modules.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.gradle_ops import CommandResult as AdapterResult
from controlplane_tool.gradle_ops import GradleOps
from controlplane_tool.k6_ops import K6Ops
from controlplane_tool.loadtest_bootstrap import LoadtestBootstrap, LoadtestBootstrapContext
from controlplane_tool.loadtest_models import LoadtestRequest
from controlplane_tool.metrics_gate import evaluate_metrics_gate
from controlplane_tool.models import Profile
from controlplane_tool.paths import default_tool_paths


class ShellCommandAdapter:
    def __init__(self, repo_root: Path | None = None) -> None:
        root = Path(repo_root) if repo_root else default_tool_paths().workspace_root
        self._gradle = GradleOps(root)
        self._k6 = K6Ops(root)
        self._bootstrap = LoadtestBootstrap(root)

    # ------------------------------------------------------------------
    # Gradle / build / test delegation
    # ------------------------------------------------------------------

    def preflight(self, profile: Profile) -> list[str]:
        return self._gradle.preflight_missing(profile)

    def compile(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return self._gradle.compile(profile, run_dir)

    def build_image(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return self._gradle.build_image(profile, run_dir)

    def run_api_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return self._gradle.run_api_tests(profile, run_dir)

    def run_mockk8s_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        return self._gradle.run_mockk8s_tests(profile, run_dir)

    # ------------------------------------------------------------------
    # Loadtest lifecycle delegation
    # ------------------------------------------------------------------

    def bootstrap_loadtest(
        self,
        profile: Profile,
        request: LoadtestRequest,
        run_dir: Path,
    ) -> LoadtestBootstrapContext:
        return self._bootstrap.bootstrap(profile, request, run_dir)

    def cleanup_loadtest(self, context: LoadtestBootstrapContext) -> None:
        self._bootstrap.cleanup(context)

    # ------------------------------------------------------------------
    # k6 + metrics gate delegation
    # ------------------------------------------------------------------

    def run_loadtest_k6(
        self,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]:
        return self._k6.run_loadtest_k6(request, context, run_dir)

    def evaluate_metrics_gate(
        self,
        profile: Profile,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]:
        return evaluate_metrics_gate(profile, request, context, run_dir)

    # ------------------------------------------------------------------
    # Legacy combined method (used by old pipeline path in pipeline.py)
    # ------------------------------------------------------------------

    def run_metrics_tests(self, profile: Profile, run_dir: Path) -> tuple[bool, str]:
        result_ok, result_detail = self._gradle.run_api_tests(profile, run_dir)
        if not result_ok:
            return (False, result_detail)
        request = self._bootstrap.legacy_loadtest_request(profile)
        try:
            context = self._bootstrap.bootstrap(profile, request, run_dir)
        except RuntimeError as exc:
            return (False, str(exc))

        try:
            k6_ok, k6_detail = self._k6.run_loadtest_k6(request, context, run_dir)
            gate_ok, gate_detail = evaluate_metrics_gate(profile, request, context, run_dir)
        finally:
            self._bootstrap.cleanup(context)

        if not gate_ok:
            return (False, gate_detail)
        if not k6_ok:
            return (False, f"prometheus checks passed, k6 failed: {k6_detail}")
        if "skipped" in k6_detail:
            return (True, "prometheus checks passed; k6 script missing, skipped load")
        return (True, f"prometheus + {k6_detail}")
