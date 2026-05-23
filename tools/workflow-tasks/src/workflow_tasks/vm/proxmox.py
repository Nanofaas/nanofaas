from __future__ import annotations

import subprocess
from pathlib import Path
from typing import TYPE_CHECKING

from proxmox_sdk import ProxmoxClient
from proxmox_sdk.exceptions import VmNotFoundError

from shellcraft.backend import ShellExecutionResult
from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.vm.multipass import _find_ssh_private_key_path, _ok


def _parse_memory_mb(memory: str) -> int:
    """Convert memory string like '4G' or '512M' to MB integer."""
    s = memory.strip().upper()
    if s.endswith("G"):
        return int(s[:-1]) * 1024
    if s.endswith("M"):
        return int(s[:-1])
    return int(s)


def _parse_disk_gb(disk: str) -> int:
    """Convert disk string like '20G' or '512M' to GB integer."""
    s = disk.strip().upper()
    if s.endswith("G"):
        return int(s[:-1])
    if s.endswith("M"):
        return max(1, int(s[:-1]) // 1024)
    return int(s)


class ProxmoxVmProvider:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _client(self, request: VmRequest) -> ProxmoxClient:
        return ProxmoxClient(
            host=request.proxmox_host or "",
            user=request.proxmox_user or "root@pam",
            password=request.proxmox_password,
            node=request.proxmox_node,
        )

    def _vm_name(self, request: VmRequest) -> str:
        return request.name or "nanofaas-proxmox"

    def _ssh_key(self, request: VmRequest) -> Path | None:
        if request.proxmox_ssh_key_path:
            return Path(request.proxmox_ssh_key_path)
        return _find_ssh_private_key_path()

    def remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def connection_host(self, request: VmRequest) -> str:
        client = self._client(request)
        vm = client.get_vm(self._vm_name(request))
        return vm.wait_for_ip()

    def ensure_running(self, request: VmRequest) -> ShellExecutionResult:
        client = self._client(request)
        name = self._vm_name(request)
        cores = request.cpus or 2
        memory_mb = _parse_memory_mb(request.memory or "2G")
        disk_gb = _parse_disk_gb(request.disk or "20G")
        client.ensure_running(
            name,
            template_id=request.proxmox_template_id,
            node=request.proxmox_node,
            cores=cores,
            memory_mb=memory_mb,
            disk_gb=disk_gb,
        )
        return _ok(["proxmox", "ensure_running", name])

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        client = self._client(request)
        name = self._vm_name(request)
        try:
            vm = client.get_vm(name)
            vm.delete()
        except VmNotFoundError:
            pass
        return _ok(["proxmox", "delete", name])

    def exec_argv(
        self,
        request: VmRequest,
        argv: list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        client = self._client(request)
        vm = client.get_vm(self._vm_name(request))
        result = vm.exec_structured(list(argv), env=env, cwd=cwd)
        return ShellExecutionResult(
            return_code=result.exit_code,
            stdout=result.stdout or "",
            stderr=result.stderr or "",
            command=list(argv),
        )

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
    ) -> ShellExecutionResult:
        host = self.connection_host(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"
        scp_cmd = ["scp"]
        if ssh_key:
            scp_cmd += ["-i", str(ssh_key)]
        scp_cmd += [str(source), f"{user}@{host}:{destination}"]
        proc = subprocess.run(scp_cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
            command=scp_cmd,
        )

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
    ) -> ShellExecutionResult:
        host = self.connection_host(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"
        scp_cmd = ["scp"]
        if ssh_key:
            scp_cmd += ["-i", str(ssh_key)]
        scp_cmd += [f"{user}@{host}:{source}", str(destination)]
        proc = subprocess.run(scp_cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
            command=scp_cmd,
        )
