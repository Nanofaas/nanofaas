from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import json
from pathlib import Path
import re
import subprocess
from typing import Any

from azure_vm import AzureClient
from azure_vm.exceptions import VmNotFoundError
from shellcraft.backend import ShellExecutionResult

from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.vm.multipass import _find_ssh_private_key_path, _ok


@dataclass(frozen=True, slots=True)
class AzureVmFacts:
    location: str
    vm_size: str
    disk_size_gb: int
    image_urn: str


class AzureVmProvider:
    """Generic Azure VM provider: lifecycle, command execution, file transfer."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

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
        return _find_ssh_private_key_path()

    def ssh_private_key_path(self, request: VmRequest) -> Path | None:
        key = self._ssh_key(request)
        if key is None:
            return None
        # ssh_key_path configures the tofu PUBLIC key (SDK default
        # ~/.ssh/id_rsa.pub); the matching private key sits next to it.
        if key.suffix == ".pub":
            return key.with_suffix("")
        return key

    def remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

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
            open_ports=request.azure_open_ports,
            disk_size_gb=_disk_size_gb(request.disk),
        )
        return _ok(["azure", "ensure_running", name])

    def release_vm_facts(self, request: VmRequest) -> AzureVmFacts:
        resource_group = request.azure_resource_group
        if not resource_group:
            raise ValueError("Azure resource group is required")
        payload = self._az_json(
            [
                "az",
                "vm",
                "show",
                "--resource-group",
                resource_group,
                "--name",
                self._vm_name(request),
                "--query",
                (
                    "{location:location,vmSize:hardwareProfile.vmSize,"
                    # az >= 2.88 returns the raw ARM casing (diskSizeGB); older
                    # versions normalized it to diskSizeGb. Accept both.
                    "diskSizeGb:storageProfile.osDisk.diskSizeGB || storageProfile.osDisk.diskSizeGb,"
                    "imagePublisher:storageProfile.imageReference.publisher,"
                    "imageOffer:storageProfile.imageReference.offer,"
                    "imageSku:storageProfile.imageReference.sku,"
                    "imageVersion:storageProfile.imageReference.version}"
                ),
                "--output",
                "json",
            ]
        )
        if not isinstance(payload, dict):
            raise RuntimeError("Azure VM facts response is invalid")
        try:
            image_urn = ":".join(
                str(payload[key])
                for key in ("imagePublisher", "imageOffer", "imageSku", "imageVersion")
            )
            return AzureVmFacts(
                location=str(payload["location"]),
                vm_size=str(payload["vmSize"]),
                disk_size_gb=int(payload["diskSizeGb"]),
                image_urn=image_urn,
            )
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError("Azure VM facts response is invalid") from error

    def restrict_inbound_sources(
        self,
        request: VmRequest,
        *,
        ports: tuple[int, ...],
        source_cidrs: tuple[str, ...],
        priority_base: int = 1010,
    ) -> None:
        resource_group = request.azure_resource_group
        if not resource_group:
            raise ValueError("Azure resource group is required")
        networks = tuple(ipaddress.ip_network(source, strict=False) for source in source_cidrs)
        if any(network.prefixlen == 0 for network in networks):
            raise ValueError("Azure NSG source CIDRs must be bounded")
        sources = tuple(dict.fromkeys(map(str, networks)))
        if not sources:
            raise ValueError("at least one Azure NSG source CIDR is required")
        for index, port in enumerate(sorted(set(ports))):
            if not 1 <= port <= 65535:
                raise ValueError(f"invalid Azure NSG port: {port}")
            payload = self._az_json(
                [
                    "az",
                    "network",
                    "nsg",
                    "rule",
                    "create",
                    "--resource-group",
                    resource_group,
                    "--nsg-name",
                    f"{self._vm_name(request)}-nsg",
                    "--name",
                    f"Port{port}",
                    "--priority",
                    str(priority_base + index),
                    "--direction",
                    "Inbound",
                    "--access",
                    "Allow",
                    "--protocol",
                    "Tcp",
                    "--destination-port-ranges",
                    str(port),
                    "--source-address-prefixes",
                    *sources,
                    "--query",
                    # A single source lands in the singular sourceAddressPrefix
                    # field and leaves the plural empty ([] is falsy in JMESPath).
                    "sourceAddressPrefixes || [sourceAddressPrefix]",
                    "--output",
                    "json",
                ]
            )
            if not isinstance(payload, list) or set(map(str, payload)) != set(sources):
                raise RuntimeError("Azure NSG source restriction mismatch")

    @staticmethod
    def _az_json(argv: list[str]) -> Any:
        process = subprocess.run(argv, capture_output=True, text=True)
        if process.returncode != 0:
            raise RuntimeError(process.stderr or "Azure CLI command failed")
        try:
            return json.loads(process.stdout)
        except json.JSONDecodeError as error:
            raise RuntimeError("Azure CLI returned invalid JSON") from error

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
        key = self.ssh_private_key_path(request)
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


def _disk_size_gb(disk: str) -> int:
    match = re.fullmatch(r"([1-9][0-9]*)G", disk)
    if match is None:
        raise ValueError(f"Azure disk must use whole gibibytes, got {disk!r}")
    return int(match.group(1))
