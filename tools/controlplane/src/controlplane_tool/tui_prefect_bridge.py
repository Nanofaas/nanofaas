from __future__ import annotations

import time

from controlplane_tool.workflow_models import (
    TuiPhaseSnapshot,
    TuiWorkflowSnapshot,
    WorkflowEvent,
    WorkflowState,
)


class TuiPrefectBridge:
    def __init__(
        self,
        *,
        planned_steps: list[str] | None = None,
        log_limit: int = 200,
    ) -> None:
        self.log_limit = log_limit
        self._phases: list[TuiPhaseSnapshot] = []
        self._phase_by_task_id: dict[str, TuiPhaseSnapshot] = {}
        self._logs: list[str] = []
        self._show_logs = True
        for label in planned_steps or []:
            self._phases.append(TuiPhaseSnapshot(label=label))

    def snapshot(self) -> TuiWorkflowSnapshot:
        return TuiWorkflowSnapshot(
            phases=[self._clone_phase(phase) for phase in self._phases],
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
        phase = self._upsert_top_level_phase(label, task_id=task_id, detail=detail)
        if activate:
            self._mark_phase_running(phase)
        return self._phases.index(phase) + 1

    def mark_phase_running(self, phase_index: int) -> None:
        phase = self._phases[phase_index - 1]
        self._mark_phase_running(phase)

    def mark_phase_success(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        self._mark_phase_success(phase, detail=detail)

    def mark_phase_failed(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        self._mark_phase_failed(phase, detail=detail)

    def mark_phase_cancelled(self, phase_index: int, detail: str = "") -> None:
        phase = self._phases[phase_index - 1]
        self._mark_phase_cancelled(phase, detail=detail)

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
            if event.task_id:
                self._phase_for_event(event)
            prefix = "stderr │ " if event.stream == "stderr" else ""
            self.append_log(f"{prefix}{event.line}")
            return
        if event.kind == "phase.started":
            self.upsert_phase(event.title or "Phase", detail=event.detail, activate=True)
            self.append_log(f"[phase] {event.title or 'Phase'}")
            return
        if event.kind == "task.pending":
            self._phase_for_event(event)
            return
        if event.kind == "task.running":
            self.append_log(
                f"[step] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase = self._phase_for_event(event)
            if phase is not None:
                self._mark_phase_running(phase)
            return
        if event.kind == "task.completed":
            self.append_log(
                f"[ok] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase = self._phase_for_event(event)
            if phase is not None:
                self._mark_phase_success(phase, detail=event.detail)
            return
        if event.kind == "task.failed":
            self.append_log(
                f"[fail] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase = self._phase_for_event(event)
            if phase is not None:
                self._mark_phase_failed(phase, detail=event.detail)
            return
        if event.kind == "task.cancelled":
            self.append_log(
                f"[cancel] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase = self._phase_for_event(event)
            if phase is not None:
                self._mark_phase_cancelled(phase, detail=event.detail)
            return
        if event.kind == "task.updated":
            self.append_log(
                f"[update] {event.title or event.task_id or 'Task'}"
                + (f" ({event.detail})" if event.detail else "")
            )
            phase = self._phase_for_event(event)
            if phase is not None:
                self._mark_phase_running(phase)
            return
        if event.kind == "task.warning":
            self.append_log(f"[warn] {event.title or event.task_id or 'Task'}")
            self._phase_for_event(event)
            return
        if event.kind == "task.skipped":
            self.append_log(f"[skip] {event.title or event.task_id or 'Task'}")
            self._phase_for_event(event)

    def _clone_phase(self, phase: TuiPhaseSnapshot) -> TuiPhaseSnapshot:
        return TuiPhaseSnapshot(
            label=phase.label,
            task_id=phase.task_id,
            parent_task_id=phase.parent_task_id,
            status=phase.status,
            detail=phase.detail,
            started_at=phase.started_at,
            finished_at=phase.finished_at,
            children=[self._clone_phase(child) for child in phase.children],
        )

    def _phase_for_event(
        self,
        event: WorkflowEvent,
    ) -> TuiPhaseSnapshot | None:
        phase = self._phase_by_task_id.get(event.task_id or "")
        if phase is not None:
            self._sync_phase_metadata(phase, event.title or phase.label, event.task_id, event.detail)
            return phase
        if event.parent_task_id:
            parent = self._phase_by_task_id.get(event.parent_task_id)
            if parent is not None:
                return self._ensure_child_phase(
                    parent,
                    event.title or event.task_id or "Task",
                    task_id=event.task_id,
                        detail=event.detail,
                )
        if not self._phases:
            return self._upsert_top_level_phase(
                event.title or event.task_id or "Task",
                task_id=event.task_id,
                detail=event.detail,
            )
        if event.title is None:
            return None
        phase = self._match_planned_top_level_phase(event.title)
        if phase is None:
            return None
        self._sync_phase_metadata(phase, event.title, event.task_id, event.detail)
        return phase

    def _upsert_top_level_phase(
        self,
        label: str,
        *,
        task_id: str | None = None,
        detail: str = "",
    ) -> TuiPhaseSnapshot:
        phase = self._phase_by_task_id.get(task_id or "")
        if phase is not None and phase.parent_task_id is None:
            self._sync_phase_metadata(phase, label, task_id, detail)
            return phase
        phase = self._next_unassigned_top_level_phase()
        if phase is None:
            phase = TuiPhaseSnapshot(label=label, task_id=task_id, detail=detail)
            self._phases.append(phase)
        else:
            self._sync_phase_metadata(phase, label, task_id, detail)
        if task_id:
            self._phase_by_task_id[task_id] = phase
        return phase

    def _next_unassigned_top_level_phase(self) -> TuiPhaseSnapshot | None:
        for phase in self._phases:
            if phase.parent_task_id is None and phase.task_id is None:
                return phase
        return None

    def _match_planned_top_level_phase(self, label: str) -> TuiPhaseSnapshot | None:
        for phase in self._phases:
            if phase.parent_task_id is None and phase.task_id is None and phase.label == label:
                return phase
        return None

    def _ensure_child_phase(
        self,
        parent: TuiPhaseSnapshot,
        label: str,
        *,
        task_id: str | None = None,
        detail: str = "",
    ) -> TuiPhaseSnapshot:
        phase = self._phase_by_task_id.get(task_id or "")
        if phase is not None and phase.parent_task_id == parent.task_id:
            self._sync_phase_metadata(phase, label, task_id, detail)
            return phase
        if task_id is None:
            for child in parent.children:
                if child.label == label and child.parent_task_id == parent.task_id:
                    self._sync_phase_metadata(child, label, task_id, detail)
                    return child
        phase = TuiPhaseSnapshot(
            label=label,
            task_id=task_id,
            parent_task_id=parent.task_id,
            detail=detail,
        )
        parent.children.append(phase)
        if task_id:
            self._phase_by_task_id[task_id] = phase
        return phase

    def _sync_phase_metadata(
        self,
        phase: TuiPhaseSnapshot,
        label: str,
        task_id: str | None,
        detail: str,
    ) -> None:
        if label and (not phase.label or phase.label == phase.task_id):
            phase.label = label
        if task_id and not phase.task_id:
            phase.task_id = task_id
            self._phase_by_task_id[task_id] = phase
        if detail:
            phase.detail = detail

    def _mark_phase_running(self, phase: TuiPhaseSnapshot) -> None:
        if phase.status != "running":
            phase.status = "running"
        phase.started_at = phase.started_at or time.time()

    def _mark_phase_success(self, phase: TuiPhaseSnapshot, detail: str = "") -> None:
        phase.status = "success"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()

    def _mark_phase_failed(self, phase: TuiPhaseSnapshot, detail: str = "") -> None:
        phase.status = "failed"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()

    def _mark_phase_cancelled(self, phase: TuiPhaseSnapshot, detail: str = "") -> None:
        phase.status = "cancelled"
        if detail:
            phase.detail = detail
        phase.finished_at = time.time()
