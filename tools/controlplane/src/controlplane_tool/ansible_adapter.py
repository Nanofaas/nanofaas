from __future__ import annotations

from pathlib import Path
from typing import Callable

from multipass import MultipassClient

from controlplane_tool.paths import ToolPaths
from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.vm_models import VmRequest

HostResolver = Callable[[VmRequest, bool], str]


class AnsibleAdapter:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        host_resolver: HostResolver | None = None,
        private_key_path: Path | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.paths = ToolPaths.repo_root(Path(repo_root))
        self.shell = shell or SubprocessShell()
        if host_resolver is None:
            from controlplane_tool.vm_adapter import resolve_connection_host

            client = multipass_client or MultipassClient()
            host_resolver = lambda request, dry_run=False: resolve_connection_host(
                request,
                client,
                dry_run=dry_run,
            )
        self.host_resolver = host_resolver
        self.private_key_path = private_key_path

    def _inventory_target(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return f"{self.host_resolver(request, dry_run=dry_run)},"

    def _build_command(
        self,
        playbook_name: str,
        request: VmRequest,
        *,
        extra_vars: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> tuple[list[str], dict[str, str]]:
        playbook = self.paths.ansible_root / "playbooks" / playbook_name
        command = [
            "ansible-playbook",
            "-i",
            self._inventory_target(request, dry_run=dry_run),
            "-u",
            request.user,
        ]
        if self.private_key_path is not None:
            command.extend(["--private-key", str(self.private_key_path)])
        for key, value in (extra_vars or {}).items():
            command.extend(["-e", f"{key}={value}"])
        command.append(str(playbook))
        env = {"ANSIBLE_CONFIG": str(self.paths.ansible_root / "ansible.cfg")}
        return command, env

    def provision_base(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            "provision-base.yml",
            request,
            extra_vars={
                "install_helm": str(install_helm).lower(),
                "helm_version": helm_version.removeprefix("v"),
                "vm_user": request.user,
            },
            dry_run=dry_run,
        )
        return self.shell.run(
            command,
            cwd=self.paths.workspace_root,
            env=env,
            dry_run=dry_run,
        )

    def provision_k3s(
        self,
        request: VmRequest,
        *,
        kubeconfig_path: str,
        k3s_version: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        extra_vars = {
            "vm_user": request.user,
            "kubeconfig_path": kubeconfig_path,
        }
        if k3s_version:
            extra_vars["k3s_version_override"] = k3s_version
        command, env = self._build_command(
            "provision-k3s.yml",
            request,
            extra_vars=extra_vars,
            dry_run=dry_run,
        )
        return self.shell.run(
            command,
            cwd=self.paths.workspace_root,
            env=env,
            dry_run=dry_run,
        )

    def configure_registry(
        self,
        request: VmRequest,
        *,
        registry: str,
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        registry_host, registry_port = registry.rsplit(":", 1)
        command, env = self._build_command(
            "configure-registry.yml",
            request,
            extra_vars={
                "registry": registry,
                "registry_host": registry_host,
                "registry_port": registry_port,
                "registry_container_name": container_name,
            },
            dry_run=dry_run,
        )
        return self.shell.run(
            command,
            cwd=self.paths.workspace_root,
            env=env,
            dry_run=dry_run,
        )
