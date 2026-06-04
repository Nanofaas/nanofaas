from __future__ import annotations

from pathlib import Path
from typing import Protocol

from multipass import MultipassClient

from workflow_tasks.shell import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from workflow_tasks.vm.models import VmRequest


class HostResolver(Protocol):
    def __call__(self, request: VmRequest, *, dry_run: bool = False) -> str: ...


def bundled_ansible_root() -> Path:
    """Path to the Ansible playbooks bundled inside the library."""
    return Path(__file__).parent / "ansible_assets"


class AnsibleAdapter:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        host_resolver: HostResolver | None = None,
        private_key_path: Path | None = None,
        multipass_client: MultipassClient | None = None,
        ansible_root: Path | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        # Playbooks are bundled with the library; callers may override.
        self.ansible_root = (
            Path(ansible_root)
            if ansible_root is not None
            else bundled_ansible_root()
        )
        self.shell = shell or SubprocessShell()
        if host_resolver is None:
            from workflow_tasks.vm.multipass import resolve_connection_host

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
        playbook = self.ansible_root / "playbooks" / playbook_name
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
        env = {"ANSIBLE_CONFIG": str(self.ansible_root / "ansible.cfg")}
        return command, env

    def run_playbook(
        self,
        playbook_name: str,
        request: VmRequest,
        *,
        extra_vars: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            playbook_name, request, extra_vars=extra_vars, dry_run=dry_run
        )
        return self.shell.run(command, cwd=self.repo_root, env=env, dry_run=dry_run)

    def install_k6(
        self,
        request: VmRequest,
        *,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook("install-k6.yml", request, dry_run=dry_run)

    def _registry_extra_vars(
        self,
        *,
        registry: str,
        container_name: str | None = None,
    ) -> dict[str, str]:
        registry_host, registry_port = registry.rsplit(":", 1)
        extra_vars = {
            "registry": registry,
            "registry_host": registry_host,
            "registry_port": registry_port,
        }
        if container_name is not None:
            extra_vars["registry_container_name"] = container_name
        return extra_vars

    def provision_base(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook(
            "provision-base.yml",
            request,
            extra_vars={
                "install_helm": str(install_helm).lower(),
                "helm_version": helm_version.removeprefix("v"),
                "vm_user": request.user,
            },
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
        return self.run_playbook(
            "provision-k3s.yml",
            request,
            extra_vars=extra_vars,
            dry_run=dry_run,
        )

    def ensure_registry_container(
        self,
        request: VmRequest,
        *,
        registry: str,
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook(
            "ensure-registry.yml",
            request,
            extra_vars=self._registry_extra_vars(
                registry=registry,
                container_name=container_name,
            ),
            dry_run=dry_run,
        )

    def configure_k3s_registry(
        self,
        request: VmRequest,
        *,
        registry: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook(
            "configure-k3s-registry.yml",
            request,
            extra_vars=self._registry_extra_vars(registry=registry),
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
        ensure_result = self.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )
        if ensure_result.return_code != 0:
            return ensure_result
        return self.configure_k3s_registry(
            request,
            registry=registry,
            dry_run=dry_run,
        )
