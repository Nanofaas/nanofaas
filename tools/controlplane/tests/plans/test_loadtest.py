from dataclasses import dataclass, field
from pathlib import Path

import pytest

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.loadtest import build_loadtest_plan
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


class NoopPrometheus:
    def query_range(self, *args, **kwargs):
        return []


SCENARIO = ScenarioConfig(workflow="loadtest", functions=["word-stats-java"])


@pytest.mark.parametrize(
    ("environment", "expected_role", "fetches"),
    [
        (EnvironmentConfig(provider="local"), "stack", False),
        (
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            "stack",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "external",
                    "roles": {
                        "stack": {"host": "stack.example"},
                        "loadgen": {"host": "load.example"},
                    },
                }
            ),
            "loadgen",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "azure",
                    "azure": {"resource_group": "rg", "location": "westeurope"},
                    "roles": {"stack": {"name": "s"}, "loadgen": {"name": "l"}},
                }
            ),
            "loadgen",
            True,
        ),
        (
            EnvironmentConfig.model_validate(
                {
                    "provider": "proxmox",
                    "proxmox": {"host": "pve", "node": "pve1"},
                    "roles": {"stack": {"name": "s"}, "loadgen": {"name": "l"}},
                }
            ),
            "loadgen",
            True,
        ),
    ],
)
def test_provider_contract_selects_role_and_result_transport(
    tmp_path: Path,
    environment: EnvironmentConfig,
    expected_role: str,
    fetches: bool,
) -> None:
    executor = RecordingExecutor()
    workflow = build_loadtest_plan(
        SCENARIO,
        environment,
        RoleBindings(host=executor, stack=executor, loadgen=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        fetcher=object() if fetches else None,
    )

    preflight = next(task for task in workflow.tasks if task.task_id == "loadgen.preflight")
    assert preflight.spec.role == expected_role
    assert ("loadgen.fetch_results" in workflow.task_ids) is fetches


def test_loadtest_plan_owns_stack_registration_and_cleanup(tmp_path: Path) -> None:
    executor = RecordingExecutor()

    workflow = build_loadtest_plan(
        SCENARIO,
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        RoleBindings(host=executor, stack=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        fetcher=object(),
    )

    assert workflow.task_ids.index("helm.deploy.function-runtime") < workflow.task_ids.index(
        "functions.register.word-stats-java"
    )
    assert workflow.task_ids.index("functions.register.word-stats-java") < workflow.task_ids.index(
        "loadgen.run_k6"
    )
    assert [task.task_id for task in workflow.cleanup_tasks] == [
        "functions.delete.word-stats-java",
        "helm.uninstall.function-runtime",
        "helm.uninstall.control-plane",
    ]


def test_loadtest_plan_enables_advanced_metrics(tmp_path: Path) -> None:
    executor = RecordingExecutor()

    workflow = build_loadtest_plan(
        SCENARIO,
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        RoleBindings(host=executor, stack=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        fetcher=object(),
    )

    deploy = next(task for task in workflow.tasks if task.task_id == "helm.deploy.control-plane")
    assert any(
        "NANOFAAS_METRICS_PROFILE" in argument for argument in deploy.spec.argv
    )
    assert any("advanced" in argument for argument in deploy.spec.argv)


def test_autoscaling_loadtest_builds_registers_and_observes_scaler(tmp_path: Path) -> None:
    executor = RecordingExecutor()
    config = ScenarioConfig(
        workflow="loadtest", functions=["word-stats-java"], autoscaling=True
    )

    workflow = build_loadtest_plan(
        config,
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        RoleBindings(host=executor, stack=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        fetcher=object(),
    )

    build = next(task for task in workflow.tasks if task.task_id == "build.jvm")
    register = next(
        task for task in workflow.tasks if task.task_id == "functions.register.word-stats-java"
    )
    run = next(task for task in workflow.tasks if task.task_id == "loadgen.run_k6")
    assert (
        "-PcontrolPlaneModules=k8s-deployment-provider,autoscaler,async-queue,sync-queue"
        in build.spec.argv
    )
    assert "scalingConfig" in " ".join(register.spec.argv)
    assert "INTERNAL" in " ".join(register.spec.argv)
    assert "timeoutMs" in " ".join(register.spec.argv)
    assert "30000" in " ".join(register.spec.argv)
    assert "queueSize" in " ".join(register.spec.argv)
    assert "100" in " ".join(register.spec.argv)
    assert run.run_k6.config.script_path.name == "autoscaling.js"
    assert [(stage.duration, stage.target) for stage in run.run_k6.config.stages] == [
        ("10s", 10),
        ("20s", 20),
        ("90s", 20),
        ("10s", 0),
    ]
    assert "autoscaling.verify_replicas" in workflow.task_ids
