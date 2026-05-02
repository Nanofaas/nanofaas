"""
cli_vm_runner.py

CliVmRunner: CLI E2E workflow inside a VM-backed environment.

Mirrors the logic of the deleted e2e-cli-backend.sh (M10).
"""
from __future__ import annotations

from tui_toolkit import phase, success, warning, fail, status, workflow_log, workflow_step
from tui_toolkit.console import console

import os
import shlex
from pathlib import Path
from typing import TYPE_CHECKING

from controlplane_tool.scenario_helpers import (
    function_image as _function_image,
    resolve_scenario as _resolve_scenario,
    selected_functions as _selected_functions,
)
from controlplane_tool.scenario_tasks import (
    build_core_images_vm_script,
    helm_namespace_install_vm_script,
    helm_upgrade_install_vm_script,
)
from controlplane_tool.shell_backend import SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest, vm_request_from_env
from controlplane_tool.workflow_progress import WorkflowProgressReporter

if TYPE_CHECKING:
    from controlplane_tool.scenario_models import ResolvedScenario


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

    @property
    def _kubeconfig_path(self) -> str:
        return self._vm.kubeconfig_path(self.vm_request)

    def _cli_exec(self, command: str, endpoint: str) -> str:
        return self._vm_exec(
            f"export PATH=$PATH:{self._cli_bin_dir}; "
            f"export NANOFAAS_ENDPOINT={endpoint}; "
            f"export NANOFAAS_NAMESPACE={self.namespace}; "
            f"{command}"
        )

    def _build_artifacts(self) -> None:
        workflow_log("Building control-plane artifacts")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./scripts/controlplane.sh jar --profile k8s -- --quiet"
        )
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./gradlew :function-runtime:bootJar --quiet"
        )

    def _build_images(self) -> None:
        workflow_log("Building and pushing images")
        self._vm_exec(
            build_core_images_vm_script(
                remote_dir=self._remote_dir,
                control_image=self._control_image,
                runtime_image=self._runtime_image,
                runtime=self.runtime,
                mode="docker",
                sudo=True,
            )
        )

    def _deploy_platform(self) -> None:
        workflow_log("Deploying platform to k3s")
        self._vm_exec(
            helm_namespace_install_vm_script(
                remote_dir=self._remote_dir,
                namespace=self.namespace,
                kubeconfig_path=self._kubeconfig_path,
            )
        )
        self._vm_exec(
            helm_upgrade_install_vm_script(
                remote_dir=self._remote_dir,
                release="control-plane",
                chart="helm/nanofaas",
                namespace=self.namespace,
                values={
                    "namespace.create": "false",
                    "namespace.name": self.namespace,
                    "controlPlane.image.repository": self._control_image.split(":")[0],
                    "controlPlane.image.tag": self._control_image.split(":")[-1],
                },
                kubeconfig_path=self._kubeconfig_path,
            )
        )

    def _build_cli(self) -> None:
        if self.skip_cli_build:
            workflow_log("Skipping CLI build (NANOFAAS_CLI_SKIP_INSTALL_DIST=true)")
            return
        workflow_log("Building CLI in VM")
        self._vm_exec(
            f"cd {self._remote_dir} && "
            f"./gradlew :nanofaas-cli:installDist --no-daemon -q"
        )

    def _resolve_endpoint(self) -> str:
        cluster_ip = self._vm_exec(
            f"KUBECONFIG={shlex.quote(self._kubeconfig_path)} "
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
        reporter = WorkflowProgressReporter.current()
        for fn_key in functions:
            fn_image = _function_image(fn_key, resolved, self._runtime_image)
            with reporter.child(
                f"cli.vm.verify.{fn_key}",
                f"Testing CLI function lifecycle for '{fn_key}'",
            ):
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

        phase("Build")
        with workflow_step(task_id="cli.vm.build", title="Build"):
            self._build_artifacts()
            self._build_images()

        phase("Deploy")
        with workflow_step(task_id="cli.vm.deploy", title="Deploy"):
            self._deploy_platform()

        phase("Verify")
        with workflow_step(task_id="cli.vm.verify", title="Verify"):
            self._build_cli()
            endpoint = self._resolve_endpoint()
            self._run_cli_tests(resolved, endpoint)
        success("CLI E2E workflow")
