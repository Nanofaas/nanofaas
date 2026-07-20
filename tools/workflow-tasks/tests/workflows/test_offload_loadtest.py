from workflow_tasks.workflows.offload_loadtest import (
    OffloadLoadtestRequest,
    cloud_deployment_specs,
    edge_deployment_specs,
    offload_cleanup_specs,
    offload_registration_specs,
)
from workflow_tasks.workflows.validate import ValidateFunction


def _request() -> OffloadLoadtestRequest:
    return OffloadLoadtestRequest(
        offloadable=ValidateFunction(
            key="word-stats-java",
            name="word-stats-java",
            image="localhost:5000/nanofaas/java-word-stats:e2e",
            build_argv=("docker", "build", "-t", "localhost:5000/nanofaas/java-word-stats:e2e", "."),
            payload='{"input":{"text":"the quick brown fox"}}',
            concurrency=2,
            queue_size=8,
        ),
        control=ValidateFunction(
            key="json-transform-java",
            name="json-transform-java",
            image="localhost:5000/nanofaas/java-json-transform:e2e",
            build_argv=(
                "docker",
                "build",
                "-t",
                "localhost:5000/nanofaas/java-json-transform:e2e",
                ".",
            ),
            payload='{"input":{"value":1}}',
            concurrency=2,
            queue_size=8,
        ),
    )


def test_edge_specs_carry_the_offload_target_env() -> None:
    specs = edge_deployment_specs(_request(), "http://10.0.0.9:30080")
    helm = next(s for s in specs if s.task_id == "helm.deploy.control-plane")
    joined = " ".join(helm.argv)
    assert "controlPlane.extraEnv[0].name=NANOFAAS_OFFLOAD_TARGETURL" not in joined
    assert "controlPlane.extraEnv[0].value=http://10.0.0.9:30080" not in joined
    assert "controlPlane.extraEnv[" in joined
    assert "].name=NANOFAAS_OFFLOAD_TARGETURL" in joined
    assert "].value=http://10.0.0.9:30080" in joined
    # the offload target must not clobber an existing extraEnv[] entry
    assert "NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND" in joined
    assert all(s.role == "stack" for s in specs)


def test_cloud_specs_are_retargeted_and_have_no_offload_env() -> None:
    specs = cloud_deployment_specs(_request())
    assert all(s.role == "cloud" for s in specs)
    assert all(s.task_id.startswith("cloud.") for s in specs)
    assert not any("OFFLOAD_TARGETURL" in " ".join(s.argv) for s in specs)


def test_cloud_specs_only_build_the_offloadable_function() -> None:
    specs = cloud_deployment_specs(_request())
    ids = {s.task_id for s in specs}
    assert "cloud.images.build.word-stats-java" in ids
    assert "cloud.images.build.json-transform-java" not in ids


def test_registrations_encode_the_two_policies() -> None:
    specs = offload_registration_specs(_request())
    by_id = {s.task_id: s for s in specs}
    edge_control = " ".join(by_id["offload-loadtest.register.edge.json-transform-java"].argv)
    assert '"offload":{"enabled":false}' in edge_control
    edge_offloadable = " ".join(by_id["offload-loadtest.register.edge.word-stats-java"].argv)
    assert '"offload"' not in edge_offloadable  # pressure default
    assert "offload-loadtest.register.cloud.word-stats-java" in by_id
    assert "offload-loadtest.register.cloud.json-transform-java" not in by_id
    cloud = by_id["offload-loadtest.register.cloud.word-stats-java"]
    assert cloud.role == "cloud"


def test_cleanup_deletes_both_functions_on_edge_and_only_offloadable_on_cloud() -> None:
    specs = offload_cleanup_specs(_request())
    edge = [s for s in specs if s.role == "stack"]
    cloud = [s for s in specs if s.role == "cloud"]

    assert {s.task_id for s in edge} >= {
        "functions.delete.word-stats-java",
        "functions.delete.json-transform-java",
        "helm.uninstall.control-plane",
    }
    assert {s.task_id for s in cloud} == {
        "cloud.functions.delete.word-stats-java",
        "cloud.helm.uninstall.control-plane",
    }
