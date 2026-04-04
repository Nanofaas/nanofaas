"""
cli_host_runner.py

CliHostPlatformRunner: host-CLI platform lifecycle test against a VM-backed k3s cluster.

Mirrors the logic of the deleted e2e-cli-host-backend.sh (M10).
"""
from __future__ import annotations

from controlplane_tool.console import console

import os
import subprocess
from pathlib import Path

from controlplane_tool.shell_backend import SubprocessShell
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

    @property
    def _control_image(self) -> str:
        return f"{self.local_registry}/nanofaas/control-plane:e2e"

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
        console.print("[e2e-host-cli] Building nanofaas-cli on host...")
        subprocess.run(
            ["./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"],
            cwd=str(self.repo_root),
            check=True,
        )
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

    def _run_host_cli(self, kubeconfig: Path, command: list[str]) -> str:
        env = {**os.environ, "KUBECONFIG": str(kubeconfig)}
        result = subprocess.run(
            [str(self._cli_bin), *command],
            cwd=str(self.repo_root),
            text=True,
            capture_output=True,
            env=env,
        )
        if result.returncode != 0:
            raise RuntimeError(f"CLI command failed: {command}\n{result.stderr}")
        return result.stdout.strip()

    def _run_host_cli_allow_fail(self, kubeconfig: Path, command: list[str]) -> tuple[int, str]:
        env = {**os.environ, "KUBECONFIG": str(kubeconfig)}
        result = subprocess.run(
            [str(self._cli_bin), *command],
            cwd=str(self.repo_root),
            text=True,
            capture_output=True,
            env=env,
        )
        return result.returncode, result.stdout.strip()

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
            self._build_cli_on_host()

            console.print("[e2e-host-cli] Running platform lifecycle from host CLI...")
            install_out = self._run_host_cli(
                kubeconfig,
                [
                    "platform",
                    "install",
                    "--release",
                    self.release,
                    "-n",
                    self.namespace,
                    "--chart",
                    str(self.repo_root / "helm" / "nanofaas"),
                    "--control-plane-repository",
                    self._control_image.split(":")[0],
                    "--control-plane-tag",
                    self._control_image.split(":")[-1],
                    "--control-plane-pull-policy",
                    "Always",
                    "--demos-enabled=false",
                ],
            )
            if f"endpoint\thttp://{public_host}:30080" not in install_out:
                raise RuntimeError(f"Unexpected endpoint in install output: {install_out}")

            status_out = self._run_host_cli(kubeconfig, ["platform", "status", "-n", self.namespace])
            if "deployment\tnanofaas-control-plane\t1/1" not in status_out:
                raise RuntimeError(f"Control-plane not ready: {status_out}")

            self._run_host_cli(
                kubeconfig, ["platform", "uninstall", "--release", self.release, "-n", self.namespace]
            )
            rc, _ = self._run_host_cli_allow_fail(kubeconfig, ["platform", "status", "-n", self.namespace])
            if rc == 0:
                raise RuntimeError("platform status unexpectedly succeeded after uninstall")

            console.print("[e2e-host-cli] Host CLI platform lifecycle test: PASSED")
        finally:
            kubeconfig.unlink(missing_ok=True)
