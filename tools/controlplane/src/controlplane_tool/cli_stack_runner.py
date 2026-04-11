"""
cli_stack_runner.py

CliStackRunner: dedicated VM-backed CLI evaluation workflow.
"""
from __future__ import annotations

import os
import shlex
from pathlib import Path

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.e2e_runner import ScenarioPlanStep, plan_recipe_steps
from controlplane_tool.cli_platform_workflow import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)
from controlplane_tool.console import phase, step, success
from controlplane_tool.scenario_components.environment import default_managed_vm_request
from controlplane_tool.scenario_helpers import (
    function_image as _function_image,
    function_payload as _function_payload,
    resolve_scenario as _resolve_scenario,
    selected_functions as _selected_functions,
)
from controlplane_tool.scenario_tasks import (
    build_core_images_vm_script,
    build_function_images_vm_script,
)
from controlplane_tool.shell_backend import SubprocessShell
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_cluster_workflows import control_image, function_image_specs, runtime_image
from controlplane_tool.vm_models import VmRequest


class CliStackRunner:
    """Run the dedicated VM-backed CLI evaluation workflow."""

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str = "nanofaas-cli-stack-e2e",
        release: str = "nanofaas-cli-stack-e2e",
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        skip_cli_build: bool = False,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or default_managed_vm_request()
        self.namespace = namespace
        self.release = release
        self.local_registry = local_registry
        self.runtime = runtime
        self.skip_cli_build = skip_cli_build
        self._shell = SubprocessShell()
        self._vm = VmOrchestrator(self.repo_root, shell=self._shell)

    @property
    def _remote_dir(self) -> str:
        return self._vm.remote_project_dir(self.vm_request)

    @property
    def _kubeconfig_path(self) -> str:
        return self._vm.kubeconfig_path(self.vm_request)

    @property
    def _control_image(self) -> str:
        return control_image(self.local_registry)

    @property
    def _runtime_image(self) -> str:
        return runtime_image(self.local_registry)

    @property
    def _cli_bin_dir(self) -> str:
        return f"{self._remote_dir}/nanofaas-cli/build/install/nanofaas-cli/bin"

    def _remote_exec_step(self, summary: str, command: str) -> ScenarioPlanStep:
        result = self._vm.remote_exec(self.vm_request, command=command, dry_run=True)
        return ScenarioPlanStep(summary=summary, command=result.command, env=result.env)

    def _resolve_public_host(self) -> str:
        host = os.getenv("E2E_PUBLIC_HOST", "") or os.getenv("E2E_VM_HOST", "")
        if host:
            return host
        if self.vm_request.lifecycle == "external" and self.vm_request.host:
            return self.vm_request.host
        return f"<multipass-ip:{self._vm.vm_name(self.vm_request)}>"

    def _cli_env_prefix(self, *, endpoint: str | None = None) -> str:
        parts = [
            f"PATH=$PATH:{shlex.quote(self._cli_bin_dir)}",
            f"KUBECONFIG={shlex.quote(self._kubeconfig_path)}",
            f"NANOFAAS_NAMESPACE={shlex.quote(self.namespace)}",
        ]
        if endpoint is not None:
            parts.append(f"NANOFAAS_ENDPOINT={shlex.quote(endpoint)}")
        return "export " + " ".join(parts)

    def _cluster_endpoint_expr(self) -> str:
        return (
            "http://$(KUBECONFIG="
            f"{shlex.quote(self._kubeconfig_path)} "
            f"kubectl get svc control-plane -n {shlex.quote(self.namespace)} "
            "-o jsonpath='{.spec.clusterIP}'):8080"
        )

    def plan_steps(self, resolved_scenario=None) -> list[ScenarioPlanStep]:
        request = E2eRequest(
            scenario="cli-stack",
            runtime=self.runtime,
            resolved_scenario=resolved_scenario,
            vm=self.vm_request,
            namespace=self.namespace,
            local_registry=self.local_registry,
        )
        return plan_recipe_steps(
            self.repo_root,
            request,
            "cli-stack",
            release=self.release,
        )

    def run(self, scenario_file: Path | None = None) -> None:
        resolved = _resolve_scenario(scenario_file)
        phase("Verify")
        for planned_step in self.plan_steps(resolved):
            step(planned_step.summary)
            result = self._shell.run(planned_step.command, cwd=self.repo_root, env=planned_step.env, dry_run=False)
            if result.return_code != 0:
                raise RuntimeError(result.stderr or result.stdout or f"{planned_step.summary} failed")
        success("CLI stack workflow")
