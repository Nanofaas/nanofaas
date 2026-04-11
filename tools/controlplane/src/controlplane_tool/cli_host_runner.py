"""
cli_host_runner.py

CliHostPlatformRunner: host-CLI platform lifecycle test against a VM-backed k3s cluster.

Mirrors the logic of the deleted e2e-cli-host-backend.sh (M10).
"""
from __future__ import annotations

from controlplane_tool.console import console, phase, step, success, warning, skip, fail, status

import os
from pathlib import Path

from controlplane_tool.scenario_components import cli as cli_components
from controlplane_tool.cli_platform_workflow import platform_uninstall_command
from controlplane_tool.scenario_components.cli import CliComponentContext
from controlplane_tool.scenario_components.operations import RemoteCommandOperation
from controlplane_tool.shell_backend import ShellExecutionResult, SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest, vm_request_from_env


class CliHostPlatformRunner:
    """Run the host-CLI platform lifecycle test against a VM-backed k3s cluster.

    Mirrors the logic of the deleted e2e-cli-host-backend.sh.
    """

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas-host-cli-e2e",
        release: str = "nanofaas-host-cli-e2e",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        skip_build: bool = False,
        skip_cli_build: bool = False,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.release = release
        self.local_registry = local_registry
        self.runtime = runtime
        self.skip_build = skip_build
        self.skip_cli_build = skip_cli_build
        self._shell = SubprocessShell()
        self._vm = VmOrchestrator(self.repo_root, shell=self._shell)
        self._cli_bin = (
            self.repo_root
            / "nanofaas-cli"
            / "build"
            / "install"
            / "nanofaas-cli"
            / "bin"
            / "nanofaas"
        )

    def _plan_context(self) -> CliComponentContext:
        return CliComponentContext(
            repo_root=self.repo_root,
            release=self.release,
            namespace=self.namespace,
            local_registry=self.local_registry,
            resolved_scenario=None,
        )

    def _first_operation_command(self, operations: tuple[RemoteCommandOperation, ...]) -> list[str]:
        if not operations:
            raise RuntimeError("shared CLI planner returned no operations")
        return list(operations[0].argv)

    @property
    def _remote_dir(self) -> str:
        return self._vm.remote_project_dir(self.vm_request)

    def _vm_exec(self, command: str) -> str:
        result = self._vm.remote_exec(self.vm_request, command=command)
        if result.return_code != 0:
            raise RuntimeError(f"VM exec failed: {command!r}\n{result.stderr}")
        return result.stdout.strip()

    def _build_cli_on_host(self) -> None:
        if self.skip_cli_build:
            if not self._cli_bin.exists():
                raise RuntimeError(f"CLI binary not found at {self._cli_bin}")
            return
        step("Building nanofaas-cli on host...")
        result = self._shell.run(
            self._first_operation_command(cli_components.plan_build_install_dist(self._plan_context())),
            cwd=self.repo_root,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "CLI build failed")
        if not self._cli_bin.exists():
            raise RuntimeError(f"CLI binary not found at {self._cli_bin}")

    def _resolve_public_host(self) -> str:
        host = os.getenv("E2E_PUBLIC_HOST", "") or os.getenv("E2E_VM_HOST", "")
        if host:
            return host
        if self.vm_request.lifecycle == "external" and self.vm_request.host:
            return self.vm_request.host
        # Fall back to Multipass IP
        ip = self._vm.resolve_multipass_ipv4(self.vm_request)
        return ip

    def _export_kubeconfig(self) -> Path:
        import tempfile

        dest = Path(tempfile.mktemp(suffix=".yaml"))
        self._vm.export_kubeconfig(self.vm_request, destination=dest)
        return dest

    def _platform_install_command(self) -> list[str]:
        return self._first_operation_command(cli_components.plan_platform_install(self._plan_context()))

    def _platform_status_command(self) -> list[str]:
        return self._first_operation_command(cli_components.plan_platform_status(self._plan_context()))

    def _platform_uninstall_command(self) -> list[str]:
        return list(
            platform_uninstall_command(release=self.release, namespace=self.namespace)
        )

    def _run_host_cli(self, kubeconfig: Path, command: list[str]) -> str:
        env = {**os.environ, "KUBECONFIG": str(kubeconfig)}
        result = self._shell.run(
            [str(self._cli_bin), *command],
            cwd=self.repo_root,
            env=env,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(f"CLI command failed: {command}\n{result.stderr}")
        return result.stdout.strip()

    def _run_host_cli_allow_fail(self, kubeconfig: Path, command: list[str]) -> tuple[int, str]:
        env = {**os.environ, "KUBECONFIG": str(kubeconfig)}
        result = self._shell.run(
            [str(self._cli_bin), *command],
            cwd=self.repo_root,
            env=env,
            dry_run=False,
        )
        return result.return_code, result.stdout.strip()

    def run(self, scenario_file: Path | None = None) -> None:
        _ = scenario_file  # host-platform does not use scenario selection

        skip_bootstrap = os.getenv("E2E_SKIP_VM_BOOTSTRAP", "").lower() == "true"
        if not skip_bootstrap:
            raise RuntimeError(
                "CliHostPlatformRunner requires VM to be bootstrapped. "
                "Set E2E_SKIP_VM_BOOTSTRAP=true and ensure VM is running, or use E2eRunner."
            )

        public_host = self._resolve_public_host()
        kubeconfig = self._export_kubeconfig()

        try:
            phase("Build")
            self._build_cli_on_host()

            phase("Deploy")
            step("Running platform lifecycle from host CLI...")
            install_out = self._run_host_cli(
                kubeconfig,
                self._platform_install_command(),
            )
            if f"endpoint\thttp://{public_host}:30080" not in install_out:
                raise RuntimeError(f"Unexpected endpoint in install output: {install_out}")

            phase("Verify")
            status_out = self._run_host_cli(kubeconfig, self._platform_status_command())
            if "deployment\tnanofaas-control-plane\t1/1" not in status_out:
                raise RuntimeError(f"Control-plane not ready: {status_out}")

            self._run_host_cli(kubeconfig, self._platform_uninstall_command())
            rc, _ = self._run_host_cli_allow_fail(kubeconfig, self._platform_status_command())
            if rc == 0:
                raise RuntimeError("platform status unexpectedly succeeded after uninstall")

            success("Host CLI platform lifecycle test:")
        finally:
            kubeconfig.unlink(missing_ok=True)
