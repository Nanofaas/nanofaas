from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
import json
from pathlib import Path
import time

from controlplane_tool.adapters import ShellCommandAdapter
from controlplane_tool.loadtest_catalog import resolve_load_profile
from controlplane_tool.loadtest_models import LoadtestRequest, MetricsGate
from controlplane_tool.loadtest_runner import LoadtestRunner
from controlplane_tool.models import Profile
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.report import render_report
from controlplane_tool.run_models import RunResult, StepResult
from controlplane_tool.scenario_loader import load_scenario_file, resolve_scenario_spec
from controlplane_tool.scenario_models import ScenarioSpec


class PipelineRunner:
    def __init__(self, adapter: object | None = None) -> None:
        self.adapter = adapter or ShellCommandAdapter()

    def _loadtest_request(self, profile: Profile) -> LoadtestRequest:
        if profile.scenario.scenario_file:
            scenario = load_scenario_file(Path(profile.scenario.scenario_file))
        else:
            scenario = resolve_scenario_spec(
                ScenarioSpec(
                    name=f"{profile.name}-loadtest",
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
            metrics_gate=MetricsGate(
                mode=profile.loadtest.metrics_gate_mode,
                required_metrics=list(profile.metrics.required),
            ),
        )

    def _run_loadtest_flow(self, profile: Profile, run_dir: Path) -> StepResult:
        if not hasattr(self.adapter, "bootstrap_loadtest") and hasattr(
            self.adapter, "run_metrics_tests"
        ):
            return self._run_step(
                "test_metrics_prometheus_k6",
                self.adapter.run_metrics_tests,
                profile,
                run_dir,
            )

        start = time.time()
        loadtest_result = LoadtestRunner(adapter=self.adapter).run(
            self._loadtest_request(profile),
            runs_root=run_dir.parent,
        )
        duration_ms = int((time.time() - start) * 1000)
        return StepResult(
            name="test_metrics_prometheus_k6",
            status="passed" if loadtest_result.final_status == "passed" else "failed",
            detail=f"loadtest runner: {loadtest_result.final_status} ({loadtest_result.run_dir})",
            duration_ms=duration_ms,
        )

    def run(self, profile: Profile, runs_root: Path | None = None) -> RunResult:
        root = runs_root or default_tool_paths().runs_dir
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        run_dir = root / f"{timestamp}-{profile.name}"
        run_dir.mkdir(parents=True, exist_ok=True)

        steps: list[StepResult] = []

        missing = self.adapter.preflight(profile)
        if missing:
            steps.append(
                StepResult(
                    name="preflight",
                    status="failed",
                    detail=f"missing tools: {', '.join(missing)}",
                    duration_ms=0,
                )
            )
            result = RunResult(
                profile_name=profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )
            return self._finalize(result)

        steps.append(StepResult(name="preflight", status="passed", detail="ok", duration_ms=0))

        compile_step = self._run_step("compile", self.adapter.compile, profile, run_dir)
        steps.append(compile_step)
        if compile_step.status == "failed":
            result = RunResult(
                profile_name=profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )
            return self._finalize(result)

        image_step = self._run_step("docker_image", self.adapter.build_image, profile, run_dir)
        steps.append(image_step)
        if image_step.status == "failed":
            result = RunResult(
                profile_name=profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )
            return self._finalize(result)

        if profile.tests.enabled and profile.tests.api:
            steps.append(self._run_step("test_api", self.adapter.run_api_tests, profile, run_dir))
        else:
            steps.append(
                StepResult(name="test_api", status="skipped", detail="disabled", duration_ms=0)
            )

        if profile.tests.enabled and profile.tests.e2e_mockk8s:
            steps.append(
                self._run_step(
                    "test_e2e_mockk8s", self.adapter.run_mockk8s_tests, profile, run_dir
                )
            )
        else:
            steps.append(
                StepResult(
                    name="test_e2e_mockk8s",
                    status="skipped",
                    detail="disabled",
                    duration_ms=0,
                )
            )

        if profile.tests.enabled and profile.tests.metrics:
            steps.append(self._run_loadtest_flow(profile, run_dir))
        else:
            steps.append(
                StepResult(
                    name="test_metrics_prometheus_k6",
                    status="skipped",
                    detail="disabled",
                    duration_ms=0,
                )
            )

        final_status = (
            "failed" if any(step.status == "failed" for step in steps) else "passed"
        )

        result = RunResult(
            profile_name=profile.name,
            run_dir=run_dir,
            final_status=final_status,
            steps=steps,
        )
        return self._finalize(result)

    def _run_step(self, name: str, fn: object, profile: Profile, run_dir: Path) -> StepResult:
        start = time.time()
        ok, detail = fn(profile, run_dir)
        duration_ms = int((time.time() - start) * 1000)
        return StepResult(
            name=name,
            status="passed" if ok else "failed",
            detail=detail,
            duration_ms=duration_ms,
        )

    def _load_metric_series(self, run_dir: Path) -> dict[str, object]:
        series_path = run_dir / "metrics" / "series.json"
        if not series_path.exists():
            return {}
        try:
            return json.loads(series_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return {}

    def _summary_payload(self, result: RunResult) -> dict[str, object]:
        return {
            "profile_name": result.profile_name,
            "run_dir": str(result.run_dir),
            "final_status": result.final_status,
            "steps": [asdict(step) for step in result.steps],
            "metrics": self._load_metric_series(result.run_dir),
        }

    def _write_summary(self, result: RunResult) -> dict[str, object]:
        payload = self._summary_payload(result)
        (result.run_dir / "summary.json").write_text(
            json.dumps(payload, indent=2), encoding="utf-8"
        )
        return payload

    def _finalize(self, result: RunResult) -> RunResult:
        payload = self._write_summary(result)
        render_report(summary=payload, output_dir=result.run_dir)
        return result


def execute_pipeline(
    profile: Profile,
    runner: PipelineRunner | None = None,
    runs_root: Path | None = None,
) -> RunResult:
    active_runner = runner or PipelineRunner()
    if runs_root is None:
        return active_runner.run(profile)
    return active_runner.run(profile, runs_root=runs_root)
