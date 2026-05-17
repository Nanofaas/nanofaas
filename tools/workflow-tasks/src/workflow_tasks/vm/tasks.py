from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from workflow_tasks.vm.models import VmConfig, VmInfo
    from workflow_tasks.vm.ports import VmLifecycle


@dataclass
class EnsureVmRunning:
    task_id: str
    title: str
    lifecycle: "VmLifecycle"
    config: "VmConfig"

    def run(self) -> "VmInfo":
        return self.lifecycle.ensure_running(self.config)


@dataclass
class DestroyVm:
    task_id: str
    title: str
    lifecycle: "VmLifecycle"
    info: "VmInfo"

    def run(self) -> None:
        self.lifecycle.destroy(self.info)
