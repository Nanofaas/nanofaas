"""workflow-tasks — task execution primitives and workflow event infrastructure.

Zero external dependencies. Configure once via bind_workflow_sink(); every
call to step/success/fail routes to the active sink.
"""
from __future__ import annotations

__version__ = "0.1.0"

from workflow_tasks.tasks.adapters import RemoteCommandOperationLike, operation_to_task_spec
from workflow_tasks.tasks.executors import HostCommandTaskExecutor, VmCommandTaskExecutor
from workflow_tasks.tasks.models import CommandTaskSpec, ExecutionTarget, TaskResult, TaskStatus
from workflow_tasks.tasks.rendering import render_shell_command, render_task_command
from workflow_tasks.workflow.context import (
    bind_workflow_context,
    bind_workflow_sink,
    get_workflow_context,
    has_workflow_sink,
)
from workflow_tasks.workflow.event_builders import build_log_event, build_phase_event, build_task_event
from workflow_tasks.workflow.events import WorkflowContext, WorkflowEvent, WorkflowSink
from workflow_tasks.workflow.models import TaskDefinition, TaskRun, WorkflowRun, WorkflowState
from workflow_tasks.workflow.reporting import (
    fail,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)

__all__ = [
    "__version__",
    # tasks
    "CommandTaskSpec", "ExecutionTarget", "TaskResult", "TaskStatus",
    "HostCommandTaskExecutor", "VmCommandTaskExecutor",
    "render_shell_command", "render_task_command",
    "RemoteCommandOperationLike", "operation_to_task_spec",
    # workflow types
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
    "WorkflowState", "WorkflowRun", "TaskDefinition", "TaskRun",
    # workflow runtime
    "bind_workflow_sink", "bind_workflow_context", "get_workflow_context", "has_workflow_sink",
    "build_task_event", "build_phase_event", "build_log_event",
    "phase", "step", "success", "warning", "skip", "fail",
    "workflow_log", "workflow_step", "status",
]
