"""
cli_runtime.py

Python implementations of CLI E2E scenario workflows (M10).

Replaces:
  - scripts/lib/e2e-cli-backend.sh     (cli / vm scenario)
  - scripts/lib/e2e-cli-host-backend.sh (cli-host / host-platform scenario)

These runners use VmOrchestrator for remote execution rather than delegating
to shell scripts that source e2e-k3s-common.sh.

Invoked via:
    controlplane-tool cli-e2e run vm
    controlplane-tool cli-e2e run host-platform
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import TYPE_CHECKING

from controlplane_tool.shell_backend import SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest, vm_request_from_env

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


def _resolve_scenario(scenario_file: Path | None) -> "ResolvedScenario | None":
    if scenario_file is None:
        return None
    from controlplane_tool.scenario_loader import load_scenario_file

    return load_scenario_file(scenario_file)


def _selected_functions(resolved: "ResolvedScenario | None", default: str = "echo-test") -> list[str]:
    if resolved is None or not resolved.functions:
        return [default]
    return [fn.key for fn in resolved.functions]


def _function_image(fn_key: str, resolved: "ResolvedScenario | None", default: str) -> str:
    if resolved is None:
        return default
    for fn in resolved.functions:
        if fn.key == fn_key and fn.image:
            return fn.image
    return default


class CliVmRunner:
    """Run the CLI E2E workflow inside a VM-backed environment.

    Mirrors the logic of the deleted e2e-cli-backend.sh.
    """

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas-e2e",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        skip_cli_build: bool = False,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or vm_request_from_env()
        self.namespace = namespace
        self.local_registry = local_registry
        self.runtime = runtime
        self.skip_cli_build = skip_cli_build
        self._shell = SubprocessShell()
        self._vm = VmOrchestrator(self.repo_root, shell=self._shell)

    def _vm_exec(self, command: str) -> str:
        result = self._vm.remote_exec(self.vm_request, command=command)
        if result.return_code != 0:
            raise RuntimeError(f"VM exec failed: {command!r}\n{result.stderr}")
        return result.stdout.strip()

    @property
    def _remote_dir(self) -> str:
        return self._vm.remote_project_dir(self.vm_request)

    @property
    def _control_image(self) -> str:
        return f"{self.local_registry}/nanofaas/control-plane:e2e"

    @property
    def _runtime_image(self) -> str:
        return f"{self.local_registry}/nanofaas/function-runtime:e2e"

    @property
    def _cli_bin_dir(self) -> str:
        return f"{self._remote_dir}/nanofaas-cli/build/install/nanofaas-cli/bin"

    def _cli_exec(self, command: str, endpoint: str) -> str:
        return self._vm_exec(
            f"export PATH=$PATH:{self._cli_bin_dir}; "
            f"export NANOFAAS_ENDPOINT={endpoint}; "
            f"export NANOFAAS_NAMESPACE={self.namespace}; "
            f"{command}"
        )

    def _build_artifacts(self) -> None:
        print("[e2e-cli] Building control-plane artifacts")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./scripts/controlplane.sh jar --profile k8s -- --quiet"
        )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./gradlew :function-runtime:bootJar --quiet"
        )

    def _build_images(self) -> None:
        print("[e2e-cli] Building and pushing images")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"sudo docker build -f control-plane/Dockerfile -t {self._control_image} control-plane/"
        )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"sudo docker build -t {self._runtime_image} function-runtime/"
        )
        self._vm_exec(f"sudo docker push {self._control_image}")
        self._vm_exec(f"sudo docker push {self._runtime_image}")

    def _deploy_platform(self) -> None:
        print("[e2e-cli] Deploying platform to k3s")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"kubectl create namespace {self.namespace} --dry-run=client -o yaml | kubectl apply -f -"
        )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"helm upgrade --install control-plane helm/nanofaas "
            f"-n {self.namespace} "
            f"--set controlPlane.image.repository={self._control_image.split(':')[0]} "
            f"--set controlPlane.image.tag={self._control_image.split(':')[-1]} "
            f"--wait --timeout 3m"
        )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"kubectl rollout status deployment/function-runtime -n {self.namespace} --timeout=2m"
        )

    def _build_cli(self) -> None:
        if self.skip_cli_build:
            print("[e2e-cli] Skipping CLI build (NANOFAAS_CLI_SKIP_INSTALL_DIST=true)")
            return
        print("[e2e-cli] Building CLI in VM")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./gradlew :nanofaas-cli:installDist --no-daemon -q"
        )

    def _resolve_endpoint(self) -> str:
        cluster_ip = self._vm_exec(
            f"kubectl get svc control-plane -n {self.namespace} -o jsonpath='{{.spec.clusterIP}}'"
        )
        if not cluster_ip:
            raise RuntimeError("Failed to resolve control-plane ClusterIP")
        return f"http://{cluster_ip}:8080"

    def _run_cli_tests(
        self,
        resolved: "ResolvedScenario | None",
        endpoint: str,
    ) -> None:
        functions = _selected_functions(resolved)
        for fn_key in functions:
            fn_image = _function_image(fn_key, resolved, self._runtime_image)
            print(f"[e2e-cli] Testing CLI function lifecycle for '{fn_key}'")
            spec = (
                f"{{\"name\":\"{fn_key}\","
                f"\"image\":\"{fn_image}\","
                f"\"timeoutMs\":5000,\"concurrency\":2,\"queueSize\":20,"
                f"\"maxRetries\":3,\"executionMode\":\"DEPLOYMENT\"}}"
            )
            self._vm_exec(f"printf '%s' '{spec}' > /tmp/{fn_key}.json")
            self._cli_exec(f"nanofaas fn apply -f /tmp/{fn_key}.json", endpoint)
            list_output = self._cli_exec("nanofaas fn list", endpoint)
            if fn_key not in list_output:
                raise RuntimeError(f"fn list missing {fn_key}")
            invoke_input = '{"input":{"message":"hello-from-cli"}}'
            invoke_out = self._cli_exec(
                f"nanofaas invoke {fn_key} -d '{invoke_input}'", endpoint
            )
            if '"success"' not in invoke_out:
                raise RuntimeError(f"invoke did not succeed for {fn_key}: {invoke_out}")
            enqueue_out = self._cli_exec(
                f"nanofaas enqueue {fn_key} -d '{invoke_input}'", endpoint
            )
            if '"executionId"' not in enqueue_out:
                raise RuntimeError(f"enqueue did not return executionId for {fn_key}")
            self._cli_exec(f"nanofaas fn delete {fn_key}", endpoint)

    def run(self, scenario_file: Path | None = None) -> None:
        resolved = _resolve_scenario(scenario_file)
        skip_bootstrap = os.getenv("E2E_SKIP_VM_BOOTSTRAP", "").lower() == "true"

        if not skip_bootstrap:
            raise RuntimeError(
                "CliVmRunner requires VM to be bootstrapped. "
                "Set E2E_SKIP_VM_BOOTSTRAP=true and ensure VM is running, or use E2eRunner."
            )

        self._build_artifacts()
        self._build_images()
        self._deploy_platform()
        self._build_cli()
        endpoint = self._resolve_endpoint()
        self._run_cli_tests(resolved, endpoint)
        print("[e2e-cli] CLI E2E workflow PASSED")


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
        print("[e2e-host-cli] Building nanofaas-cli on host...")
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

            print("[e2e-host-cli] Running platform lifecycle from host CLI...")
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

            print("[e2e-host-cli] Host CLI platform lifecycle test: PASSED")
        finally:
            kubeconfig.unlink(missing_ok=True)
