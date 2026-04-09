"""
helm_stack_runner.py

HelmStackRunner: Helm stack compatibility workflow against a VM-backed k3s cluster.

Mirrors the logic of the deleted e2e-helm-stack-backend.sh (M11).
"""
from __future__ import annotations

from controlplane_tool.console import console, phase, step, success, warning, skip, fail, status

import os
from pathlib import Path

from controlplane_tool.registry_runtime import LocalRegistry
from controlplane_tool.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.vm_models import VmRequest, vm_request_from_env


class HelmStackRunner:
    """Run the Helm stack compatibility workflow against a VM-backed k3s cluster.

    Mirrors the logic of the deleted e2e-helm-stack-backend.sh.
    """

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        noninteractive: bool = True,
        shell: ShellBackend | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.registry = LocalRegistry(local_registry)
        self.runtime = runtime
        self.noninteractive = noninteractive
        self._shell = shell or SubprocessShell()

    def _build_env(self) -> dict[str, str]:
        vm = self.vm_request
        env = dict(os.environ)
        env.update(
            {
                "VM_NAME": self.vm_request.name or "nanofaas-e2e",
                "NAMESPACE": self.namespace,
                "LOCAL_REGISTRY": self.registry.address,
                "CONTROL_PLANE_RUNTIME": self.runtime,
                "KEEP_VM": "true",
            }
        )
        if vm.host:
            env["E2E_VM_HOST"] = vm.host
        if vm.home:
            env["E2E_VM_HOME"] = vm.home
        if vm.user:
            env["E2E_VM_USER"] = vm.user
        if self.noninteractive:
            env["E2E_K3S_HELM_NONINTERACTIVE"] = "true"
        return env

    def run(self) -> None:
        skip_bootstrap = os.getenv("E2E_SKIP_VM_BOOTSTRAP", "").lower() == "true"
        if not skip_bootstrap:
            raise RuntimeError(
                "HelmStackRunner requires VM to be bootstrapped. "
                "Set E2E_SKIP_VM_BOOTSTRAP=true and ensure VM is running, or use E2eRunner."
            )

        env = self._build_env()
        phase("Run")
        step("Running loadtest via Python runner")
        loadtest = self._shell.run(
            [
                "uv",
                "run",
                "--project",
                str(self.repo_root / "tools" / "controlplane"),
                "--locked",
                "controlplane-tool",
                "loadtest",
                "run",
            ],
            dry_run=False,
            env=env,
        )
        if loadtest.return_code != 0:
            raise RuntimeError(loadtest.stderr or loadtest.stdout or "loadtest failed")
        step("Running autoscaling experiment (Python)")
        autoscaling = self._shell.run(
            [
                "uv",
                "run",
                "--project",
                str(self.repo_root / "tools" / "controlplane"),
                "--locked",
                "python",
                str(self.repo_root / "experiments" / "autoscaling.py"),
            ],
            dry_run=False,
            env=env,
        )
        if autoscaling.return_code != 0:
            raise RuntimeError(autoscaling.stderr or autoscaling.stdout or "autoscaling failed")
        success("Helm stack compatibility workflow:")
