from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


@dataclass
class BuildCliDist:
    """Build nanofaas-cli installDist inside the VM."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str

    def run(self) -> None:
        result = self.vm.exec_argv(
            self.request,
            ("./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"),
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class CliApplyFunction:
    """Apply a single function spec via nanofaas-cli fn apply."""

    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str
    cli_binary: str
    function_name: str
    image: str
    namespace: str
    kubeconfig: str

    def run(self) -> None:
        import json
        import shlex
        spec = json.dumps({
            "name": self.function_name,
            "image": self.image,
            "timeoutMs": 5000,
            "concurrency": 2,
            "queueSize": 20,
            "maxRetries": 3,
            "executionMode": "DEPLOYMENT",
        }, separators=(",", ":"))
        manifest = f"/tmp/{self.function_name}.json"
        command = (
            f"printf '%s' {shlex.quote(spec)} > {shlex.quote(manifest)} && "
            f"{shlex.quote(self.cli_binary)} fn apply -f {shlex.quote(manifest)}"
        )
        result = self.vm.exec_argv(
            self.request,
            ("bash", "-lc", command),
            env={
                "KUBECONFIG": self.kubeconfig,
                "NANOFAAS_NAMESPACE": self.namespace,
                "NANOFAAS_FUNCTION_IMAGE": self.image,
            },
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")
