from dataclasses import dataclass, field

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.validate import build_validate_plan
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


def test_validate_plan_dispatches_k8s_tasks_to_stack_binding() -> None:
    host = RecordingExecutor()
    stack = RecordingExecutor()
    plan = build_validate_plan(
        ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
        RoleBindings(host=host, stack=stack),
    )

    plan.run()

    assert host.seen == []
    assert [task.task_id for task in stack.seen] == plan.task_ids
    assert "images.build.warm-echo" in plan.task_ids
    assert "helm.deploy.function-runtime" not in plan.task_ids
    build = next(task.spec for task in plan.tasks if task.task_id == "build.jvm")
    assert "-PcontrolPlaneModules=k8s-deployment-provider,async-queue,sync-queue" in build.argv


def test_validate_plan_keeps_container_validation_local() -> None:
    host = RecordingExecutor()
    stack = RecordingExecutor()
    plan = build_validate_plan(
        ScenarioConfig(workflow="validate", backend="container", functions=["word-stats-java"]),
        RoleBindings(host=host, stack=stack),
    )

    assert stack.seen == []
    assert plan.tasks[2].task_id == "container.start.control-plane"
    assert plan.tasks[2].cleanup_task_id == "container.start.control-plane.cleanup"


def test_validate_plan_resolves_build_from_the_function_catalog() -> None:
    plan = build_validate_plan(
        ScenarioConfig(
            workflow="validate",
            backend="container",
            functions=["word-stats-java"],
        ),
        RoleBindings(host=RecordingExecutor(), stack=RecordingExecutor()),
    )

    image_build = plan.tasks[1].spec
    assert image_build.argv[0] == "./gradlew"
    assert image_build.argv[1] == ":functions:java:word-stats:bootBuildImage"
    assert image_build.argv[2].startswith("-PfunctionImage=")


def test_validate_plan_propagates_resource_requests_and_limits() -> None:
    plan = build_validate_plan(
        ScenarioConfig.model_validate(
            {
                "workflow": "validate",
                "backend": "container",
                "functions": ["word-stats-java"],
                "resources": {
                    "word-stats-java": {
                        "requests": {"cpu": 0.25, "memoryMiB": 128},
                        "limits": {"cpu": 1.0, "memoryMiB": 512},
                    }
                },
            }
        ),
        RoleBindings(host=RecordingExecutor(), stack=RecordingExecutor()),
    )

    register_body = plan.tasks[3].spec.argv[-2]
    assert '"memoryMiB":128' in register_body
    assert '"memoryMiB":512' in register_body
