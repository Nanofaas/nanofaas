# Re-exports from workflow_tasks for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.models import VmLifecycle, VmRequest, vm_request_from_env

__all__ = ["VmLifecycle", "VmRequest", "vm_request_from_env"]
