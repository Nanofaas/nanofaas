# Re-exports ProxmoxVmProvider; ProxmoxVmOrchestrator is an alias for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.proxmox import ProxmoxVmProvider

ProxmoxVmOrchestrator = ProxmoxVmProvider

__all__ = ["ProxmoxVmOrchestrator", "ProxmoxVmProvider"]
