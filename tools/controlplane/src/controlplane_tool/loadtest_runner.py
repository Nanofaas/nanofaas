from __future__ import annotations

from dataclasses import asdict
from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from typing import Callable, Literal

from controlplane_tool.adapters import ShellCommandAdapter
from controlplane_tool.loadtest_models import LoadtestRequest, TargetRunResult
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.report import render_report
from controlplane_tool.run_models import RunResult, StepResult

LoadtestStepStatus = Literal["running", "passed", "failed"]


@dataclass(frozen=True)
class LoadtestStepEvent:
    step_name: str
    status: LoadtestStepStatus
    detail: str = ""


class LoadtestRunner:
    def __init__(self, adapter: object | None = None) -> None:
        self.adapter = adapter or ShellCommandAdapter()

    def _emit_event(
        self,
        event_listener: Callable[[LoadtestStepEvent], None] | None,
        *,
        step_name: str,
        status: LoadtestStepStatus,
        detail: str = "",
    ) -> None:
        if event_listener is None:
            return
        event_listener(LoadtestStepEvent(step_name=step_name, status=status, detail=detail))

    def run(
        self,
        request: LoadtestRequest,
        runs_root: Path | None = None,
        *,
        event_listener: Callable[[LoadtestStepEvent], None] | None = None,
    ) -> RunResult:
        root = runs_root or default_tool_paths().runs_dir
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        run_dir = root / f"{timestamp}-{request.name}"
        run_dir.mkdir(parents=True, exist_ok=True)

        steps: list[StepResult] = []
        self._emit_event(event_listener, step_name="preflight", status="running")
        missing = self.adapter.preflight(request.profile)
        if missing:
            steps.append(
                StepResult(
                    name="preflight",
                    status="failed",
                    detail=f"missing tools: {', '.join(missing)}",
                    duration_ms=0,
                )
            )
            self._emit_event(
                event_listener,
                step_name="preflight",
                status="failed",
                detail=f"missing tools: {', '.join(missing)}",
            )
            self._emit_event(event_listener, step_name="report", status="running")
            report_step = self._report_step(run_dir, request, steps, final_status="failed")
            steps.append(report_step)
            self._emit_event(
                event_listener,
                step_name="report",
                status="passed",
                detail=report_step.detail,
            )
            return RunResult(
                profile_name=request.profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )

        steps.append(StepResult(name="preflight", status="passed", detail="ok", duration_ms=0))
        self._emit_event(event_listener, step_name="preflight", status="passed", detail="ok")

        bootstrap_start = time.time()
        self._emit_event(event_listener, step_name="bootstrap", status="running")
        try:
            context = self.adapter.bootstrap_loadtest(request.profile, request, run_dir)
            bootstrap_step = StepResult(
                name="bootstrap",
                status="passed",
                detail="loadtest environment ready",
                duration_ms=int((time.time() - bootstrap_start) * 1000),
            )
        except RuntimeError as exc:
            bootstrap_step = StepResult(
                name="bootstrap",
                status="failed",
                detail=str(exc),
                duration_ms=int((time.time() - bootstrap_start) * 1000),
            )
            steps.append(bootstrap_step)
            self._emit_event(
                event_listener,
                step_name="bootstrap",
                status="failed",
                detail=bootstrap_step.detail,
            )
            self._emit_event(event_listener, step_name="report", status="running")
            report_step = self._report_step(run_dir, request, steps, final_status="failed")
            steps.append(report_step)
            self._emit_event(
                event_listener,
                step_name="report",
                status="passed",
                detail=report_step.detail,
            )
            return RunResult(
                profile_name=request.profile.name,
                run_dir=run_dir,
                final_status="failed",
                steps=steps,
            )

        steps.append(bootstrap_step)
        self._emit_event(
            event_listener,
            step_name="bootstrap",
            status="passed",
            detail=bootstrap_step.detail,
        )

        target_results: list[TargetRunResult] = []
        try:
            load_step = self._run_step(
                "load_k6",
                self.adapter.run_loadtest_k6,
                request,
                context,
                run_dir,
                event_listener=event_listener,
            )
            steps.append(load_step)
            target_results = list(getattr(context, "target_results", []))
            gate_step = self._run_step(
                "metrics_gate",
                self.adapter.evaluate_metrics_gate,
                request.profile,
                request,
                context,
                run_dir,
                event_listener=event_listener,
            )
            steps.append(gate_step)
        finally:
            self.adapter.cleanup_loadtest(context)

        final_status = "failed" if any(step.status == "failed" for step in steps) else "passed"
        self._emit_event(event_listener, step_name="report", status="running")
        report_step = self._report_step(
            run_dir,
            request,
            steps,
            final_status=final_status,
            target_results=target_results,
        )
        steps.append(report_step)
        self._emit_event(
            event_listener,
            step_name="report",
            status="passed",
            detail=report_step.detail,
        )
        return RunResult(
            profile_name=request.profile.name,
            run_dir=run_dir,
            final_status=final_status,
            steps=steps,
        )

    def _run_step(
        self,
        name: str,
        fn,
        *args,
        event_listener: Callable[[LoadtestStepEvent], None] | None = None,
    ) -> StepResult:  # noqa: ANN001
        start = time.time()
        self._emit_event(event_listener, step_name=name, status="running")
        ok, detail = fn(*args)
        duration_ms = int((time.time() - start) * 1000)
        step = StepResult(
            name=name,
            status="passed" if ok else "failed",
            detail=detail,
            duration_ms=duration_ms,
        )
        self._emit_event(
            event_listener,
            step_name=name,
            status="passed" if ok else "failed",
            detail=detail,
        )
        return step

    def _summary_payload(
        self,
        request: LoadtestRequest,
        run_dir: Path,
        steps: list[StepResult],
        *,
        final_status: str,
        target_results: list[TargetRunResult] | None = None,
    ) -> dict[str, object]:
        metrics_path = run_dir / "metrics" / "series.json"
        if metrics_path.exists():
            try:
                metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                metrics = {}
        else:
            metrics = {}

        return {
            "profile_name": request.profile.name,
            "run_dir": str(run_dir),
            "final_status": final_status,
            "steps": [asdict(step) for step in steps],
            "metrics": metrics,
            "loadtest": {
                "name": request.name,
                "scenario": request.scenario.name,
                "load_profile": request.load_profile.name,
                "targets": list(request.targets.targets if request.targets is not None else []),
                "target_results": [
                    result.model_dump(mode="json")
                    for result in (target_results or [])
                ],
            },
        }

    def _report_step(
        self,
        run_dir: Path,
        request: LoadtestRequest,
        steps: list[StepResult],
        *,
        final_status: str,
        target_results: list[TargetRunResult] | None = None,
    ) -> StepResult:
        start = time.time()
        summary = self._summary_payload(
            request,
            run_dir,
            steps,
            final_status=final_status,
            target_results=target_results,
        )
        (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        render_report(summary=summary, output_dir=run_dir)
        targets = ", ".join(request.targets.targets if request.targets is not None else [])
        detail = "summary.json + report.html"
        if targets:
            detail += f" (targets: {targets})"
        return StepResult(
            name="report",
            status="passed",
            detail=detail,
            duration_ms=int((time.time() - start) * 1000),
        )
