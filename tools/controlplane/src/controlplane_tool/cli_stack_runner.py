"""
cli_stack_runner.py

CliStackRunner: dedicated VM-backed CLI evaluation workflow.
"""
from __future__ import annotations

import os
import shlex
from pathlib import Path

from controlplane_tool.cli_platform_workflow import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)
from controlplane_tool.console import phase, step, success
from controlplane_tool.e2e_runner import ScenarioPlanStep
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
from controlplane_tool.vm_models import VmRequest, vm_request_from_env


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
        self.vm_request = vm_request or vm_request_from_env()
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
        selected_functions = _selected_functions(resolved_scenario)
        function_specs = function_image_specs(resolved_scenario, self._runtime_image)

        build_functions_script = (
            build_function_images_vm_script(
                remote_dir=self._remote_dir,
                functions=function_specs,
                sudo=True,
                push=True,
            )
            if function_specs
            else f"cd {shlex.quote(self._remote_dir)} && echo 'No selected function images to build'"
        )

        install_command = shlex.join(
            platform_install_command(
                repo_root=self.repo_root,
                release=self.release,
                namespace=self.namespace,
                control_plane_image=self._control_image,
            )
        )
        status_command = shlex.join(platform_status_command(self.namespace))
        uninstall_command = shlex.join(
            platform_uninstall_command(release=self.release, namespace=self.namespace)
        )

        build_cli_step = (
            self._remote_exec_step(
                "Build nanofaas-cli installDist in VM",
                f"cd {shlex.quote(self._remote_dir)} && ./gradlew :nanofaas-cli:installDist --no-daemon -q",
            )
            if not self.skip_cli_build
            else self._remote_exec_step(
                "Build nanofaas-cli installDist in VM",
                f"test -x {shlex.quote(self._cli_bin_dir + '/nanofaas')}",
            )
        )

        apply_lines = [f"cd {shlex.quote(self._remote_dir)}", self._cli_env_prefix(endpoint=self._cluster_endpoint_expr())]
        list_lines = [f"cd {shlex.quote(self._remote_dir)}", self._cli_env_prefix(endpoint=self._cluster_endpoint_expr()), "out=$(nanofaas fn list)", 'printf "%s\\n" "$out"']
        invoke_lines = [f"cd {shlex.quote(self._remote_dir)}", self._cli_env_prefix(endpoint=self._cluster_endpoint_expr())]
        enqueue_lines = [f"cd {shlex.quote(self._remote_dir)}", self._cli_env_prefix(endpoint=self._cluster_endpoint_expr())]
        delete_lines = [f"cd {shlex.quote(self._remote_dir)}", self._cli_env_prefix(endpoint=self._cluster_endpoint_expr())]

        for fn_key in selected_functions:
            image = _function_image(fn_key, resolved_scenario, self._runtime_image)
            payload = _function_payload(fn_key, resolved_scenario, default_message="hello-from-cli-stack")
            spec = (
                f'{{"name":"{fn_key}","image":"{image}","timeoutMs":5000,'
                '"concurrency":2,"queueSize":20,"maxRetries":3,"executionMode":"DEPLOYMENT"}'
            )
            apply_lines.extend(
                [
                    f"printf '%s' {shlex.quote(spec)} > /tmp/{shlex.quote(fn_key)}.json",
                    f"nanofaas fn apply -f /tmp/{shlex.quote(fn_key)}.json",
                ]
            )
            list_lines.append(f'printf "%s\\n" "$out" | grep -q {shlex.quote(fn_key)}')
            invoke_lines.extend(
                [
                    f"invoke_out=$(nanofaas invoke {shlex.quote(fn_key)} -d {shlex.quote(payload)})",
                    'printf "%s\\n" "$invoke_out"',
                    'printf "%s\\n" "$invoke_out" | grep -q \'"success"\'',
                ]
            )
            enqueue_lines.extend(
                [
                    f"enqueue_out=$(nanofaas enqueue {shlex.quote(fn_key)} -d {shlex.quote(payload)})",
                    'printf "%s\\n" "$enqueue_out"',
                    'printf "%s\\n" "$enqueue_out" | grep -q \'"executionId"\'',
                ]
            )
            delete_lines.append(f"nanofaas fn delete {shlex.quote(fn_key)}")

        return [
            self._remote_exec_step(
                "Build control-plane and runtime images in VM",
                build_core_images_vm_script(
                    remote_dir=self._remote_dir,
                    control_image=self._control_image,
                    runtime_image=self._runtime_image,
                    runtime=self.runtime,
                    mode="docker",
                    sudo=True,
                    build_jars=True,
                ),
            ),
            self._remote_exec_step(
                "Build selected function images in VM",
                build_functions_script,
            ),
            build_cli_step,
            self._remote_exec_step(
                "Install nanofaas into k3s through the CLI",
                " && ".join(
                    [
                        f"cd {shlex.quote(self._remote_dir)}",
                        self._cli_env_prefix(),
                        f'install_out=$({install_command})',
                        'printf "%s\\n" "$install_out"',
                        f'printf "%s\\n" "$install_out" | grep -q {shlex.quote(f"endpoint\\thttp://{self._resolve_public_host()}:30080")}',
                    ]
                ),
            ),
            self._remote_exec_step(
                "Run platform status",
                " && ".join(
                    [
                        f"cd {shlex.quote(self._remote_dir)}",
                        self._cli_env_prefix(),
                        f'status_out=$({status_command})',
                        'printf "%s\\n" "$status_out"',
                        'printf "%s\\n" "$status_out" | grep -q $\'deployment\\tnanofaas-control-plane\\t1/1\'',
                    ]
                ),
            ),
            self._remote_exec_step(
                "Apply or register the selected functions",
                " && ".join(apply_lines),
            ),
            self._remote_exec_step(
                "Run fn list",
                " && ".join(list_lines),
            ),
            self._remote_exec_step(
                "Run synchronous invoke checks",
                " && ".join(invoke_lines),
            ),
            self._remote_exec_step(
                "Run enqueue checks",
                " && ".join(enqueue_lines),
            ),
            self._remote_exec_step(
                "Delete the selected functions",
                " && ".join(delete_lines),
            ),
            self._remote_exec_step(
                "Uninstall nanofaas",
                " && ".join(
                    [
                        f"cd {shlex.quote(self._remote_dir)}",
                        self._cli_env_prefix(),
                        uninstall_command,
                    ]
                ),
            ),
            self._remote_exec_step(
                "Verify cli-stack status fails",
                " && ".join(
                    [
                        f"cd {shlex.quote(self._remote_dir)}",
                        self._cli_env_prefix(),
                        f"if {status_command}; then exit 1; fi",
                    ]
                ),
            ),
        ]

    def run(self, scenario_file: Path | None = None) -> None:
        resolved = _resolve_scenario(scenario_file)
        skip_bootstrap = os.getenv("E2E_SKIP_VM_BOOTSTRAP", "").lower() == "true"
        if not skip_bootstrap:
            raise RuntimeError(
                "CliStackRunner requires VM to be bootstrapped. "
                "Set E2E_SKIP_VM_BOOTSTRAP=true and ensure VM is running, or use cli-test/e2e orchestration."
            )

        phase("Verify")
        for planned_step in self.plan_steps(resolved):
            step(planned_step.summary)
            result = self._shell.run(planned_step.command, cwd=self.repo_root, env=planned_step.env, dry_run=False)
            if result.return_code != 0:
                raise RuntimeError(result.stderr or result.stdout or f"{planned_step.summary} failed")
        success("CLI stack workflow")
