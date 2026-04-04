"""
helm_stack_runner.py

HelmStackRunner: Helm stack compatibility workflow against a VM-backed k3s cluster.

Mirrors the logic of the deleted e2e-helm-stack-backend.sh (M11).
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from controlplane_tool.registry_runtime import LocalRegistry
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
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.registry = LocalRegistry(local_registry)
        self.runtime = runtime
        self.noninteractive = noninteractive

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
        print("[helm-stack] Running loadtest via Python runner")
        subprocess.run(
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
            check=True,
            env=env,
        )
        print("[helm-stack] Running autoscaling experiment (Python)")
        subprocess.run(
            [
                "uv",
                "run",
                "--project",
                str(self.repo_root / "tools" / "controlplane"),
                "--locked",
                "python",
                str(self.repo_root / "experiments" / "autoscaling.py"),
            ],
            check=True,
            env=env,
        )
        print("[helm-stack] Helm stack compatibility workflow: PASSED")
