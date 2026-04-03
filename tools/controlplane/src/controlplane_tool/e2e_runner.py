from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from controlplane_tool.e2e_catalog import ScenarioDefinition, list_scenarios, resolve_scenario
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.paths import ToolPaths
from controlplane_tool.scenario_manifest import (
    scenario_manifest_system_property_arg,
    write_scenario_manifest,
)
from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.vm_adapter import VmOrchestrator
from controlplane_tool.vm_models import VmRequest


@dataclass(frozen=True)
class ScenarioPlanStep:
    summary: str
    command: list[str]
    env: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]


class E2eRunner:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        manifest_root: Path | None = None,
    ) -> None:
        self.paths = ToolPaths.repo_root(Path(repo_root))
        self.shell = shell or SubprocessShell()
        self.vm = VmOrchestrator(self.paths.workspace_root, shell=self.shell)
        self.manifest_root = manifest_root or (self.paths.runs_dir / "manifests")

    def _step_from_result(self, summary: str, result: ShellExecutionResult) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=result.command, env=result.env)

    def _step(
        self,
        summary: str,
        command: list[str],
        *,
        env: dict[str, str] | None = None,
    ) -> ScenarioPlanStep:
        return ScenarioPlanStep(summary=summary, command=command, env=env or {})

    def _backend_step(
        self,
        summary: str,
        script_name: str,
        *,
        env: dict[str, str] | None = None,
    ) -> ScenarioPlanStep:
        script = self.paths.workspace_root / "scripts" / "lib" / script_name
        return self._step(summary, ["bash", str(script)], env=env)

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

    def _remote_exec_step(self, summary: str, request: VmRequest, command: str) -> ScenarioPlanStep:
        result = self.vm.remote_exec(request, command=command, dry_run=True)
        return self._step_from_result(summary, result)

    def _common_env(self, request: E2eRequest) -> dict[str, str]:
        env = {
            "CONTROL_PLANE_RUNTIME": request.runtime,
            "LOCAL_REGISTRY": request.local_registry,
        }
        if request.namespace:
            env["NAMESPACE"] = request.namespace
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
        if vm_request.home:
            env["E2E_VM_HOME"] = vm_request.home
        return env

    def _local_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        if request.scenario == "docker":
            return [
                self._step(
                    "Run Docker POOL regression test",
                    [
                        "./scripts/control-plane-build.sh",
                        "test",
                        "--profile",
                        "all",
                        "--",
                        "-PrunE2e",
                        "--tests",
                        "it.unimib.datai.nanofaas.controlplane.e2e.E2eFlowTest",
                    ],
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
                ),
                self._step(
                    "Run buildpack regression tests",
                    [
                        "./scripts/control-plane-build.sh",
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
                ),
            ]

        if request.scenario == "container-local":
            return [
                self._backend_step(
                    "Run container-local compatibility workflow",
                    "e2e-container-local-backend.sh",
                    env=self._with_manifest_env(request, self._common_env(request)),
                )
            ]

        if request.scenario == "deploy-host":
            return [
                self._backend_step(
                    "Run deploy-host compatibility workflow",
                    "e2e-deploy-host-backend.sh",
                    env=self._with_manifest_env(request, self._common_env(request)),
                )
            ]

        raise ValueError(f"Unsupported local scenario: {request.scenario}")

    def _vm_bootstrap_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_request = self._require_vm(request)
        return [
            self._step_from_result("Ensure VM is running", self.vm.ensure_running(vm_request, dry_run=True)),
            self._step_from_result(
                "Provision base VM dependencies",
                self.vm.install_dependencies(vm_request, install_helm=True, dry_run=True),
            ),
            self._step_from_result("Sync project to VM", self.vm.sync_project(vm_request, dry_run=True)),
            self._step_from_result("Install k3s", self.vm.install_k3s(vm_request, dry_run=True)),
            self._step_from_result(
                "Configure local registry",
                self.vm.setup_registry(vm_request, registry=request.local_registry, dry_run=True),
            ),
        ]

    def _vm_scenario_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        vm_request = self._require_vm(request)
        remote_dir = self.vm.remote_project_dir(vm_request)
        kubeconfig_path = self.vm.kubeconfig_path(vm_request)
        env = self._vm_env(request)

        if request.scenario == "k3s-curl":
            return [
                self._backend_step(
                    "Run k3s curl compatibility workflow",
                    "e2e-k3s-curl-backend.sh",
                    env=self._with_manifest_env(request, env),
                )
            ]

        if request.scenario == "k8s-vm":
            manifest_property = ""
            manifest_path = self._manifest_path(request)
            if manifest_path is not None:
                manifest_property = (
                    scenario_manifest_system_property_arg(
                        self._remote_manifest_path(vm_request, manifest_path)
                    )
                    + " "
                )
            return [
                self._remote_exec_step(
                    "Build control-plane and runtime images in VM",
                    vm_request,
                    f"cd {remote_dir} && ./scripts/control-plane-build.sh image --profile k8s -- -PcontrolPlaneImage={request.local_registry}/nanofaas/control-plane:e2e",
                ),
                self._remote_exec_step(
                    "Run K8sE2eTest in VM",
                    vm_request,
                    " ".join(
                        [
                            f"cd {remote_dir} &&",
                            f"KUBECONFIG={kubeconfig_path}",
                            "./scripts/control-plane-build.sh",
                            "test",
                            "--profile",
                            "k8s",
                            "--modules",
                            "all",
                            "--",
                            f"{manifest_property}-PrunE2e",
                            "--tests",
                            "it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest",
                            "--no-daemon",
                        ]
                    ),
                ),
            ]

        if request.scenario == "cli":
            return [
                self._backend_step(
                    "Run CLI compatibility workflow",
                    "e2e-cli-backend.sh",
                    env=self._with_manifest_env(request, env),
                )
            ]

        if request.scenario == "cli-host":
            return [
                self._backend_step(
                    "Run host CLI compatibility workflow",
                    "e2e-cli-host-backend.sh",
                    env=self._with_manifest_env(request, env),
                )
            ]

        if request.scenario == "helm-stack":
            return [
                self._backend_step(
                    "Run Helm stack compatibility workflow",
                    "e2e-helm-stack-backend.sh",
                    env=self._with_manifest_env(
                        request,
                        {**env, "E2E_K3S_HELM_NONINTERACTIVE": "true"},
                    ),
                )
            ]

        raise ValueError(f"Unsupported VM-backed scenario: {request.scenario}")

    def _vm_backed_steps(
        self,
        request: E2eRequest,
        *,
        include_bootstrap: bool = True,
    ) -> list[ScenarioPlanStep]:
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
        keep_vm: bool = False,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
    ) -> list[ScenarioPlan]:
        only_set = set(only or [])
        skip_set = set(skip or [])
        plans: list[ScenarioPlan] = []
        shared_vm_request = vm_request
        vm_bootstrap_planned = False

        for scenario in list_scenarios():
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
                keep_vm=keep_vm,
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

    def _execute_steps(self, plan: ScenarioPlan) -> None:
        for step in plan.steps:
            result = self.shell.run(
                step.command,
                cwd=self.paths.workspace_root,
                env=step.env,
                dry_run=False,
            )
            if result.return_code != 0:
                raise RuntimeError(f"Scenario '{plan.request.scenario}' failed at step '{step.summary}'")

    def _should_teardown(self, vm_request: VmRequest | None, *, keep_vm: bool) -> bool:
        return vm_request is not None and vm_request.lifecycle == "multipass" and not keep_vm

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

    def run(self, request: E2eRequest) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        try:
            self._execute_steps(plan)
            return plan
        finally:
            if self._should_teardown(request.vm, keep_vm=request.keep_vm):
                self.vm.teardown(request.vm)

    def run_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        keep_vm: bool = False,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
    ) -> list[ScenarioPlan]:
        initial_count = self._recorded_command_count()
        plans = self.plan_all(
            only=only,
            skip=skip,
            runtime=runtime,
            vm_request=vm_request,
            keep_vm=keep_vm,
            namespace=namespace,
            local_registry=local_registry,
        )
        self._discard_planning_commands(initial_count)
        shared_vm_request = next(
            (plan.request.vm for plan in plans if plan.request.vm is not None),
            None,
        )
        try:
            for plan in plans:
                self._execute_steps(plan)
            return plans
        finally:
            if self._should_teardown(shared_vm_request, keep_vm=keep_vm):
                self.vm.teardown(shared_vm_request)
