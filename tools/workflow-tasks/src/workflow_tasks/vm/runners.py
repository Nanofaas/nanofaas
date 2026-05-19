from __future__ import annotations

from pathlib import Path


class OrchestratorVmRunner:
    """Adapts any VM orchestrator's exec_argv to the VmCommandRunner protocol."""

    def __init__(self, orchestrator: object, request: object) -> None:
        self._orch = orchestrator
        self._request = request

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> object:
        return self._orch.exec_argv(  # type: ignore[attr-defined]
            self._request, argv, env=env or None, cwd=remote_dir, dry_run=dry_run
        )


class VmFileFetcher:
    """Implements RemoteFileFetcher using any orchestrator's transfer_from()."""

    def __init__(self, vm: object, request: object) -> None:
        self._vm = vm
        self._request = request

    def fetch_from(self, remote: str, local: Path) -> None:
        result = self._vm.transfer_from(self._request, source=remote, destination=local)  # type: ignore[attr-defined]
        return_code = getattr(result, "return_code", 0)
        if return_code != 0:
            stderr = getattr(result, "stderr", "") or ""
            stdout = getattr(result, "stdout", "") or ""
            raise RuntimeError(stderr or stdout or f"transfer failed (exit {return_code})")
