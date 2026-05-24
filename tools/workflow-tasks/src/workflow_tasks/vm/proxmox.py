from __future__ import annotations

import shlex
import socket
import subprocess
import time
from pathlib import Path
from typing import Any, cast

from proxmox_sdk import ProxmoxClient
from proxmox_sdk.exceptions import VmNotFoundError
from proxmox_sdk.models import CloudInitConfig
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

    def _ssh_public_key(self, request: VmRequest) -> str | None:
        private_key = self._ssh_key(request)
        if private_key is None:
            return None
        public_key = Path(f"{private_key}.pub")
        if public_key.exists():
            return public_key.read_text(encoding="utf-8").strip()
        return None

    def _cloud_init_config(self, request: VmRequest) -> CloudInitConfig | None:
        public_key = self._ssh_public_key(request)
        if not public_key:
            return None
        return CloudInitConfig(username=request.user, ssh_keys=[public_key])

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

    def _published_rule_or_none(self, request: VmRequest, service: str = "SSH") -> PortMapping | None:
        name = self._vm_name(request)
        rules = [
            r for r in self._routing_manager(request).list_rules()
            if r.vm_name == name and r.service == service
        ]
        if rules:
            return rules[0]
        return None

    def _published_rule(self, request: VmRequest, service: str = "SSH") -> PortMapping:
        name = self._vm_name(request)
        rule = self._published_rule_or_none(request, service)
        if rule is None:
            raise RuntimeError(f"Missing Proxmox NAT rule for {name} service {service}")
        if rule.host_port is None:
            raise RuntimeError(f"Proxmox NAT rule for {name} service {service} has no host port")
        return rule

    def _reconcile_published_rule(
        self,
        request: VmRequest,
        *,
        service: str,
        guest_port: int,
    ) -> PortMapping:
        name = self._vm_name(request)
        vm = self._client(request).get_vm(name)
        guest_ip = vm.wait_for_ip()
        rule = self._published_rule_or_none(request, service)
        if (
            rule is not None
            and rule.host_port is not None
            and rule.vm_id == int(vm.vm_id)
            and rule.vm_ip == guest_ip
            and rule.vm_port == guest_port
        ):
            return rule
        mapping = PortMapping(
            vm_id=int(vm.vm_id),
            vm_name=name,
            vm_ip=guest_ip,
            vm_port=guest_port,
            service=service,
        )
        [published] = self._routing_manager(request).add_rules([mapping])
        if published.host_port is None:
            raise RuntimeError(f"Proxmox NAT rule for {name} service {service} has no host port")
        return published

    def _ssh_endpoint(self, request: VmRequest) -> tuple[str, int]:
        name = self._vm_name(request)
        rule = self._reconcile_published_rule(request, service="SSH", guest_port=22)
        endpoint = (request.proxmox_host or "", int(cast(int, rule.host_port)))
        self._ssh_endpoints[name] = endpoint
        return endpoint

    def _ssh_nat_diagnostic(self, request: VmRequest) -> str:
        name = self._vm_name(request)
        vm_id = "<unknown>"
        guest_ip = "<unknown>"
        try:
            vm = self._client(request).get_vm(name)
            vm_id = str(int(vm.vm_id))
            guest_ip = str(vm.wait_for_ip())
        except Exception as exc:
            guest_ip = f"<unavailable: {exc}>"
        try:
            rule = self._published_rule_or_none(request, "SSH")
        except Exception as exc:
            return (
                f"Proxmox NAT diagnostic: vm={name} vm_id={vm_id} guest_ip={guest_ip} "
                f"SSH=<unavailable: {exc}>"
            )
        if rule is None:
            rule_text = "SSH=<missing>"
        else:
            host_port = rule.host_port if rule.host_port is not None else "<none>"
            rule_text = f"SSH={host_port}->{rule.vm_ip}:{rule.vm_port}"
        return f"Proxmox NAT diagnostic: vm={name} vm_id={vm_id} guest_ip={guest_ip} {rule_text}"

    def _append_ssh_nat_diagnostic(self, request: VmRequest, stderr: str) -> str:
        diagnostic = self._ssh_nat_diagnostic(request)
        if stderr and not stderr.endswith("\n"):
            stderr += "\n"
        return f"{stderr}{diagnostic}\n"

    def _wait_for_ssh(self, host: str, port: int, *, timeout: float = 120.0) -> None:
        """Probe the NAT-forwarded SSH port until it accepts TCP connections."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                with socket.create_connection((host, port), timeout=5.0):
                    return
            except OSError:
                time.sleep(2.0)
        raise RuntimeError(
            f"SSH at {host}:{port} did not become reachable within {timeout:.0f}s"
            " — cloud-init may still be rebooting the VM"
        )

    def ssh_endpoint(self, request: VmRequest) -> tuple[str, int]:
        return self._ssh_endpoint(request)

    def ssh_private_key_path(self, request: VmRequest) -> Path | None:
        return self._ssh_key(request)

    def remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def publish_port(self, request: VmRequest, *, service: str, guest_port: int) -> tuple[str, int]:
        rule = self._reconcile_published_rule(request, service=service, guest_port=guest_port)
        return request.proxmox_host or "", int(cast(int, rule.host_port))

    def published_endpoint(self, request: VmRequest, *, service: str) -> tuple[str, int]:
        rule = self._published_rule(request, service)
        return request.proxmox_host or "", int(cast(int, rule.host_port))

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
            template_id=cast(int, request.proxmox_template_id),
            node=request.proxmox_node,
            cores=cores,
            memory_mb=memory_mb,
            disk_gb=disk_gb,
            cloud_init_config=self._cloud_init_config(request),
        )
        vm.wait_ready()
        guest_ip = vm.wait_for_ip()
        published = self._publish_ssh(request, vm=vm, guest_ip=guest_ip)
        host = request.proxmox_host or ""
        port = int(cast(int, published.host_port))
        self._ssh_endpoints[name] = (host, port)
        self._wait_for_ssh(host, port)
        return _ok(["proxmox", "ensure_running", name])

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        client = self._client(request)
        name = self._vm_name(request)
        delete_error: Exception | None = None
        try:
            vm = client.get_vm(name)
            try:
                if vm.info().state.value == "running":
                    vm.stop()
            except Exception:
                pass
            try:
                vm.delete()
            except VmNotFoundError:
                pass
            except Exception as exc:
                delete_error = exc
        except VmNotFoundError:
            pass
        except Exception as exc:
            delete_error = exc
        try:
            mgr = self._routing_manager(request)
            rules = [r for r in mgr.list_rules() if r.vm_name == name]
            if rules:
                mgr.remove_rules(rules)
        except Exception:
            pass
        if delete_error is not None:
            raise delete_error
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
        host, port = self._ssh_endpoint(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"

        ssh_cmd = ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "-p", str(port)]
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
        stderr = proc.stderr
        if proc.returncode != 0:
            stderr = self._append_ssh_nat_diagnostic(request, stderr)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=stderr,
            command=list(argv),
        )

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
    ) -> ShellExecutionResult:
        host, port = self._ssh_endpoint(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"
        scp_cmd = ["scp", "-P", str(port)]
        if ssh_key:
            scp_cmd += ["-i", str(ssh_key)]
        scp_cmd += [str(source), f"{user}@{host}:{destination}"]
        proc = subprocess.run(scp_cmd, capture_output=True, text=True)
        stderr = proc.stderr
        if proc.returncode != 0:
            stderr = self._append_ssh_nat_diagnostic(request, stderr)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=stderr,
            command=scp_cmd,
        )

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
    ) -> ShellExecutionResult:
        host, port = self._ssh_endpoint(request)
        ssh_key = self._ssh_key(request)
        user = request.user or "ubuntu"
        scp_cmd = ["scp", "-P", str(port)]
        if ssh_key:
            scp_cmd += ["-i", str(ssh_key)]
        scp_cmd += [f"{user}@{host}:{source}", str(destination)]
        proc = subprocess.run(scp_cmd, capture_output=True, text=True)
        stderr = proc.stderr
        if proc.returncode != 0:
            stderr = self._append_ssh_nat_diagnostic(request, stderr)
        return ShellExecutionResult(
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=stderr,
            command=scp_cmd,
        )
