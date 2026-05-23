from __future__ import annotations

import shlex
import subprocess
from pathlib import Path
from typing import Any

from proxmox_sdk import ProxmoxClient
from proxmox_sdk.exceptions import VmNotFoundError
from proxmox_sdk.routing import PortMapping, ProxmoxRoutingManager

from shellcraft.backend import ShellExecutionResult
from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.vm.multipass import _find_ssh_private_key_path, _ok


def _parse_memory_mb(memory: str) -> int:
    s = memory.strip().upper()
    if s.endswith("G"):
        return int(s[:-1]) * 1024
    if s.endswith("M"):
        return int(s[:-1])
    return int(s)


def _parse_disk_gb(disk: str) -> int:
    s = disk.strip().upper()
    if s.endswith("G"):
        return int(s[:-1])
    if s.endswith("M"):
        return max(1, int(s[:-1]) // 1024)
    return int(s)


class ProxmoxVmProvider:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)
        self._ssh_endpoints: dict[str, tuple[str, int]] = {}

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

    def _routing_manager(self, request: VmRequest) -> ProxmoxRoutingManager:
        host = request.proxmox_host or ""
        # proxmox_user is "root@pam" → SSH login is "root"
        ssh_user = (request.proxmox_user or "root@pam").split("@")[0]
        ssh_key = self._ssh_key(request)
        if ssh_key:
            return ProxmoxRoutingManager.from_key(host, ssh_user, str(ssh_key))
        return ProxmoxRoutingManager.from_password(host, ssh_user, request.proxmox_password or "")

    def _publish_ssh(self, request: VmRequest, *, vm: Any, guest_ip: str) -> PortMapping:
        mapping = PortMapping(
            vm_id=int(vm.vm_id),
            vm_name=self._vm_name(request),
            vm_ip=guest_ip,
            vm_port=22,
            service="SSH",
        )
        [published] = self._routing_manager(request).add_rules([mapping])
        if published.host_port is None:
            raise RuntimeError(f"Proxmox SSH NAT rule for {mapping.vm_name} has no host port")
        return published

    def _published_rule(self, request: VmRequest, service: str = "SSH") -> PortMapping:
        name = self._vm_name(request)
        rules = [
            r for r in self._routing_manager(request).list_rules()
            if r.vm_name == name and r.service == service
        ]
        if not rules:
            raise RuntimeError(f"Missing Proxmox NAT rule for {name} service {service}")
        rule = rules[0]
        if rule.host_port is None:
            raise RuntimeError(f"Proxmox NAT rule for {name} service {service} has no host port")
        return rule

    def _ssh_endpoint(self, request: VmRequest) -> tuple[str, int]:
        name = self._vm_name(request)
        if name in self._ssh_endpoints:
            return self._ssh_endpoints[name]
        try:
            rule = self._published_rule(request, "SSH")
            return request.proxmox_host or "", int(rule.host_port)
        except RuntimeError:
            return self.connection_host(request), 22

    def remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def guest_host(self, request: VmRequest) -> str:
        return self.connection_host(request)

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
        vm = client.ensure_running(
            name,
            template_id=request.proxmox_template_id,
            node=request.proxmox_node,
            cores=cores,
            memory_mb=memory_mb,
            disk_gb=disk_gb,
        )
        vm.wait_ready()
        guest_ip = vm.wait_for_ip()
        published = self._publish_ssh(request, vm=vm, guest_ip=guest_ip)
        self._ssh_endpoints[name] = (request.proxmox_host or "", int(published.host_port))
        return _ok(["proxmox", "ensure_running", name])

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        client = self._client(request)
        name = self._vm_name(request)
        try:
            vm = client.get_vm(name)
            vm.delete()
        except VmNotFoundError:
            pass
        try:
            mgr = self._routing_manager(request)
            rules = [r for r in mgr.list_rules() if r.vm_name == name]
            if rules:
                mgr.remove_rules(rules)
        except Exception:
            pass
        return _ok(["proxmox", "delete", name])

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        del dry_run
        host = self.connection_host(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"

        ssh_cmd = ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes"]
        if ssh_key:
            ssh_cmd += ["-i", str(ssh_key)]

        remote_parts: list[str] = []
        if cwd:
            remote_parts.append(f"cd {shlex.quote(cwd)} &&")
        if env:
            remote_parts.append("env")
            remote_parts.extend(f"{k}={shlex.quote(v)}" for k, v in env.items())
        remote_parts.extend(shlex.quote(a) for a in argv)

        ssh_cmd += [f"{user}@{host}", " ".join(remote_parts)]
        proc = subprocess.run(ssh_cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
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
