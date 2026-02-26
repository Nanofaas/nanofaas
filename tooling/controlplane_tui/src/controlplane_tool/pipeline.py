from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import time

from controlplane_tool.adapters import ShellCommandAdapter
from controlplane_tool.models import Profile
from controlplane_tool.report import render_report


@dataclass(frozen=True)
class StepResult:
    name: str
    status: str
    detail: str
    duration_ms: int


@dataclass(frozen=True)
class RunResult:
    profile_name: str
    run_dir: Path
    final_status: str
    steps: list[StepResult]


class PipelineRunner:
    def __init__(self, adapter: object | None = None) -> None:
        self.adapter = adapter or ShellCommandAdapter()

    def run(self, profile: Profile, runs_root: Path | None = None) -> RunResult:
        root = runs_root or Path("tooling/runs")
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
            steps.append(
                self._run_step(
                    "test_metrics_prometheus_k6",
                    self.adapter.run_metrics_tests,
                    profile,
                    run_dir,
                )
            )
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

    def _summary_payload(self, result: RunResult) -> dict[str, object]:
        return {
            "profile_name": result.profile_name,
            "run_dir": str(result.run_dir),
            "final_status": result.final_status,
            "steps": [asdict(step) for step in result.steps],
            "metrics": {},
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
