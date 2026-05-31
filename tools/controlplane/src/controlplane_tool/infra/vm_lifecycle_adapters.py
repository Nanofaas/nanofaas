# Re-exports from workflow_tasks for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, ProxmoxVmAdapter, VmLifecycleAdapter

__all__ = ["VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter", "ProxmoxVmAdapter"]
