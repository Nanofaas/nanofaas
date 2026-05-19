# Re-exports AzureVmProvider; AzureVmOrchestrator is an alias for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.azure import AzureVmProvider

AzureVmOrchestrator = AzureVmProvider

__all__ = ["AzureVmOrchestrator", "AzureVmProvider"]
