from __future__ import annotations

from typing import Protocol

from workflow_tasks.vm.models import VmConfig, VmInfo


class VmLifecycle(Protocol):
    def ensure_running(self, config: VmConfig) -> VmInfo: ...
    def destroy(self, info: VmInfo) -> None: ...
