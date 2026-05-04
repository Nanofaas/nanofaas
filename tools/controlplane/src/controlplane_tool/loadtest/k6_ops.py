"""
k6_ops.py

k6 load-test execution helpers.

Extracted from adapters.py (ShellCommandAdapter) to satisfy single-responsibility.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.building.gradle_ops import CommandResult, run_logged
from controlplane_tool.loadtest.loadtest_models import LoadProfileDefinition, LoadtestRequest


class K6Ops:
    """Runs k6 load tests and collects per-function results."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _run(self, command: list[str], run_dir: Path, log_name: str) -> CommandResult:
        return run_logged(command, run_dir, log_name, repo_root=self.repo_root)

    def _k6_stage_args(self, load_profile: LoadProfileDefinition) -> list[str]:
        args: list[str] = []
        for stage in load_profile.stages:
            args.extend(["--stage", f"{stage.duration}:{stage.target}"])
        return args

    def _build_target_command(
        self,
        *,
        request: LoadtestRequest,
        context: object,
        target_function: str,
        k6_script: Path,
        k6_summary: Path,
    ) -> list[str]:
        return [
            "k6",
            "run",
            "--summary-export",
            str(k6_summary),
            *self._k6_stage_args(request.load_profile),
            "-e",
            f"NANOFAAS_URL={context.base_url}",  # type: ignore[union-attr]
            "-e",
            f"NANOFAAS_FUNCTION={target_function}",
            "-e",
            f"NANOFAAS_SCENARIO_MANIFEST={context.scenario_manifest_path}",  # type: ignore[union-attr]
            str(k6_script),
        ]

    def run_loadtest_k6(
        self,
        request: LoadtestRequest,
        context: object,
        run_dir: Path,
    ) -> tuple[bool, str]:
        from controlplane_tool.loadtest.loadtest_models import TargetRunResult

        k6_script = (
            self.repo_root / "tools" / "controlplane" / "assets" / "k6" / "tool-metrics-echo.js"
        )
        if not k6_script.exists():
            return (True, "k6 skipped")

        metrics_dir = run_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)
        target_results: list[TargetRunResult] = []
        for target_function in context.target_functions:  # type: ignore[union-attr]
            target_metrics_dir = metrics_dir / target_function
            target_metrics_dir.mkdir(parents=True, exist_ok=True)
            k6_summary = target_metrics_dir / "k6-summary.json"
            command = self._build_target_command(
                request=request,
                context=context,
                target_function=target_function,
                k6_script=k6_script,
                k6_summary=k6_summary,
            )
            result = self._run(command, run_dir, "test.log")
            target_results.append(
                TargetRunResult(
                    function_key=target_function,
                    k6_summary_path=k6_summary,
                    status="passed" if result.ok else "failed",
                    detail=result.detail,
                )
            )

        context.target_results = target_results  # type: ignore[union-attr]
        ok = all(r.status == "passed" for r in target_results)
        detail = "; ".join(f"{r.function_key}: {r.detail}" for r in target_results)
        return (ok, f"k6: {detail}")
