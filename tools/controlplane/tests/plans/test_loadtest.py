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

DEFAULT_TASK_IDS = [
    "stack.preflight",
    "build.jvm",
    "images.build.control-plane",
    "images.push.control-plane",
    "images.build.warm-echo",
    "images.push.warm-echo",
    "images.build.word-stats-java",
    "images.push.word-stats-java",
    "helm.deploy.control-plane",
    "functions.register.word-stats-java",
    "loadgen.preflight",
    "loadgen.prepare",
    "loadgen.run_k6",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "loadtest.write_summary",
    "metrics.evaluate_gate",
    "functions.delete.word-stats-java",
    "helm.uninstall.control-plane",
]


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


def test_loadtest_defaults_preserve_task_ids_byte_for_byte(tmp_path: Path) -> None:
    executor = RecordingExecutor()

    workflow = build_loadtest_plan(
        SCENARIO,
        EnvironmentConfig(provider="local"),
        RoleBindings(host=executor, stack=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
    )

    assert workflow.task_ids == DEFAULT_TASK_IDS


def test_loadtest_plan_deploys_exact_prebuilt_images(tmp_path: Path) -> None:
    executor = RecordingExecutor()
    control_plane_image = "localhost:5000/nanofaas/control-plane:v0.18.0-amd64-native"
    function_image = "localhost:5000/nanofaas/java-word-stats:v0.18.0-amd64-native"

    workflow = build_loadtest_plan(
        SCENARIO,
        EnvironmentConfig(provider="local"),
        RoleBindings(host=executor, stack=executor),
        control_plane_url="http://stack:30080",
        prometheus_client=NoopPrometheus(),
        run_dir=tmp_path,
        prebuilt_control_plane_image=control_plane_image,
        prebuilt_function_images={"word-stats-java": function_image},
    )

    assert "build.jvm" not in workflow.task_ids
    assert not any(task_id.startswith("images.") for task_id in workflow.task_ids)
    deploy = next(task for task in workflow.tasks if task.task_id == "helm.deploy.control-plane")
    assert "controlPlane.image.repository=localhost:5000/nanofaas/control-plane" in deploy.spec.argv
    assert "controlPlane.image.tag=v0.18.0-amd64-native" in deploy.spec.argv
    register = next(
        task for task in workflow.tasks if task.task_id == "functions.register.word-stats-java"
    )
    assert function_image in register.spec.argv[-1]


def test_prebuilt_loadtest_requires_function_images(tmp_path: Path) -> None:
    executor = RecordingExecutor()

    with pytest.raises(
        ValueError,
        match="prebuilt function images are required in prebuilt mode",
    ):
        build_loadtest_plan(
            SCENARIO,
            EnvironmentConfig(provider="local"),
            RoleBindings(host=executor, stack=executor),
            control_plane_url="http://stack:30080",
            prometheus_client=NoopPrometheus(),
            run_dir=tmp_path,
            prebuilt_control_plane_image="localhost:5000/control-plane:v0.18.0",
        )


def test_prebuilt_loadtest_reports_missing_selected_function_images(
    tmp_path: Path,
) -> None:
    executor = RecordingExecutor()

    with pytest.raises(
        ValueError,
        match="missing prebuilt function images: word-stats-java",
    ):
        build_loadtest_plan(
            SCENARIO,
            EnvironmentConfig(provider="local"),
            RoleBindings(host=executor, stack=executor),
            control_plane_url="http://stack:30080",
            prometheus_client=NoopPrometheus(),
            run_dir=tmp_path,
            prebuilt_control_plane_image="localhost:5000/control-plane:v0.18.0",
            prebuilt_function_images={},
        )


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

    assert workflow.task_ids.index("helm.deploy.control-plane") < workflow.task_ids.index(
        "functions.register.word-stats-java"
    )
    assert workflow.task_ids.index("functions.register.word-stats-java") < workflow.task_ids.index(
        "loadgen.run_k6"
    )
    assert [task.task_id for task in workflow.cleanup_tasks] == [
        "functions.delete.word-stats-java",
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
