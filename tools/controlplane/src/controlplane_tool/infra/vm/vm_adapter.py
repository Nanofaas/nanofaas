from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from multipass import MultipassCommandError

from workflow_tasks.vm.multipass import MultipassVmProvider, _ok, _sdk_error, repo_rsync_command, repo_sync_ssh_rsh
from workflow_tasks.vm.models import VmRequest
from controlplane_tool.workspace.paths import ToolPaths

if TYPE_CHECKING:
    from shellcraft.backend import ShellBackend
    from multipass import MultipassClient
    from controlplane_tool.infra.vm.ansible_adapter import AnsibleAdapter

# Re-export for backward compatibility (tests import from here)
__all__ = ["VmOrchestrator", "repo_rsync_command"]


class VmOrchestrator(MultipassVmProvider):
    def __init__(
        self,
        repo_root: Path,
        shell: "ShellBackend | None" = None,
        ansible: "AnsibleAdapter | None" = None,
        multipass_client: "MultipassClient | None" = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        super().__init__(
            workspace_root=self.repo_root,
            shell=shell,
            multipass_client=multipass_client,
        )
        if ansible is None:
            from controlplane_tool.infra.vm.ansible_adapter import AnsibleAdapter

            ansible = AnsibleAdapter(
                self.repo_root,
                shell=self.shell,
                host_resolver=self.connection_host,
                private_key_path=self._private_key_path,
            )
        self.ansible = ansible

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/nanofaas"

    def kubeconfig_path(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/.kube/config"

    def remote_path_for_local(
        self,
        request: VmRequest,
        local_path: Path,
        *,
        local_root: Path | None = None,
        fallback_subdir: str | None = None,
    ) -> str:
        path = Path(local_path).resolve()
        root = Path(local_root or self.paths.workspace_root).resolve()
        remote_dir = self.remote_project_dir(request)

        try:
            relative = path.relative_to(root)
            return f"{remote_dir}/{relative.as_posix()}"
        except ValueError:
            if fallback_subdir:
                fallback = fallback_subdir.strip("/")
                return f"{remote_dir}/{fallback}/{path.name}"
            return f"{remote_dir}/{path.name}"

    def sync_project(
        self,
        request: VmRequest,
        *,
        source_dir: Path | None = None,
        remote_dir: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        source = Path(source_dir or self.paths.workspace_root)
        destination = remote_dir or self.remote_project_dir(request)

        if request.lifecycle == "external":
            return self._shell_run(
                repo_rsync_command(
                    source=source,
                    user=request.user,
                    host=str(request.host),
                    destination=destination,
                ),
                dry_run=dry_run,
            )

        host = self.connection_host(request, dry_run=dry_run)
        return self._shell_run(
            repo_rsync_command(
                source=source,
                user=request.user,
                host=host,
                destination=destination,
                ssh_rsh=repo_sync_ssh_rsh(self._private_key_path),
            ),
            dry_run=dry_run,
        )

    def install_dependencies(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_base(
            request,
            install_helm=install_helm,
            helm_version=helm_version,
            dry_run=dry_run,
        )

    def install_k3s(
        self,
        request: VmRequest,
        *,
        kubeconfig_path: str | None = None,
        k3s_version: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_k3s(
            request,
            kubeconfig_path=kubeconfig_path or self.kubeconfig_path(request),
            k3s_version=k3s_version,
            dry_run=dry_run,
        )

    def setup_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
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

    def ensure_registry_container(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )

    def configure_k3s_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.configure_k3s_registry(
            request,
            registry=registry,
            dry_run=dry_run,
        )

    def export_kubeconfig(
        self,
        request: VmRequest,
        *,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        kubeconfig_path = self.kubeconfig_path(request)
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", f"{request.user}@{request.host}:{kubeconfig_path}", str(destination)],
                dry_run=dry_run,
            )

        name = self._vm_name(request)
        transfer_cmd = ["multipass", "transfer", f"{name}:{kubeconfig_path}", str(destination)]
        if dry_run:
            return _ok(transfer_cmd)

        try:
            self._client.get_vm(name).transfer(f"{name}:{kubeconfig_path}", str(destination))
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(transfer_cmd)
