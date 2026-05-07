from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.cli_validation.cli_stack_runner import CliStackRunner
from controlplane_tool.cli_validation.cli_test_catalog import (
    CliTestScenarioDefinition,
    resolve_cli_test_scenario,
)
from controlplane_tool.cli_validation.cli_test_models import CliTestRequest
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, ScenarioPlanStep
from controlplane_tool.workspace.paths import ToolPaths
from controlplane_tool.core.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.infra.vm.vm_models import VmRequest


@dataclass(frozen=True)
class CliTestPlan:
    scenario: CliTestScenarioDefinition
    request: CliTestRequest
    steps: list[ScenarioPlanStep]


class CliTestRunner:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        manifest_root: Path | None = None,
    ) -> None:
        self.paths = ToolPaths.repo_root(Path(repo_root))
        self.shell = shell or SubprocessShell()
        self.e2e_runner = E2eRunner(
            self.paths.workspace_root,
            shell=self.shell,
            manifest_root=manifest_root,
        )

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

    def _execute_steps(self, plan: CliTestPlan) -> None:
        ip_cache: dict[str, str] = {}
        for step in plan.steps:
            command = step.command
            env = step.env
            if plan.request.vm is not None:
                command = self.e2e_runner._resolver._resolve_command(
                    command,
                    plan.request.vm,
                    ip_cache,
                    self.e2e_runner.vm,
                )
                env = self.e2e_runner._resolver._resolve_env(
                    env,
                    plan.request.vm,
                    ip_cache,
                    self.e2e_runner.vm,
                )
            result = self.shell.run(
                command,
                cwd=self.paths.workspace_root,
                env=env,
                dry_run=False,
            )
            if result.return_code != 0:
                raise RuntimeError(
                    f"cli-test scenario '{plan.request.scenario}' failed at step '{step.summary}'"
                )

    def _should_teardown(self, vm_request: VmRequest | None, *, keep_vm: bool) -> bool:
        return vm_request is not None and vm_request.lifecycle == "multipass" and not keep_vm

    def _gradle_step(self, scenario: CliTestScenarioDefinition) -> ScenarioPlanStep:
        if scenario.gradle_task == ":nanofaas-cli:test":
            return ScenarioPlanStep(
                summary="Run nanofaas-cli Gradle tests",
                command=["./gradlew", scenario.gradle_task, "--no-daemon"],
            )
        return ScenarioPlanStep(
            summary="Build nanofaas-cli installDist",
            command=["./gradlew", scenario.gradle_task, "--no-daemon"],
        )

    def _as_e2e_request(
        self,
        request: CliTestRequest,
        scenario: CliTestScenarioDefinition,
    ) -> E2eRequest:
        if scenario.legacy_e2e_scenario is None:
            raise ValueError(f"cli-test scenario '{request.scenario}' has no E2E mapping")
        resolved_scenario = (
            request.resolved_scenario.model_copy(
                update={"base_scenario": scenario.legacy_e2e_scenario}
            )
            if request.resolved_scenario is not None
            else None
        )
        return E2eRequest(
            scenario=scenario.legacy_e2e_scenario,
            runtime=request.runtime,
            function_preset=request.function_preset,
            functions=list(request.functions),
            scenario_file=request.scenario_file,
            saved_profile=request.saved_profile,
            scenario_source=request.scenario_source,
            resolved_scenario=resolved_scenario,
            vm=request.vm,
            cleanup_vm=not request.keep_vm,
            namespace=request.namespace,
            local_registry=request.local_registry,
        )

    def _step_owns_cli_build(self, step: ScenarioPlanStep) -> bool:
        rendered = " ".join(step.command)
        if "controlplane-tool" not in rendered or "cli-test" not in rendered or "run" not in rendered:
            return False
        return any(
            scenario_name in rendered
            for scenario_name in ("deploy-host", "host-platform")
        )

    def _with_cli_build_reuse(self, steps: list[ScenarioPlanStep]) -> list[ScenarioPlanStep]:
        reused_steps: list[ScenarioPlanStep] = []
        for step in steps:
            if self._step_owns_cli_build(step):
                reused_steps.append(
                    ScenarioPlanStep(
                        summary=step.summary,
                        command=step.command,
                        env={**step.env, "NANOFAAS_CLI_SKIP_INSTALL_DIST": "true"},
                    )
                )
                continue
            reused_steps.append(step)
        return reused_steps

    def plan(self, request: CliTestRequest) -> CliTestPlan:
        scenario = resolve_cli_test_scenario(request.scenario)
        if request.scenario == "cli-stack":
            runner = CliStackRunner(
                self.paths.workspace_root,
                vm_request=request.vm,
                namespace=request.namespace,
                local_registry=request.local_registry,
                runtime=request.runtime,
                skip_cli_build=False,
            )
            plan_request = (
                request
                if request.vm is not None
                else request.model_copy(update={"vm": runner.vm_request})
            )
            return CliTestPlan(
                scenario=scenario,
                request=plan_request,
                steps=runner.plan_steps(request.resolved_scenario),
            )
        steps = [self._gradle_step(scenario)]
        if scenario.legacy_e2e_scenario is not None:
            e2e_plan = self.e2e_runner.plan(self._as_e2e_request(request, scenario))
            steps.extend(self._with_cli_build_reuse(e2e_plan.steps))
        return CliTestPlan(scenario=scenario, request=request, steps=steps)

    def run(self, request: CliTestRequest) -> CliTestPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        try:
            self._execute_steps(plan)
            return plan
        finally:
            vm_request = request.vm
            if (
                plan.request.scenario != "cli-stack"
                and vm_request is not None
                and self._should_teardown(vm_request, keep_vm=request.keep_vm)
            ):
                self.e2e_runner.vm.teardown(vm_request)
