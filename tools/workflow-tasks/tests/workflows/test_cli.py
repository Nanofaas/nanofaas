from dataclasses import replace

from workflow_tasks.workflows.cli import (
    CliFunction,
    CliWorkflowRequest,
    cli_cleanup_specs,
    cli_task_specs,
)


FUNCTION = CliFunction(
    name="word-stats-java",
    image="localhost:5000/nanofaas/java-word-stats:e2e",
    payload='{"text":"hello world"}',
)


def test_cli_on_host_exercises_the_function_lifecycle() -> None:
    specs = cli_task_specs(
        CliWorkflowRequest(
            functions=(FUNCTION,),
            cli_role="host",
            namespace="research",
        )
    )

    assert [task.task_id for task in specs] == [
        "cli.build",
        "cli.function.apply.word-stats-java",
        "cli.function.list",
        "cli.function.invoke.word-stats-java",
    ]
    assert all(task.role == "host" for task in specs)
    direct_commands = [task for task in specs if task.argv[0] != "bash"]
    assert all(("--namespace", "research") == task.argv[3:5] for task in direct_commands[1:])
    invoke = specs[-1]
    assert invoke.argv[:2] == ("bash", "-lc")
    assert "{\"text\":\"hello world\"}" in invoke.argv[-1]
    assert '"status":"success"' in invoke.argv[-1]
    assert '"output"' in invoke.argv[-1]
    assert (
        cli_cleanup_specs(CliWorkflowRequest(functions=(FUNCTION,)))[0].task_id
        == "cli.function.delete.word-stats-java"
    )


def test_cli_can_run_on_stack_without_changing_the_lifecycle() -> None:
    request = CliWorkflowRequest(functions=(FUNCTION,), cli_role="stack")
    specs = cli_task_specs(request)

    assert all(task.role == "stack" for task in (*specs, *cli_cleanup_specs(request)))


def test_apply_uses_resolved_image_resources_and_no_persistent_manifest() -> None:
    function = replace(
        FUNCTION,
        image="registry.example/research:v2",
        resources={"limits": {"cpu": 1.0, "memoryMiB": 512}},
    )
    apply = cli_task_specs(CliWorkflowRequest(functions=(function,)))[1]

    assert "mktemp" in apply.argv[-1]
    assert "registry.example/research:v2" in apply.argv[-1]
    assert '"memoryMiB":512' in apply.argv[-1]
