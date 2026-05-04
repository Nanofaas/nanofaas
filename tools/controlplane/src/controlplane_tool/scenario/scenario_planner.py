"""
scenario_planner.py — Builds ordered ScenarioPlanStep lists for each scenario type.

Extracted from E2eRunner. Owns all step-construction logic.
"""
from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.k3s_curl_runner import K3sCurlRunner
from controlplane_tool.app.paths import ToolPaths
from controlplane_tool.scenario.components.bootstrap import plan_loadtest_install_k6
from controlplane_tool.scenario.components.environment import resolve_scenario_environment
from controlplane_tool.scenario.components.executor import ScenarioPlanStep, operation_to_plan_step
from controlplane_tool.scenario.components.verification import plan_loadtest_run
from controlplane_tool.scenario.scenario_manifest import write_scenario_manifest
from controlplane_tool.scenario.scenario_tasks import (
    helm_namespace_uninstall_vm_script,
    helm_uninstall_vm_script,
    k8s_e2e_test_vm_script,
)
from controlplane_tool.core.shell_backend import ShellBackend, ShellExecutionResult
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_cluster_workflows import (
    build_vm_cluster_prelude_plan,
    control_image,
    function_image_specs,
    runtime_image,
)
from controlplane_tool.infra.vm.vm_models import VmRequest


class ScenarioPlanner:
    """Builds step lists for every supported scenario type."""

    def __init__(
        self,
        paths: ToolPaths,
        vm: VmOrchestrator,
        shell: ShellBackend,
        manifest_root: Path,
    ) -> None:
        self.paths = paths
        self.vm = vm
        self.shell = shell
        self.manifest_root = manifest_root

    # ── private step builders ────────────────────────────────────────────────

    def _step(
        self,
        summary: str,
        command: list[str],
        *,
        env: dict[str, str] | None = None,
        step_id: str,
    ) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=command, env=env or {}, step_id=step_id)

    def _step_from_result(
        self,
        summary: str,
        result: ShellExecutionResult,
        *,
        step_id: str,
    ) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=result.command, env=result.env, step_id=step_id)

    def _backend_step(
        self,
        summary: str,
        script_name: str,
        *,
        env: dict[str, str] | None = None,
    ) -> ScenarioPlanStep:
        script = self.paths.workspace_root / "scripts" / "lib" / script_name
        return self._step(summary, ["bash", str(script)], env=env, step_id=f"backend.{script_name}")

    def _remote_exec_step(
        self,
        summary: str,
        request: VmRequest,
        command: str,
        *,
        step_id: str = "",
    ) -> ScenarioPlanStep:
        result = self.vm.remote_exec(request, command=command, dry_run=True)
        return self._step_from_result(summary, result, step_id=step_id)

    def _run_remote_operation(
        self,
        request: E2eRequest,
        argv: tuple[str, ...],
        env: Mapping[str, str],
    ) -> None:
        vm_request = self._require_vm(request)
        result = self.vm.exec_argv(
            vm_request,
            argv,
            env=dict(env),
            cwd=self.vm.remote_project_dir(vm_request),
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError((result.stderr or result.stdout or f"exit {result.return_code}").strip())

    # ── environment helpers ──────────────────────────────────────────────────

    def _with_manifest_env(
        self,
        request: E2eRequest,
        env: dict[str, str] | None = None,
    ) -> dict[str, str]:
        resolved_env = dict(env or {})
        manifest_path = self._manifest_path(request)
        if manifest_path is None:
            return resolved_env
        resolved_env["NANOFAAS_SCENARIO_PATH"] = str(manifest_path)
        return resolved_env

    def _manifest_path(self, request: E2eRequest) -> Path | None:
        if request.resolved_scenario is None:
            return None
        return write_scenario_manifest(request.resolved_scenario, root=self.manifest_root)

    def _remote_manifest_path(self, request: VmRequest, local_manifest: Path) -> str:
        return self.vm.remote_path_for_local(
            request,
            local_manifest,
            fallback_subdir="tools/controlplane/runs/manifests",
        )

    def _require_vm(self, request: E2eRequest) -> VmRequest:
        if request.vm is None:
            raise ValueError(f"Scenario '{request.scenario}' requires VM configuration")
        return request.vm

    def _effective_namespace(self, request: E2eRequest) -> str:
        if request.namespace:
            return request.namespace
        if request.resolved_scenario is not None and request.resolved_scenario.namespace:
            return request.resolved_scenario.namespace
        return "nanofaas-e2e"

    def _common_env(self, request: E2eRequest) -> dict[str, str]:
        env = {
            "CONTROL_PLANE_RUNTIME": request.runtime,
            "LOCAL_REGISTRY": request.local_registry,
            "NAMESPACE": self._effective_namespace(request),
        }
        return env

    def _vm_env(self, request: E2eRequest) -> dict[str, str]:
        vm_request = self._require_vm(request)
        env = self._common_env(request)
        env.update(
            {
                "VM_NAME": self.vm.vm_name(vm_request),
                "CPUS": str(vm_request.cpus),
                "MEMORY": vm_request.memory,
                "DISK": vm_request.disk,
                "KEEP_VM": "true",
                "E2E_SKIP_VM_BOOTSTRAP": "true",
                "E2E_VM_LIFECYCLE": vm_request.lifecycle,
                "E2E_VM_USER": vm_request.user,
                "E2E_REMOTE_PROJECT_DIR": self.vm.remote_project_dir(vm_request),
                "E2E_KUBECONFIG_PATH": self.vm.kubeconfig_path(vm_request),
            }
        )
        if vm_request.host:
            env["E2E_VM_HOST"] = vm_request.host
            env["E2E_PUBLIC_HOST"] = vm_request.host
        elif vm_request.lifecycle == "multipass":
            placeholder = f"<multipass-ip:{self.vm.vm_name(vm_request)}>"
            env["E2E_VM_HOST"] = placeholder
            env["E2E_PUBLIC_HOST"] = placeholder
        if vm_request.home:
            env["E2E_VM_HOME"] = vm_request.home
        return env

    # ── image helpers ────────────────────────────────────────────────────────

    def _control_image(self, request: E2eRequest) -> str:
        return control_image(request.local_registry)

    def _runtime_image(self, request: E2eRequest) -> str:
        return runtime_image(request.local_registry)

    def _function_image_specs(self, request: E2eRequest) -> list[tuple[str, str, str]]:
        return function_image_specs(request.resolved_scenario, self._runtime_image(request))

    def _k3s_curl_runner(self, request: E2eRequest) -> K3sCurlRunner:
        return K3sCurlRunner(
            self.paths.workspace_root,
            vm_request=self._require_vm(request),
            namespace=self._effective_namespace(request),
            local_registry=request.local_registry,
            runtime=request.runtime,
            shell=self.shell,
        )

    # ── public step builders ─────────────────────────────────────────────────

    def local_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        if request.scenario == "docker":
            return [
                self._step(
                    "Run Docker POOL regression test",
                    [
                        "./scripts/controlplane.sh",
                        "test",
                        "--profile",
                        "all",
                        "--",
                        "-PrunE2e",
                        "--tests",
                        "it.unimib.datai.nanofaas.controlplane.e2e.E2eFlowTest",
                    ],
                    step_id="docker.e2e_flow",
                )
            ]

        if request.scenario == "buildpack":
            return [
                self._step(
                    "Build function-runtime buildpack image",
                    [
                        "./gradlew",
                        ":function-runtime:bootBuildImage",
                        "-PfunctionRuntimeImage=nanofaas/function-runtime:buildpack",
                    ],
                    step_id="buildpack.boot_build_image",
                ),
                self._step(
                    "Run buildpack regression tests",
                    [
                        "./scripts/controlplane.sh",
                        "test",
                        "--profile",
                        "all",
                        "--",
                        "-PrunE2e",
                        "--tests",
                        "it.unimib.datai.nanofaas.controlplane.e2e.BuildpackE2eTest",
                        "--tests",
                        "it.unimib.datai.nanofaas.controlplane.e2e.ContainerLocalE2eTest",
                    ],
                    step_id="buildpack.e2e_tests",
                ),
            ]

        if request.scenario == "container-local":
            return [
                self._step(
                    "Run container-local managed DEPLOYMENT flow (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "e2e", "run", "container-local",
                    ],
                    env=self._with_manifest_env(request, self._common_env(request)),
                    step_id="container-local.run_flow",
                )
            ]

        if request.scenario == "deploy-host":
            return [
                self._step(
                    "Run deploy-host E2E flow (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "cli-test", "run", "deploy-host",
                    ],
                    env=self._with_manifest_env(request, self._common_env(request)),
                    step_id="deploy-host.run_flow",
                )
            ]

        raise ValueError(f"Unsupported local scenario: {request.scenario}")

    def k3s_vm_prelude_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_request = self._require_vm(request)
        prelude = build_vm_cluster_prelude_plan(
            vm=self.vm,
            vm_request=vm_request,
            namespace=self._effective_namespace(request),
            local_registry=request.local_registry,
            runtime=request.runtime,
            resolved_scenario=request.resolved_scenario,
        )

        ensure_step = self._step_from_result(
            "Ensure VM is running",
            prelude.ensure_running,
            step_id="vm.ensure_running",
        )

        if prelude.build_selected_functions_script is not None:
            build_selected_functions_step = self._remote_exec_step(
                "Build selected function images in VM",
                vm_request,
                prelude.build_selected_functions_script,
                step_id="images.build_selected_functions",
            )
        else:
            build_selected_functions_step = self._step(
                "Build selected function images in VM",
                ["echo", "No selected function images to building"],
                step_id="images.build_selected_functions",
            )

        return [
            ensure_step,
            self._step_from_result(
                "Provision base VM dependencies",
                prelude.install_dependencies,
                step_id="vm.provision_base",
            ),
            self._step_from_result("Sync project to VM", prelude.sync_project, step_id="repo.sync_to_vm"),
            self._step_from_result(
                "Ensure registry container",
                prelude.ensure_registry,
                step_id="registry.ensure_container",
            ),
            self._remote_exec_step(
                "Build control-plane and runtime images in VM",
                vm_request,
                prelude.build_core_script,
                step_id="images.build_core",
            ),
            build_selected_functions_step,
            self._step_from_result("Install k3s", prelude.install_k3s, step_id="k3s.install"),
            self._step_from_result(
                "Configure k3s registry",
                prelude.configure_registry,
                step_id="k3s.configure_registry",
            ),
            self._remote_exec_step(
                "Install namespace Helm release",
                vm_request,
                prelude.install_namespace_script,
                step_id="namespace.install",
            ),
            self._remote_exec_step(
                "Deploy control-plane via Helm",
                vm_request,
                prelude.deploy_control_plane_script,
                step_id="helm.deploy_control_plane",
            ),
            self._remote_exec_step(
                "Deploy function-runtime via Helm",
                vm_request,
                prelude.deploy_function_runtime_script,
                step_id="helm.deploy_function_runtime",
            ),
        ]

    def k3s_junit_curl_tail_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_request = self._require_vm(request)
        remote_dir = self.vm.remote_project_dir(vm_request)
        kubeconfig_path = self.vm.kubeconfig_path(vm_request)
        namespace = self._effective_namespace(request)
        manifest_path = self._manifest_path(request)
        remote_manifest_path = (
            self._remote_manifest_path(vm_request, manifest_path)
            if manifest_path is not None
            else None
        )
        runtime_img = self._runtime_image(request)
        verifier = self._k3s_curl_runner(request)

        if request.cleanup_vm:
            uninstall_runtime_step = self._remote_exec_step(
                "Uninstall function-runtime Helm release",
                vm_request,
                helm_uninstall_vm_script(
                    remote_dir=remote_dir,
                    release="function-runtime",
                    namespace=namespace,
                    kubeconfig_path=kubeconfig_path,
                ),
                step_id="cleanup.uninstall_function_runtime",
            )
            uninstall_control_plane_step = self._remote_exec_step(
                "Uninstall control-plane Helm release",
                vm_request,
                helm_uninstall_vm_script(
                    remote_dir=remote_dir,
                    release="control-plane",
                    namespace=namespace,
                    kubeconfig_path=kubeconfig_path,
                ),
                step_id="cleanup.uninstall_control_plane",
            )
            uninstall_namespace_step = self._remote_exec_step(
                "Uninstall namespace Helm release",
                vm_request,
                helm_namespace_uninstall_vm_script(
                    remote_dir=remote_dir,
                    namespace=namespace,
                    kubeconfig_path=kubeconfig_path,
                ),
                step_id="namespace.uninstall",
            )
            teardown_dry = self.vm.teardown(vm_request, dry_run=True)
            teardown_step = ScenarioPlanStep(
                summary="Teardown VM",
                command=teardown_dry.command,
                env=teardown_dry.env,
                step_id="vm.down",
                action=lambda: self.vm.teardown(vm_request, dry_run=False),
            )
        else:
            uninstall_runtime_step = self._step(
                "Uninstall function-runtime Helm release",
                ["echo", "Skipping function-runtime cleanup (--no-cleanup-vm)"],
                step_id="cleanup.uninstall_function_runtime",
            )
            uninstall_control_plane_step = self._step(
                "Uninstall control-plane Helm release",
                ["echo", "Skipping control-plane cleanup (--no-cleanup-vm)"],
                step_id="cleanup.uninstall_control_plane",
            )
            uninstall_namespace_step = self._step(
                "Uninstall namespace Helm release",
                ["echo", "Skipping namespace cleanup (--no-cleanup-vm)"],
                step_id="namespace.uninstall",
            )
            teardown_step = self._step(
                "Teardown VM",
                ["echo", "Skipping VM teardown (--no-cleanup-vm)"],
                step_id="vm.down",
            )

        return [
            ScenarioPlanStep(
                summary="Run k3s-junit-curl verification",
                command=["python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack"],
                step_id="tests.run_k3s_curl_checks",
                action=lambda: verifier.verify_existing_stack(request.resolved_scenario),
            ),
            self._remote_exec_step(
                "Run K8sE2eTest in VM",
                vm_request,
                k8s_e2e_test_vm_script(
                    remote_dir=remote_dir,
                    kubeconfig_path=kubeconfig_path,
                    runtime_image=runtime_img,
                    namespace=namespace,
                    remote_manifest_path=remote_manifest_path,
                ),
                step_id="tests.run_k8s_junit",
            ),
            uninstall_runtime_step,
            uninstall_control_plane_step,
            uninstall_namespace_step,
            teardown_step,
        ]

    def k3s_junit_curl_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        return [
            *self.k3s_vm_prelude_steps(request),
            *self.k3s_junit_curl_tail_steps(request),
        ]

    def helm_stack_tail_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        context = resolve_scenario_environment(
            self.paths.workspace_root,
            request,
            manifest_root=self.manifest_root,
        )
        vm_env = self._vm_env(request)
        if request.helm_noninteractive:
            vm_env = {**vm_env, "E2E_K3S_HELM_NONINTERACTIVE": "true"}
        vm_env = self._with_manifest_env(request, vm_env)
        controlplane_tool_project = self.paths.workspace_root / "tools" / "controlplane"
        return [
            operation_to_plan_step(
                plan_loadtest_install_k6(context)[0],
                request=request,
            ),
            operation_to_plan_step(
                plan_loadtest_run(context)[0],
                request=request,
                on_remote_exec=lambda argv, env: self._run_remote_operation(request, argv, env),
            ),
            self._step(
                "Run autoscaling experiment (Python)",
                [
                    "uv",
                    "run",
                    "--project",
                    str(controlplane_tool_project),
                    "--locked",
                    "python",
                    str(self.paths.workspace_root / "experiments" / "autoscaling.py"),
                ],
                env=vm_env,
                step_id="experiments.autoscaling",
            ),
        ]

    def vm_bootstrap_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_request = self._require_vm(request)
        ensure_dry = self.vm.ensure_running(vm_request, dry_run=True)
        ensure_step = self._step_from_result("Ensure VM is running", ensure_dry, step_id="vm.ensure_running")
        return [
            ensure_step,
            self._step_from_result(
                "Provision base VM dependencies",
                self.vm.install_dependencies(vm_request, install_helm=True, dry_run=True),
                step_id="vm.provision_base",
            ),
            self._step_from_result(
                "Sync project to VM",
                self.vm.sync_project(vm_request, dry_run=True),
                step_id="repo.sync_to_vm",
            ),
            self._step_from_result(
                "Install k3s",
                self.vm.install_k3s(vm_request, dry_run=True),
                step_id="k3s.install",
            ),
            self._step_from_result(
                "Ensure registry container",
                self.vm.ensure_registry_container(
                    vm_request,
                    registry=request.local_registry,
                    dry_run=True,
                ),
                step_id="registry.ensure_container",
            ),
            self._step_from_result(
                "Configure k3s registry",
                self.vm.configure_k3s_registry(
                    vm_request,
                    registry=request.local_registry,
                    dry_run=True,
                ),
                step_id="k3s.configure_registry",
            ),
        ]

    def vm_scenario_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        if request.scenario == "k3s-junit-curl":
            return self.k3s_junit_curl_steps(request)

        if request.scenario == "cli":
            return [
                self._step(
                    "Run CLI E2E workflow inside VM (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "cli-test", "run", "vm",
                    ],
                    env=self._with_manifest_env(request, self._vm_env(request)),
                    step_id="cli.vm_e2e_flow",
                )
            ]

        if request.scenario == "cli-stack":
            return [
                self._step(
                    "Run dedicated cli-stack workflow inside VM (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "cli-test", "run", "cli-stack",
                    ],
                    env=self._with_manifest_env(request, self._vm_env(request)),
                    step_id="cli_stack.vm_e2e_flow",
                )
            ]

        if request.scenario == "cli-host":
            return [
                self._step(
                    "Run host CLI platform lifecycle (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "cli-test", "run", "host-platform",
                    ],
                    env=self._with_manifest_env(request, self._vm_env(request)),
                    step_id="cli.host_platform_flow",
                )
            ]

        raise ValueError(f"Unsupported VM-backed scenario: {request.scenario}")

    def vm_backed_steps(
        self,
        request: E2eRequest,
        *,
        include_bootstrap: bool = True,
    ) -> list[ScenarioPlanStep]:
        if request.scenario == "k3s-junit-curl":
            return self.k3s_junit_curl_steps(request)
        if request.scenario == "helm-stack":
            return [
                *self.k3s_vm_prelude_steps(request),
                *self.helm_stack_tail_steps(request),
            ]
        steps = []
        if include_bootstrap:
            steps.extend(self.vm_bootstrap_steps(request))
        steps.extend(self.vm_scenario_steps(request))
        return steps
