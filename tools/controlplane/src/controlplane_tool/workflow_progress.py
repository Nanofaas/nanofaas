from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from typing import Generator

from controlplane_tool.console import _workflow_context, workflow_step
from controlplane_tool.workflow_models import WorkflowContext


@dataclass(slots=True, frozen=True)
class WorkflowProgressReporter:
    flow_id: str
    parent_task_id: str | None = None

    @classmethod
    def current(cls) -> "WorkflowProgressReporter":
        context = _workflow_context() or WorkflowContext()
        return cls(
            flow_id=context.flow_id,
            parent_task_id=context.task_id or context.parent_task_id,
        )

    @contextmanager
    def child(
        self,
        task_id: str,
        title: str,
        detail: str = "",
    ) -> Generator[WorkflowContext, None, None]:
        context = _workflow_context() or WorkflowContext(flow_id=self.flow_id)
        with workflow_step(
            task_id=task_id,
            title=title,
            detail=detail,
            parent_task_id=self.parent_task_id,
            context=context,
        ) as child_context:
            yield child_context
