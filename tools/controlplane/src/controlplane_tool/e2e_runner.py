from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Literal

from multipass import MultipassClient

from controlplane_tool.e2e_catalog import ScenarioDefinition, list_scenarios, resolve_scenario
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.console import bind_workflow_context
from controlplane_tool.paths import ToolPaths
from controlplane_tool.k3s_curl_runner import K3sCurlRunner
from controlplane_tool.scenario_components.cli import CliComponentContext
from controlplane_tool.scenario_components.composer import compose_recipe
from controlplane_tool.scenario_components.environment import (
    ScenarioExecutionContext,
    resolve_scenario_environment,
)
from controlplane_tool.scenario_components.executor import (
    ScenarioPlanStep,
    operations_to_plan_steps,
)
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.scenario_manifest import (
    write_scenario_manifest,
)
from controlplane_tool.scenario_tasks import (
    helm_uninstall_vm_script,
    k8s_e2e_test_vm_script,
    kubectl_delete_namespace_vm_script,
)
from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.vm_cluster_workflows import (
    build_vm_cluster_prelude_plan,
    control_image,
    function_image_specs,
    runtime_image,
)
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest
from controlplane_tool.workflow_models import WorkflowContext


@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]


ScenarioExecutionStatus = Literal["running", "success", "failed"]


@dataclass(frozen=True)
class ScenarioStepEvent:
    step_index: int
    total_steps: int
    step: ScenarioPlanStep
    status: ScenarioExecutionStatus
    error: str | None = None

def plan_recipe_steps(
    repo_root: Path,
    request: E2eRequest,
    scenario_name: str,
    *,
    shell: ShellBackend | None = None,
    release: str | None = None,
    manifest_root: Path | None = None,
) -> list[ScenarioPlanStep]:
    effective_release = release or (
        "nanofaas-cli-stack-e2e" if scenario_name == "cli-stack" else "control-plane"
    )
    context: ScenarioExecutionContext = resolve_scenario_environment(
        repo_root,
        request,
        manifest_root=manifest_root,
        release=effective_release,
    )
    recipe = build_scenario_recipe(scenario_name)
    runner = E2eRunner(repo_root, shell=shell, manifest_root=manifest_root)
    namespace = context.namespace
    if namespace is None and context.resolved_scenario is not None:
        namespace = context.resolved_scenario.namespace
    if namespace is None:
        namespace = "nanofaas-e2e"
    cli_context = CliComponentContext(
        repo_root=repo_root,
        release=effective_release,
        namespace=namespace,
        local_registry=context.local_registry,
        resolved_scenario=context.resolved_scenario,
    )
    vm_request = context.vm_request
    remote_dir = runner.vm.remote_project_dir(vm_request)

    def _on_ensure_running() -> None:
        runner.vm.ensure_running(vm_request)

    def _on_vm_down() -> None:
        runner.vm.teardown(vm_request)

    def _on_remote_exec(argv: tuple[str, ...], env: Mapping[str, str]) -> None:
        result = runner.vm.exec_argv(vm_request, argv, env=dict(env), cwd=remote_dir)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")

    cli_context = CliComponentContext(
        repo_root=Path(remote_dir),
        release=cli_context.release,
        namespace=cli_context.namespace,
        local_registry=cli_context.local_registry,
        resolved_scenario=cli_context.resolved_scenario,
    )

    steps: list[ScenarioPlanStep] = []
    for component in compose_recipe(recipe):
        planner_context: object = cli_context if component.component_id.startswith("cli.") else context
        operations = component.planner(planner_context)
        steps.extend(
            operations_to_plan_steps(
                operations,
                request=request,
                on_k3s_curl_verify=lambda: runner._k3s_curl_runner(request).verify_existing_stack(
                    request.resolved_scenario
                ),
                on_ensure_running=_on_ensure_running,
                on_vm_down=_on_vm_down,
                on_remote_exec=_on_remote_exec,
            )
        )
    return steps


class E2eRunner:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        manifest_root: Path | None = None,
        host_resolver: Callable[[VmRequest], str] | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.paths = ToolPaths.repo_root(Path(repo_root))
        self.shell = shell or SubprocessShell()
        self.vm = VmOrchestrator(self.paths.workspace_root, shell=self.shell, multipass_client=multipass_client)
        self.manifest_root = manifest_root or (self.paths.runs_dir / "manifests")
        self._host_resolver = host_resolver

    def _step_from_result(
        self,
        summary: str,
        result: ShellExecutionResult,
        *,
        step_id: str,
    ) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=result.command, env=result.env, step_id=step_id)

    def _step(
        self,
        summary: str,
        command: list[str],
        *,
        env: dict[str, str] | None = None,
        step_id: str,
    ) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=command, env=env or {}, step_id=step_id)

    def _backend_step(
        self,
        summary: str,
        script_name: str,
        *,
        env: dict[str, str] | None = None,
    ) -> ScenarioPlanStep:
        script = self.paths.workspace_root / "scripts" / "lib" / script_name
        return self._step(summary, ["bash", str(script)], env=env, step_id=f"backend.{script_name}")

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

    def _effective_namespace(self, request: E2eRequest) -> str:
        if request.namespace:
            return request.namespace
        if request.resolved_scenario is not None and request.resolved_scenario.namespace:
            return request.resolved_scenario.namespace
        return "nanofaas-e2e"

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

    def _local_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
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
                        "local-e2e", "run", "container-local",
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
                        "local-e2e", "run", "deploy-host",
                    ],
                    env=self._with_manifest_env(request, self._common_env(request)),
                    step_id="deploy-host.run_flow",
                )
            ]

        raise ValueError(f"Unsupported local scenario: {request.scenario}")

    def _k3s_vm_prelude_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
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
                ["echo", "No selected function images to build"],
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
            self._remote_exec_step(
                "Wait for control-plane deployment",
                vm_request,
                prelude.wait_control_plane_script,
                step_id="k8s.wait_control_plane_ready",
            ),
            self._remote_exec_step(
                "Wait for function-runtime deployment",
                vm_request,
                prelude.wait_function_runtime_script,
                step_id="k8s.wait_function_runtime_ready",
            ),
        ]

    def _k3s_junit_curl_tail_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
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
        runtime_image = self._runtime_image(request)
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
            delete_namespace_step = self._remote_exec_step(
                "Delete E2E namespace",
                vm_request,
                kubectl_delete_namespace_vm_script(
                    remote_dir=remote_dir,
                    namespace=namespace,
                    kubeconfig_path=kubeconfig_path,
                ),
                step_id="cleanup.delete_namespace",
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
            delete_namespace_step = self._step(
                "Delete E2E namespace",
                ["echo", "Skipping namespace cleanup (--no-cleanup-vm)"],
                step_id="cleanup.delete_namespace",
            )
            teardown_step = self._step(
                "Teardown VM",
                ["echo", "Skipping VM teardown (--no-cleanup-vm)"],
                step_id="vm.down",
            )

        return [
            ScenarioPlanStep(
                summary="Run k3s-junit-curl verification",
                command=["python", "-m", "controlplane_tool.k3s_curl_runner", "verify-existing-stack"],
                step_id="tests.run_k3s_curl_checks",
                action=lambda: verifier.verify_existing_stack(request.resolved_scenario),
            ),
            self._remote_exec_step(
                "Run K8sE2eTest in VM",
                vm_request,
                k8s_e2e_test_vm_script(
                    remote_dir=remote_dir,
                    kubeconfig_path=kubeconfig_path,
                    runtime_image=runtime_image,
                    namespace=namespace,
                    remote_manifest_path=remote_manifest_path,
                ),
                step_id="tests.run_k8s_junit",
            ),
            uninstall_runtime_step,
            uninstall_control_plane_step,
            delete_namespace_step,
            teardown_step,
        ]

    def _k3s_junit_curl_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        return [
            *self._k3s_vm_prelude_steps(request),
            *self._k3s_junit_curl_tail_steps(request),
        ]

    def _helm_stack_tail_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_env = self._vm_env(request)
        if request.helm_noninteractive:
            vm_env = {**vm_env, "E2E_K3S_HELM_NONINTERACTIVE": "true"}
        vm_env = self._with_manifest_env(request, vm_env)
        controlplane_tool_project = self.paths.workspace_root / "tools" / "controlplane"
        return [
            self._step(
                "Run loadtest via Python runner",
                [
                    "uv",
                    "run",
                    "--project",
                    str(controlplane_tool_project),
                    "--locked",
                    "controlplane-tool",
                    "loadtest",
                    "run",
                ],
                env=vm_env,
                step_id="loadtest.run",
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

    def _vm_bootstrap_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
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

    def _vm_scenario_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        if request.scenario == "k3s-junit-curl":
            return self._k3s_junit_curl_steps(request)

        if request.scenario == "cli":
            return [
                self._step(
                    "Run CLI E2E workflow inside VM (Python)",
                    [
                        "uv", "run",
                        "--project", "tools/controlplane",
                        "--locked",
                        "controlplane-tool",
                        "cli-e2e", "run", "vm",
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
                        "cli-e2e", "run", "cli-stack",
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
                        "cli-e2e", "run", "host-platform",
                    ],
                    env=self._with_manifest_env(request, self._vm_env(request)),
                    step_id="cli.host_platform_flow",
                )
        ]

        raise ValueError(f"Unsupported VM-backed scenario: {request.scenario}")

    def _vm_backed_steps(
        self,
        request: E2eRequest,
        *,
        include_bootstrap: bool = True,
    ) -> list[ScenarioPlanStep]:
        if request.scenario == "k3s-junit-curl":
            return self._k3s_junit_curl_steps(request)
        if request.scenario == "helm-stack":
            return [
                *self._k3s_vm_prelude_steps(request),
                *self._helm_stack_tail_steps(request),
            ]
        steps = []
        if include_bootstrap:
            steps.extend(self._vm_bootstrap_steps(request))
        steps.extend(self._vm_scenario_steps(request))
        return steps

    def plan(self, request: E2eRequest) -> ScenarioPlan:
        scenario = resolve_scenario(request.scenario)
        if request.runtime not in scenario.supported_runtimes:
            raise ValueError(
                f"Scenario '{request.scenario}' does not support runtime '{request.runtime}'"
            )
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack"}:
            plan_request = request
            recipe = build_scenario_recipe(request.scenario)
            if request.vm is None and recipe.requires_managed_vm:
                context = resolve_scenario_environment(self.paths.workspace_root, request)
                plan_request = request.model_copy(update={"vm": context.vm_request})
            return ScenarioPlan(
                scenario=scenario,
                request=plan_request,
                steps=plan_recipe_steps(
                    self.paths.workspace_root,
                    plan_request,
                    request.scenario,
                    shell=self.shell,
                    manifest_root=self.manifest_root,
                ),
            )
        steps = (
            self._vm_backed_steps(request)
            if scenario.requires_vm
            else self._local_steps(request)
        )
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)

    def plan_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        cleanup_vm: bool = True,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
    ) -> list[ScenarioPlan]:
        only_set = set(only or [])
        skip_set = set(skip or [])
        plans: list[ScenarioPlan] = []
        shared_vm_request = vm_request
        vm_bootstrap_planned = False
        selected_scenarios = [
            scenario
            for scenario in list_scenarios()
            if (not only_set or scenario.name in only_set)
            and scenario.name not in skip_set
            and runtime in scenario.supported_runtimes
        ]
        last_vm_index = max(
            (index for index, scenario in enumerate(selected_scenarios) if scenario.requires_vm),
            default=-1,
        )

        for index, scenario in enumerate(selected_scenarios):
            if only_set and scenario.name not in only_set:
                continue
            if scenario.name in skip_set:
                continue
            if runtime not in scenario.supported_runtimes:
                continue

            if scenario.requires_vm and shared_vm_request is None:
                shared_vm_request = VmRequest(lifecycle="multipass")

            request = E2eRequest(
                scenario=scenario.name,
                runtime=runtime,
                vm=shared_vm_request if scenario.requires_vm else None,
                cleanup_vm=cleanup_vm if index == last_vm_index else False,
                namespace=namespace,
                local_registry=local_registry,
            )
            if scenario.requires_vm:
                steps = self._vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue

            plans.append(ScenarioPlan(scenario=scenario, request=request, steps=self._local_steps(request)))
        return plans

    # Matches the dry-run placeholder inserted by resolve_connection_host
    _MULTIPASS_IP_RE = re.compile(r"<multipass-ip:([^>]+)>")

    def _resolve_ip(self, vm_request: VmRequest) -> str:
        """Resolve the real IP of a VM, using an injected resolver if provided."""
        if self._host_resolver is not None:
            return self._host_resolver(vm_request)
        return self.vm.resolve_multipass_ipv4(vm_request)

    def _resolve_placeholder_text(
        self,
        value: str,
        vm_request: VmRequest | None,
        cache: dict[str, str],
    ) -> str:
        def _replace(m: re.Match) -> str:
            key = m.group(1)
            if key not in cache and vm_request is not None:
                cache[key] = self._resolve_ip(vm_request)
            return cache.get(key, m.group(0))

        return self._MULTIPASS_IP_RE.sub(_replace, value)

    def _resolve_command(
        self,
        command: list[str],
        vm_request: VmRequest | None,
        cache: dict[str, str],
    ) -> list[str]:
        """Substitute <multipass-ip:name> placeholders with real IPs."""
        if not any(self._MULTIPASS_IP_RE.search(arg) for arg in command):
            return command
        return [self._resolve_placeholder_text(arg, vm_request, cache) for arg in command]

    def _resolve_env(
        self,
        env: dict[str, str],
        vm_request: VmRequest | None,
        cache: dict[str, str],
    ) -> dict[str, str]:
        if not any(self._MULTIPASS_IP_RE.search(value) for value in env.values()):
            return env
        return {
            key: self._resolve_placeholder_text(value, vm_request, cache)
            for key, value in env.items()
        }

    def _emit_event(
        self,
        event_listener: Callable[[ScenarioStepEvent], None] | None,
        *,
        step_index: int,
        total_steps: int,
        step: ScenarioPlanStep,
        status: ScenarioExecutionStatus,
        error: str | None = None,
    ) -> None:
        if event_listener is None:
            return
        event_listener(
            ScenarioStepEvent(
                step_index=step_index,
                total_steps=total_steps,
                step=step,
                status=status,
                error=error,
            )
        )

    def _require_step_id(self, step: ScenarioPlanStep) -> str:
        if not step.step_id:
            raise ValueError(f"Scenario step '{step.summary}' is missing a stable step_id")
        return step.step_id

    def _execute_steps(
        self,
        plan: ScenarioPlan,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        ip_cache: dict[str, str] = {}
        total_steps = len(plan.steps)
        for step_index, step in enumerate(plan.steps, start=1):
            step_id = self._require_step_id(step)
            self._emit_event(
                event_listener,
                step_index=step_index,
                total_steps=total_steps,
                step=step,
                status="running",
            )
            with bind_workflow_context(WorkflowContext(flow_id=plan.request.scenario, task_id=step_id)):
                if step.action is not None:
                    try:
                        step.action()
                    except Exception as exc:
                        self._emit_event(
                            event_listener,
                            step_index=step_index,
                            total_steps=total_steps,
                            step=step,
                            status="failed",
                            error=str(exc),
                        )
                        raise RuntimeError(
                            f"Scenario '{plan.request.scenario}' failed at step '{step.summary}': {exc}"
                        ) from exc
                    self._emit_event(
                        event_listener,
                        step_index=step_index,
                        total_steps=total_steps,
                        step=step,
                        status="success",
                    )
                    continue
                command = self._resolve_command(step.command, plan.request.vm, ip_cache)
                env = self._resolve_env(step.env, plan.request.vm, ip_cache)
                result = self.shell.run(
                    command,
                    cwd=self.paths.workspace_root,
                    env=env,
                    dry_run=False,
                )
                if result.return_code != 0:
                    output = (result.stderr or result.stdout or "").strip()
                    self._emit_event(
                        event_listener,
                        step_index=step_index,
                        total_steps=total_steps,
                        step=step,
                        status="failed",
                        error=output or f"exit {result.return_code}",
                    )
                    msg = f"Scenario '{plan.request.scenario}' failed at step '{step.summary}' (exit {result.return_code})"
                    if output:
                        msg += f"\n\n{output}"
                    raise RuntimeError(msg)
                self._emit_event(
                    event_listener,
                    step_index=step_index,
                    total_steps=total_steps,
                    step=step,
                    status="success",
                )

    def _should_teardown(self, request: E2eRequest | None) -> bool:
        if request is None or request.vm is None:
            return False
        if request.vm.lifecycle != "multipass" or not request.cleanup_vm:
            return False
        try:
            recipe = build_scenario_recipe(request.scenario)
        except ValueError:
            return True
        return "vm.down" not in recipe.component_ids

    def _recorded_command_count(self) -> int | None:
        commands = getattr(self.shell, "commands", None)
        if isinstance(commands, list):
            return len(commands)
        return None

    def _discard_planning_commands(self, initial_count: int | None) -> None:
        if initial_count is None:
            return
        commands = getattr(self.shell, "commands", None)
        if isinstance(commands, list):
            del commands[initial_count:]

    def execute(
        self,
        plan: ScenarioPlan,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> None:
        succeeded = False
        try:
            self._execute_steps(plan, event_listener=event_listener)
            succeeded = True
        finally:
            if succeeded and self._should_teardown(plan.request):
                self.vm.teardown(plan.request.vm)

    def run(
        self,
        request: E2eRequest,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        self.execute(plan, event_listener=event_listener)
        return plan

    def run_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        cleanup_vm: bool = True,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
    ) -> list[ScenarioPlan]:
        initial_count = self._recorded_command_count()
        plans = self.plan_all(
            only=only,
            skip=skip,
            runtime=runtime,
            vm_request=vm_request,
            cleanup_vm=cleanup_vm,
            namespace=namespace,
            local_registry=local_registry,
        )
        self._discard_planning_commands(initial_count)
        shared_vm_request = next(
            (plan.request.vm for plan in plans if plan.request.vm is not None),
            None,
        )
        succeeded = False
        try:
            for plan in plans:
                self._execute_steps(plan)
            succeeded = True
            return plans
        finally:
            final_request = plans[-1].request if plans else None
            if succeeded and self._should_teardown(final_request):
                self.vm.teardown(shared_vm_request)
