from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from workflow_tasks.vm.models import VmConfig, VmInfo
    from workflow_tasks.vm.ports import VmLifecycleProtocol


@dataclass
class EnsureVmRunning:
    task_id: str
    title: str
    lifecycle: "VmLifecycleProtocol"
    config: "VmConfig"
    _result: "VmInfo | None" = field(default=None, init=False, repr=False, compare=False)

    def run(self) -> "VmInfo":
        self._result = self.lifecycle.ensure_running(self.config)
        return self._result

    @property
    def result(self) -> "VmInfo":
        if self._result is None:
            raise RuntimeError(f"task {self.task_id!r} has not run yet")
        return self._result


@dataclass
class DestroyVm:
    task_id: str
    title: str
    lifecycle: "VmLifecycleProtocol"
    info: "VmInfo"

    def run(self) -> None:
        self.lifecycle.destroy(self.info)
