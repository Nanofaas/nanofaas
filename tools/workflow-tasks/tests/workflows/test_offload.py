from workflow_tasks.workflows.offload import (
    CLOUD_ENDPOINT,
    EDGE_ENDPOINT,
    OffloadWorkflowRequest,
    START_CLOUD_TASK_ID,
    START_EDGE_TASK_ID,
    offload_cleanup_specs,
    offload_task_specs,
)
from workflow_tasks.workflows.validate import ValidateFunction


def _request() -> OffloadWorkflowRequest:
    return OffloadWorkflowRequest(
        functions=(
            ValidateFunction(
                key="word-stats-java",
                name="word-stats-java",
                image="nanofaas/java-word-stats:e2e",
                build_argv=("docker", "build", "-t", "nanofaas/java-word-stats:e2e", "."),
                payload='{"input":"the quick fox"}',
            ),
        )
    )


def test_specs_build_one_jar_with_the_offload_modules() -> None:
    specs = offload_task_specs(_request())

    build = specs[0]
    assert build.argv[0] == "./gradlew"
    assert ":control-plane:bootJar" in build.argv
    modules = next(part for part in build.argv if part.startswith("-PcontrolPlaneModules="))
    assert "offload" in modules
    assert "container-deployment-provider" in modules


def test_cloud_starts_before_edge_and_edge_targets_the_cloud() -> None:
    specs = offload_task_specs(_request())
    ids = [spec.task_id for spec in specs]

    cloud = specs[ids.index(START_CLOUD_TASK_ID)]
    edge = specs[ids.index(START_EDGE_TASK_ID)]
    assert ids.index(START_CLOUD_TASK_ID) < ids.index(START_EDGE_TASK_ID)
    assert "--server.port=19090" in cloud.argv
    assert "--nanofaas.deployment.default-backend=container-local" in cloud.argv
    assert "--server.port=18080" in edge.argv
    assert f"--nanofaas.offload.target-url={CLOUD_ENDPOINT}" in edge.argv
    # the edge never runs the function locally: no container backend needed
    assert not any("container-local" in part for part in edge.argv)


def test_function_registers_on_both_sides_with_an_eager_edge_policy() -> None:
    specs = offload_task_specs(_request())
    by_id = {spec.task_id: spec for spec in specs}

    cloud = by_id["offload.register.cloud.word-stats-java"]
    assert any(CLOUD_ENDPOINT in part for part in cloud.argv)
    assert any('"executionMode":"DEPLOYMENT"' in part for part in cloud.argv)

    edge = by_id["offload.register.edge.word-stats-java"]
    assert any(EDGE_ENDPOINT in part for part in edge.argv)
    assert any('"executionMode":"LOCAL"' in part for part in edge.argv)
    assert any('"offload":{"mode":"always"}' in part for part in edge.argv)


def test_eager_invocation_requires_the_offloaded_header_and_metrics() -> None:
    specs = offload_task_specs(_request())
    by_id = {spec.task_id: spec for spec in specs}

    invoke = by_id["offload.invoke.eager.word-stats-java"]
    command = " ".join(invoke.argv)
    assert f"{EDGE_ENDPOINT}/v1/functions/word-stats-java:invoke" in command
    assert "x-nanofaas-offloaded" in command.lower()

    metrics = by_id["offload.verify.metrics.word-stats-java"]
    command = " ".join(metrics.argv)
    assert "nanofaas_offload_total" in command
    assert 'trigger="eager"' in command
    assert "nanofaas_offload_failure_total" in command


def test_remote_missing_check_runs_last_and_expects_502() -> None:
    specs = offload_task_specs(_request())
    ids = [spec.task_id for spec in specs]

    negative = ids.index("offload.verify.remote-missing.word-stats-java")
    assert negative == len(ids) - 1
    command = " ".join(specs[negative].argv)
    assert "502" in command
    assert f"{CLOUD_ENDPOINT}/v1/functions/word-stats-java" in command


def test_cleanup_deletes_the_function_on_both_instances_tolerantly() -> None:
    cleanup = offload_cleanup_specs(_request())

    assert {spec.task_id for spec in cleanup} == {
        "offload.delete.edge.word-stats-java",
        "offload.delete.cloud.word-stats-java",
    }
    for spec in cleanup:
        assert spec.expected_exit_codes >= {0, 7, 22}
