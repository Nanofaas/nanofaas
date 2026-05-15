from __future__ import annotations

import subprocess
from pathlib import Path

from azure_vm import AzureClient
from azure_vm.exceptions import VmNotFoundError

from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.workspace.paths import ToolPaths


def _find_ssh_private_key() -> Path | None:
    ssh_dir = Path.home() / ".ssh"
    for name in ("id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"):
        priv = ssh_dir / name
        if priv.exists():
            return priv
    return None


def _ok(command: list[str]) -> ShellExecutionResult:
    return ShellExecutionResult(command=command, return_code=0, stdout="")


class AzureVmOrchestrator:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)

    def _client(self, request: VmRequest) -> AzureClient:
        return AzureClient(
            resource_group=request.azure_resource_group,
            location=request.azure_location,
            ssh_key_path=request.azure_ssh_key_path,
            ssh_username=request.user,
        )

    def _vm_name(self, request: VmRequest) -> str:
        return request.name or "nanofaas-azure"

    def _ssh_key(self, request: VmRequest) -> Path | None:
        if request.azure_ssh_key_path:
            return Path(request.azure_ssh_key_path)
        return _find_ssh_private_key()

    def remote_home(self, request: VmRequest) -> str:
        if request.home:
            return request.home
        if request.user == "root":
            return "/root"
        return f"/home/{request.user}"

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def connection_host(self, request: VmRequest) -> str:
        vm = self._client(request).get_vm(self._vm_name(request))
        return vm.wait_for_ip()

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        try:
            self._client(request).get_vm(name).delete()
        except VmNotFoundError:
            pass
        return _ok(["azure", "delete", name])

    def ensure_running(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        self._client(request).ensure_running(
            name,
            vm_size=request.azure_vm_size,
            image_urn=request.azure_image_urn,
            ssh_key_path=request.azure_ssh_key_path,
        )
        return _ok(["azure", "ensure_running", name])

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
    ) -> ShellExecutionResult:
        vm = self._client(request).get_vm(self._vm_name(request))
        result = vm.exec_structured(list(argv), env=env, cwd=cwd)
        return ShellExecutionResult(
            command=list(argv),
            return_code=result.returncode,
            stdout=result.stdout,
            stderr=result.stderr,
        )

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
    ) -> ShellExecutionResult:
        vm = self._client(request).get_vm(self._vm_name(request))
        vm.transfer(str(source), destination)
        return _ok(["scp", str(source), destination])

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
    ) -> ShellExecutionResult:
        ip = self._client(request).get_vm(self._vm_name(request)).wait_for_ip()
        key = self._ssh_key(request)
        cmd: list[str] = ["scp"]
        if key:
            cmd.extend(["-i", str(key)])
        cmd.extend([
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            f"{request.user}@{ip}:{source}",
            str(destination),
        ])
        proc = subprocess.run(cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            command=cmd,
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )
