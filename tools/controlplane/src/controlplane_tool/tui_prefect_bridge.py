from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Literal

from controlplane_tool.workflow_models import WorkflowEvent

WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


@dataclass
class TuiPhaseSnapshot:
    label: str
    task_id: str | None = None
    status: WorkflowState = "pending"
    detail: str = ""
    started_at: float | None = None
    finished_at: float | None = None


@dataclass
class TuiWorkflowSnapshot:
    phases: list[TuiPhaseSnapshot]
    logs: list[str]
    show_logs: bool


class TuiPrefectBridge:
    def __init__(
        self,
        *,
        planned_steps: list[str] | None = None,
        log_limit: int = 200,
    ) -> None:
        self.log_limit = log_limit
        self._phases: list[TuiPhaseSnapshot] = []
        self._phase_keys: dict[str, int] = {}
        self._logs: list[str] = []
        self._show_logs = True
        for label in planned_steps or []:
            self.upsert_phase(label)

    def snapshot(self) -> TuiWorkflowSnapshot:
        return TuiWorkflowSnapshot(
            phases=[
                TuiPhaseSnapshot(
                    label=phase.label,
                    task_id=phase.task_id,
                    status=phase.status,
                    detail=phase.detail,
                    started_at=phase.started_at,
                    finished_at=phase.finished_at,
                )
                for phase in self._phases
            ],
            logs=list(self._logs),
            show_logs=self._show_logs,
        )

    def toggle_logs(self) -> None:
        self._show_logs = not self._show_logs

    def append_log(self, message: str) -> None:
        self._logs.append(message)
        if len(self._logs) > self.log_limit:
            self._logs = self._logs[-self.log_limit :]

    def upsert_phase(
        self,
        label: str,
        *,
        task_id: str | None = None,
        detail: str = "",
        activate: bool = False,
    ) -> int:
        key = self._resolve_key(label, task_id)
        index = self._phase_keys.get(key)
        if index is None and task_id is not None:
            index = self._find_title_placeholder(label)
            if index is not None:
                self._phase_keys[key] = index
                self._phases[index].task_id = task_id
        if index is None:
            self._phases.append(TuiPhaseSnapshot(label=label, task_id=task_id, detail=detail))
            index = len(self._phases) - 1
            self._phase_keys[key] = index
        phase = self._phases[index]
        if label and (not phase.label or phase.label == phase.task_id):
            phase.label = label
        if task_id and not phase.task_id:
            phase.task_id = task_id
        if detail:
            phase.detail = detail
        if activate:
            self.mark_phase_running(index + 1)
        return index + 1

    def mark_phase_running(self, phase_index: int) -> None:
        phase = self._phases[phase_index - 1]
        for existing in self._phases:
            if existing is phase or existing.status != "running":
                continue
            existing.status = "success"
            existing.finished_at = existing.finished_at or time.time()
        if phase.status != "running":
            phase.status = "running"
        phase.started_at = phase.started_at or time.time()

    def mark_phase_success(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        phase.status = "success"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()

    def mark_phase_failed(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        phase.status = "failed"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()

    def mark_phase_cancelled(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        phase.status = "cancelled"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()

    def complete_running_phases(
        self,
        *,
        status: WorkflowState = "success",
        detail: str = "",
    ) -> None:
        for phase in self._phases:
            if phase.status != "running":
                continue
            phase.status = status
            if detail:
                phase.detail = detail
            phase.finished_at = time.time()

    def handle_event(self, event: WorkflowEvent) -> None:
        if event.kind == "log.line":
            if event.task_id and (event.title or self._has_phase_for_task(event.task_id)):
                self.upsert_phase(event.title or event.task_id, task_id=event.task_id)
            prefix = "stderr │ " if event.stream == "stderr" else ""
            self.append_log(f"{prefix}{event.line}")
            return
        if event.kind == "phase.started":
            self.upsert_phase(event.title or "Phase", detail=event.detail, activate=True)
            self.append_log(f"[phase] {event.title or 'Phase'}")
            return
        if event.kind == "task.pending":
            self.upsert_phase(event.title or event.task_id or "Task", task_id=event.task_id, detail=event.detail)
            return
        if event.kind == "task.running":
            self.append_log(
                f"[step] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase_index = self.upsert_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
                activate=True,
            )
            self.mark_phase_running(phase_index)
            return
        if event.kind == "task.completed":
            self.append_log(
                f"[ok] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase_index = self.upsert_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
            )
            self.mark_phase_success(phase_index, detail=event.detail)
            return
        if event.kind == "task.failed":
            self.append_log(
                f"[fail] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase_index = self.upsert_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
            )
            self.mark_phase_failed(phase_index, detail=event.detail)
            return
        if event.kind == "task.cancelled":
            self.append_log(
                f"[cancel] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase_index = self.upsert_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
            )
            self.mark_phase_cancelled(phase_index, detail=event.detail)
            return
        if event.kind == "task.updated":
            self.append_log(
                f"[update] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase_index = self.upsert_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
            )
            self.mark_phase_running(phase_index)
            return
        if event.kind == "task.warning":
            self.append_log(f"[warn] {event.title or event.task_id or 'Task'}")
            self.upsert_phase(event.title or event.task_id or "Task", task_id=event.task_id)
            return
        if event.kind == "task.skipped":
            self.append_log(f"[skip] {event.title or event.task_id or 'Task'}")
            self.upsert_phase(event.title or event.task_id or "Task", task_id=event.task_id)

    def _resolve_key(self, label: str, task_id: str | None) -> str:
        if task_id:
            return f"task:{task_id}"
        return f"title:{label}"

    def _find_title_placeholder(self, label: str) -> int | None:
        key = f"title:{label}"
        return self._phase_keys.get(key)

    def _has_phase_for_task(self, task_id: str) -> bool:
        return self._resolve_key("", task_id) in self._phase_keys
