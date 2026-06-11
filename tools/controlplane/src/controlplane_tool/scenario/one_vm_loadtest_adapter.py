from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from controlplane_tool.autoscaling.tasks import (
    FetchAutoscalingSummary,
    ReplicaProbe,
    ReplicaWatcher,
    RunK6WithReplicaWatch,
    VerifyAutoscalingReplicas,
)
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.scenario.loadtest_adapter import InstallEndpoint, MultipassConnectivity
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext
from controlplane_tool.scenario.scenarios._workflow_assembly import _Setup, build_setup
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_prometheus_url,
    two_vm_target_function,
)
from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
from workflow_tasks.loadtest.models import K6Config, K6Stage
from workflow_tasks.loadtest.tasks import RunK6


@dataclass
class OneVmLoadtestAdapter:
    runner: object
    request: object
    title_suffix: str = " (one VM)"
    _cached_setup: _Setup | None = field(default=None, init=False, repr=False)

    @property
    def connectivity(self) -> MultipassConnectivity:
        return MultipassConnectivity(runner=self.runner, request=self.request)

    def uses_dedicated_loadgen_vm(self) -> bool:
        return False

    def stack_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_lifecycle(self):
        return self.stack_lifecycle()

    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint:
        from multipass import find_ssh_public_key
        from workflow_tasks.vm.multipass import _find_ssh_private_key_path

        # Same VM as the stack; ansible k6 install needs the real ssh key.
        return InstallEndpoint(
            host=ctx.stack_info.host,
            user=self.request.vm.user,
            private_key=_find_ssh_private_key_path(find_ssh_public_key()),
            port=None,
        )

    def loadgen_runner(self, ctx: RunContext):
        return self.connectivity.vm_runner(self.request.vm)

    def fetcher(self, ctx: RunContext):
        from workflow_tasks.vm.runners import VmFileFetcher

        return VmFileFetcher(vm=self.runner.vm, request=self.request.vm)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self.request.vm, host=ctx.stack_info.host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return two_vm_prometheus_url(self.request.vm, host=ctx.stack_info.host)

    def prepare_loadgen(self, ctx: RunContext) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        request = self.request.model_copy(update={"loadgen_vm": self.request.vm})
        TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root,
            shell=self.runner.shell,
            runs_root=self.runner.paths.runs_dir,
        ).prepare_loadgen(request, ctx.remote_paths)
        autoscaling_asset = (
            self.runner.paths.workspace_root
            / "tools"
            / "controlplane"
            / "assets"
            / "k6"
            / "autoscaling.js"
        )
        self.runner.vm.transfer_to(
            self.request.vm,
            source=autoscaling_asset,
            destination=f"{ctx.remote_paths.scripts_dir}/autoscaling.js",
        )

    def create_run_dir(self) -> Path:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        loadtest_runner = TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root,
            shell=self.runner.shell,
            runs_root=self.runner.paths.runs_dir,
        )
        return loadtest_runner._create_run_dir()  # noqa: SLF001

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        return []

    def extra_step_ids(self, phase: FlowPhase) -> list[str]:
        return []

    def extra_step_titles(self, phase: FlowPhase) -> list[str]:
        return []

    def emits_step_events(self) -> bool:
        return False

    def cleanup_on_failure(self, error: Exception) -> list[str]:
        return []

    def register_functions(self, ctx: RunContext) -> None:
        setup = self._setup()
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=ctx.control_plane_url,
            on_conflict="skip",
            specs=[
                FunctionSpec(
                    name=fn_key,
                    image=function_image(fn_key, self.request.resolved_scenario, runtime_image_default),
                )
                for fn_key in selected_functions(self.request.resolved_scenario)
            ],
        ).run()

    def post_loadgen_task_ids(self) -> list[str]:
        return [
            "autoscaling.register_function",
            "autoscaling.run_k6",
            "autoscaling.verify_replicas",
            "autoscaling.fetch_summary",
        ]

    def post_loadgen_task_titles(self) -> list[str]:
        return [
            "Register autoscaling function",
            "Run autoscaling k6",
            "Verify autoscaling replicas",
            "Fetch autoscaling k6 summary",
        ]

    def post_loadgen_tasks(self, ctx: RunContext) -> list:
        setup = self._setup()
        runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
        function_name = two_vm_target_function(self.request)
        function_image_value = function_image(
            function_name,
            self.request.resolved_scenario,
            runtime_image_default,
        )
        autoscaling_script = Path(f"{ctx.remote_paths.scripts_dir}/autoscaling.js")
        autoscaling_summary = Path(f"{ctx.remote_paths.results_dir}/autoscaling-k6-summary.json")
        loadgen_runner = self.loadgen_runner(ctx)
        probe = ReplicaProbe(
            runner=loadgen_runner,
            namespace=setup.context.namespace,
            deployment_name=f"fn-{function_name}",
            remote_dir=ctx.loadgen_info.home,
        )
        watcher = ReplicaWatcher(probe)
        return [
            RegisterFunctions(
                task_id="autoscaling.register_function",
                title="Register autoscaling function",
                control_plane_url=ctx.control_plane_url,
                on_conflict="replace",
                specs=[
                    FunctionSpec(
                        name=function_name,
                        image=function_image_value,
                        timeout_ms=30000,
                        concurrency=4,
                        queue_size=100,
                        max_retries=3,
                        scaling_config={
                            "strategy": "INTERNAL",
                            "minReplicas": 0,
                            "maxReplicas": 5,
                            "metrics": [{"type": "in_flight", "target": "2"}],
                        },
                    )
                ],
            ),
            RunK6WithReplicaWatch(
                task_id="autoscaling.run_k6",
                title="Run autoscaling k6",
                run_k6=RunK6(
                    task_id="autoscaling.run_k6.inner",
                    title="Run autoscaling k6 (inner)",
                    runner=loadgen_runner,
                    config=K6Config(
                        script_path=autoscaling_script,
                        target_url=ctx.control_plane_url,
                        summary_output_path=autoscaling_summary,
                        stages=(
                            K6Stage(duration="10s", target=10),
                            K6Stage(duration="20s", target=20),
                            K6Stage(duration="90s", target=20),
                            K6Stage(duration="10s", target=0),
                        ),
                        env={
                            "NANOFAAS_URL": ctx.control_plane_url,
                            "NANOFAAS_FUNCTION": function_name,
                        },
                    ),
                    remote_dir=ctx.loadgen_info.home,
                ),
                watcher=watcher,
            ),
            VerifyAutoscalingReplicas(
                task_id="autoscaling.verify_replicas",
                title="Verify autoscaling replicas",
                runner=loadgen_runner,
                namespace=setup.context.namespace,
                deployment_name=f"fn-{function_name}",
                remote_dir=ctx.loadgen_info.home,
                watcher=watcher,
            ),
            FetchAutoscalingSummary(
                task_id="autoscaling.fetch_summary",
                title="Fetch autoscaling k6 summary",
                fetcher=self.fetcher(ctx),
                remote_path=str(autoscaling_summary),
                local_path=ctx.run_dir / "autoscaling-k6-summary.json",
            ),
        ]

    def _setup(self) -> _Setup:
        if self._cached_setup is None:
            self._cached_setup = build_setup(self.runner, self.request)
        return self._cached_setup
