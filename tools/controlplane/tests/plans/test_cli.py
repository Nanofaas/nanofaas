from dataclasses import dataclass, field

from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans.cli import build_cli_plan
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult


@dataclass
class RecordingExecutor:
    seen: list[CommandTaskSpec] = field(default_factory=list)

    def run(self, task: CommandTaskSpec, *, dry_run: bool = False) -> TaskResult:
        self.seen.append(task)
        return TaskResult(task_id=task.task_id, status="passed", return_code=0)


def test_cli_plan_uses_the_selected_role_binding() -> None:
    host = RecordingExecutor()
    stack = RecordingExecutor()
    plan = build_cli_plan(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        RoleBindings(host=host, stack=stack),
        cli_role="stack",
    )

    plan.run()

    assert host.seen == []
    assert [task.task_id for task in stack.seen] == plan.task_ids


def test_cli_plan_resolves_all_selected_functions_and_resources() -> None:
    plan = build_cli_plan(
        ScenarioConfig.model_validate(
            {
                "workflow": "cli",
                "backend": "k8s",
                "functions": ["word-stats-java", "json-transform-python"],
                "resources": {"word-stats-java": {"limits": {"memoryMiB": 512}}},
            }
        ),
        RoleBindings(host=RecordingExecutor(), stack=RecordingExecutor()),
        endpoint="http://stack.example:8080",
    )

    assert [task.task_id for task in plan.tasks] == [
        "cli.build",
        "cli.function.apply.word-stats-java",
        "cli.function.apply.json-transform-python",
        "cli.function.list",
        "cli.function.invoke.word-stats-java",
        "cli.function.invoke.json-transform-python",
    ]
    assert "http://stack.example:8080" in " ".join(plan.tasks[1].spec.argv)
    assert '"memoryMiB":512' in plan.tasks[1].spec.argv[-1]
    assert '"input"' not in plan.tasks[-2].spec.argv[-1]
