from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import sys
import time
from typing import Literal, Protocol

from controlplane_tool.core.models import Profile
from controlplane_tool.loadtest.loadtest_bootstrap import LoadtestBootstrapContext
from controlplane_tool.loadtest.loadtest_models import LoadtestRequest, TargetRunResult
from controlplane_tool.loadtest.report import render_report
from controlplane_tool.core.run_models import StepResult
from workflow_tasks.tasks.models import CommandTaskSpec

LoadtestStepStatus = Literal["running", "passed", "failed"]

_LOADTEST_CLI_MODULE = "controlplane_tool.app.main"
_LOADTEST_STEP_TASK_IDS = {
    "preflight": "loadtest.bootstrap",
    "bootstrap": "loadtest.bootstrap",
    "load_k6": "loadtest.execute_k6",
    "metrics_gate": "metrics.evaluate_gate",
    "report": "loadtest.write_report",
}


@dataclass(frozen=True)
class LoadtestStepEvent:
    step_name: str
    status: LoadtestStepStatus
    detail: str = ""


@dataclass(frozen=True)
class BootstrapTaskResult:
    context: LoadtestBootstrapContext | None
    steps: list[StepResult]


class LoadtestAdapter(Protocol):
    def preflight(self, profile: Profile) -> list[str]: ...

    def bootstrap_loadtest(
        self,
        profile: Profile,
        request: LoadtestRequest,
        run_dir: Path,
    ) -> LoadtestBootstrapContext: ...

    def run_loadtest_k6(
        self,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]: ...

    def evaluate_metrics_gate(
        self,
        profile: Profile,
        request: LoadtestRequest,
        context: LoadtestBootstrapContext,
        run_dir: Path,
    ) -> tuple[bool, str]: ...

    def cleanup_loadtest(self, context: LoadtestBootstrapContext) -> None: ...


def loadtest_step_spec(step_name: str, summary: str) -> CommandTaskSpec:
    return CommandTaskSpec(
        task_id=_LOADTEST_STEP_TASK_IDS.get(step_name, f"loadtest.{step_name}"),
        summary=summary,
        argv=(sys.executable, "-m", _LOADTEST_CLI_MODULE, "loadtest", "run", "--dry-run"),
    )


def emit_loadtest_event(
    event_listener: Callable[[LoadtestStepEvent], None] | None,
    *,
    step_name: str,
    status: LoadtestStepStatus,
    detail: str = "",
) -> None:
    if event_listener is None:
        return
    event_listener(LoadtestStepEvent(step_name=step_name, status=status, detail=detail))


def bootstrap_loadtest_task(
    *,
    adapter: LoadtestAdapter,
    request: LoadtestRequest,
    run_dir: Path,
    event_listener: Callable[[LoadtestStepEvent], None] | None = None,
) -> BootstrapTaskResult:
    steps: list[StepResult] = []

    emit_loadtest_event(event_listener, step_name="preflight", status="running")
    missing = adapter.preflight(request.profile)
    if missing:
        detail = f"missing tools: {', '.join(missing)}"
        step = StepResult(name="preflight", status="failed", detail=detail, duration_ms=0)
        emit_loadtest_event(event_listener, step_name="preflight", status="failed", detail=detail)
        return BootstrapTaskResult(context=None, steps=[step])

    preflight_step = StepResult(name="preflight", status="passed", detail="ok", duration_ms=0)
    steps.append(preflight_step)
    emit_loadtest_event(event_listener, step_name="preflight", status="passed", detail="ok")

    start = time.time()
    emit_loadtest_event(event_listener, step_name="bootstrap", status="running")
    try:
        context = adapter.bootstrap_loadtest(request.profile, request, run_dir)
    except RuntimeError as exc:
        step = StepResult(
            name="bootstrap",
            status="failed",
            detail=str(exc),
            duration_ms=int((time.time() - start) * 1000),
        )
        steps.append(step)
        emit_loadtest_event(
            event_listener,
            step_name="bootstrap",
            status="failed",
            detail=step.detail,
        )
        return BootstrapTaskResult(context=None, steps=steps)

    step = StepResult(
        name="bootstrap",
        status="passed",
        detail="loadtest environment ready",
        duration_ms=int((time.time() - start) * 1000),
    )
    steps.append(step)
    emit_loadtest_event(
        event_listener,
        step_name="bootstrap",
        status="passed",
        detail=step.detail,
    )
    return BootstrapTaskResult(context=context, steps=steps)


def run_loadtest_step_task(
    name: str,
    fn,
    *args,
    event_listener: Callable[[LoadtestStepEvent], None] | None = None,
) -> StepResult:  # noqa: ANN001
    start = time.time()
    emit_loadtest_event(event_listener, step_name=name, status="running")
    ok, detail = fn(*args)
    step = StepResult(
        name=name,
        status="passed" if ok else "failed",
        detail=detail,
        duration_ms=int((time.time() - start) * 1000),
    )
    emit_loadtest_event(
        event_listener,
        step_name=name,
        status="passed" if ok else "failed",
        detail=detail,
    )
    return step


def _summary_payload(
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
            "execution_description": request.execution_description,
            "targets": list(request.targets.targets if request.targets is not None else []),
            "target_results": [
                result.model_dump(mode="json")
                for result in (target_results or [])
            ],
        },
    }


def write_loadtest_report_task(
    *,
    request: LoadtestRequest,
    run_dir: Path,
    steps: list[StepResult],
    final_status: str,
    target_results: list[TargetRunResult] | None = None,
    event_listener: Callable[[LoadtestStepEvent], None] | None = None,
) -> StepResult:
    start = time.time()
    emit_loadtest_event(event_listener, step_name="report", status="running")
    summary = _summary_payload(
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
    step = StepResult(
        name="report",
        status="passed",
        detail=detail,
        duration_ms=int((time.time() - start) * 1000),
    )
    emit_loadtest_event(event_listener, step_name="report", status="passed", detail=detail)
    return step
