"""
cli_stack_runner.py

CliStackRunner: dedicated VM-backed CLI evaluation workflow.
"""
from __future__ import annotations

import os
import shlex
from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from workflow_tasks import (
    CommandTask,
    CommandTaskSpec,
    HostCommandTaskExecutor,
    Workflow,
    phase,
    success,
    workflow_step,
)
from controlplane_tool.scenario.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
)
from controlplane_tool.scenario.components.cli import CliComponentContext
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components.environment import (
    default_managed_vm_request,
    resolve_scenario_environment,
)
from controlplane_tool.scenario.components.executor import (
    ScenarioPlanStep,
)
from controlplane_tool.scenario.scenarios._workflow_assembly import _SUMMARY_OVERRIDES
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenario_helpers import (
    resolve_scenario as _resolve_scenario,
)
from workflow_tasks.shell import SubprocessShell
from workflow_tasks.vm.orchestrator import VmOrchestrator
from controlplane_tool.infra.vm.vm_cluster_workflows import control_image, runtime_image
from controlplane_tool.infra.vm.vm_models import VmRequest


class CliStackRunner:
    """Run the dedicated VM-backed CLI evaluation workflow."""

    def __init__(
        self,
        repo_root: Path,
        *,
        vm_request: VmRequest | None = None,
        namespace: str | None = None,
        release: str | None = None,
        local_registry: str = "localhost:5000",
        runtime: str = "java",
        skip_cli_build: bool = False,
    ) -> None:
        default_namespace = resolve_scenario_namespace(
            "cli-stack",
            explicit_namespace=namespace,
            resolved_scenario_namespace=None,
        )
        resolved_release = resolve_scenario_release(
            "cli-stack",
            explicit_release=release,
        )
        if default_namespace is None or resolved_release is None:
            raise ValueError("cli-stack requires resolved namespace and release defaults")
        self.repo_root = Path(repo_root)
        self.vm_request = vm_request or default_managed_vm_request()
        self._explicit_namespace = namespace
        self.namespace = default_namespace
        self.release = resolved_release
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
        effective_scenario = (
            resolved_scenario.model_copy(update={"base_scenario": "cli-stack"})
            if resolved_scenario is not None
            else None
        )
        effective_namespace = resolve_scenario_namespace(
            "cli-stack",
            explicit_namespace=self._explicit_namespace,
            resolved_scenario_namespace=(
                effective_scenario.namespace if effective_scenario is not None else None
            ),
        )
        if effective_namespace is None:
            raise ValueError("cli-stack plan requires a resolved namespace")
        request = E2eRequest(
            scenario="cli-stack",
            runtime=self.runtime,
            resolved_scenario=effective_scenario,
            vm=self.vm_request,
            namespace=effective_namespace,
            local_registry=self.local_registry,
        )
        # Compose the cli-stack recipe directly instead of going through the shared
        # e2e recipe planner. cli_stack_runner runs every step LOCALLY in its own
        # run() loop (using only command/env), so it needs the raw operation argv —
        # no remote-exec/ensure-running/teardown callbacks are required here.
        context = resolve_scenario_environment(
            self.repo_root,
            request,
            release=self.release,
        )
        # cli.* planners need the VM-side repo root and platform identifiers; the
        # control plane endpoint is None for cli-stack (it talks to the in-VM API
        # via KUBECONFIG/namespace, not an explicit endpoint).
        cli_context = CliComponentContext(
            repo_root=Path(self._vm.remote_project_dir(context.vm_request)),
            release=context.release,
            namespace=context.namespace,
            local_registry=context.local_registry,
            resolved_scenario=context.resolved_scenario,
            control_plane_endpoint=None,
        )

        steps: list[ScenarioPlanStep] = []
        for component in compose_recipe(build_scenario_recipe("cli-stack")):
            ctx = (
                cli_context
                if component.component_id.startswith("cli.")
                else context
            )
            for op in component.planner(ctx):
                steps.append(
                    ScenarioPlanStep(
                        summary=_SUMMARY_OVERRIDES.get(op.operation_id, op.summary),
                        command=list(op.argv),
                        env=dict(op.env),
                        step_id=op.operation_id,
                    )
                )
        return steps

    def _command_task(
        self, step: ScenarioPlanStep, *, executor: HostCommandTaskExecutor
    ) -> CommandTask:
        if not step.step_id:
            raise ValueError(
                f"CLI stack planned step '{step.summary}' is missing a stable step_id"
            )
        return CommandTask(
            task_id=step.step_id,
            title=step.summary,
            spec=CommandTaskSpec(
                task_id=step.step_id,
                summary=step.summary,
                argv=tuple(step.command),
                target="host",
                env=dict(step.env),
                cwd=self.repo_root,
            ),
            executor=executor,
        )

    def run(self, scenario_file: Path | None = None) -> None:
        resolved = _resolve_scenario(scenario_file)
        host_executor = HostCommandTaskExecutor(self._shell)
        tasks = [
            self._command_task(step, executor=host_executor)
            for step in self.plan_steps(resolved)
        ]
        phase("Verify")
        with workflow_step(task_id="cli-stack.verify", title="Verify"):
            Workflow(tasks=tasks).run()
        success("CLI stack workflow")
