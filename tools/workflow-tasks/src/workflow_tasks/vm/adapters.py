from __future__ import annotations

from workflow_tasks.vm.models import VmConfig, VmInfo, VmRequest


class VmLifecycleAdapter:
    """Implements VmLifecycle Protocol for any VM orchestrator.

    The optional *credentials* VmRequest carries provider-specific fields
    (e.g. proxmox_host, proxmox_password) that VmConfig intentionally omits.
    They are merged via model_copy so the orchestrator always receives a fully
    populated VmRequest without the caller needing to know the internals.
    """

    def __init__(
        self,
        orchestrator: object,
        *,
        lifecycle: str,
        credentials: VmRequest | None = None,
    ) -> None:
        self._vm = orchestrator
        self._lifecycle = lifecycle
        self._credentials = credentials or VmRequest(lifecycle=lifecycle)  # type: ignore[arg-type]

    def ensure_running(self, config: VmConfig) -> VmInfo:
        request = self._credentials.model_copy(
            update={
                "name": config.name,
                "cpus": config.cpus,
                "memory": config.memory,
                "disk": config.disk,
            }
        )
        self._vm.ensure_running(request)  # type: ignore[attr-defined]
        host = self._vm.connection_host(request)  # type: ignore[attr-defined]
        return VmInfo(name=config.name, host=host, user="ubuntu", home="/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        request = self._credentials.model_copy(update={"name": info.name})
        self._vm.teardown(request)  # type: ignore[attr-defined]


def MultipassVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="multipass")


def AzureVmAdapter(
    orchestrator: object,
    *,
    credentials: VmRequest | None = None,
) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="azure", credentials=credentials)


def ProxmoxVmAdapter(
    orchestrator: object,
    *,
    credentials: VmRequest | None = None,
) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="proxmox", credentials=credentials)
