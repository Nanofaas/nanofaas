import pytest

from workflow_tasks.workflows.validate import (
    ValidateFunction,
    ValidateWorkflowRequest,
    k8s_deployment_specs,
    registration_specs,
    validate_cleanup_specs,
    validate_task_specs,
)


FUNCTION = ValidateFunction(
    key="word-stats-java",
    name="word-stats-java",
    image="localhost:5000/nanofaas/java-word-stats:e2e",
    build_argv=(
        "./gradlew",
        ":functions:java:word-stats:bootBuildImage",
        "-PfunctionImage=localhost:5000/nanofaas/java-word-stats:e2e",
        "--quiet",
    ),
    payload='{"input":{"text":"hello world"}}',
)


def _ids(backend: str) -> list[str]:
    return [
        task.task_id
        for task in validate_task_specs(
            ValidateWorkflowRequest(
                backend=backend,
                build="docker",
                functions=(FUNCTION,),
                namespace="nanofaas-e2e",
            )
        )
    ]


def test_pool_validation_is_the_small_local_regression_suite() -> None:
    assert _ids("pool") == ["build.jvm", "validate.pool"]


def test_container_validation_includes_explicit_resource_inspection() -> None:
    assert _ids("container") == [
        "build.jvm",
        "images.build.word-stats-java",
        "container.start.control-plane",
        "functions.register.word-stats-java",
        "functions.invoke.word-stats-java",
        "resources.inspect.container.word-stats-java",
    ]


def test_kubernetes_validation_uses_stack_role_and_inspects_requests_and_limits() -> None:
    specs = validate_task_specs(
        ValidateWorkflowRequest(
            backend="k8s",
            build="docker",
            functions=(FUNCTION,),
            namespace="research",
        )
    )

    assert [task.task_id for task in specs] == [
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
        "functions.invoke.word-stats-java",
        "resources.inspect.k8s.word-stats-java",
    ]
    assert specs[0].role == "stack"
    assert all(task.role == "stack" for task in specs[2:])
    assert ("-n", "research") == specs[-1].argv[4:6]
    assert specs[-1].argv[3] == "fn-word-stats-java"


def test_kubernetes_docker_build_creates_both_core_jars() -> None:
    specs = validate_task_specs(
        ValidateWorkflowRequest(backend="k8s", build="docker", functions=(FUNCTION,))
    )

    assert specs[1].argv == (
        "./gradlew",
        ":control-plane:bootJar",
        ":services:java:warm-echo:bootJar",
        "-PcontrolPlaneModules=k8s-deployment-provider",
        "--no-daemon",
    )


def test_kubernetes_validation_enables_junit_queue_and_metrics_contracts() -> None:
    specs = validate_task_specs(
        ValidateWorkflowRequest(backend="k8s", build="docker", functions=(FUNCTION,))
    )

    argv = next(
        spec for spec in specs if spec.task_id == "helm.deploy.control-plane"
    ).argv
    settings = [
        value for value in argv if value.startswith("controlPlane.extraEnv[")
    ]
    admission = next(
        index
        for index, value in enumerate(settings)
        if value.endswith("=SYNC_QUEUE_ADMISSION_ENABLED")
    )
    metrics = next(
        index
        for index, value in enumerate(settings)
        if value.endswith("=NANOFAAS_METRICS_PROFILE")
    )

    assert settings[admission + 1].endswith("=true")
    assert settings[metrics + 1].endswith("=advanced")


def test_kubernetes_deployment_specs_can_expose_loadtest_node_ports() -> None:
    request = ValidateWorkflowRequest(backend="k8s", build="docker", functions=(FUNCTION,))

    specs = k8s_deployment_specs(request, expose_node_ports=True)

    assert [spec.task_id for spec in specs] == [
        "stack.preflight",
        "build.jvm",
        "images.build.control-plane",
        "images.push.control-plane",
        "images.build.warm-echo",
        "images.push.warm-echo",
        "images.build.word-stats-java",
        "images.push.word-stats-java",
        "helm.deploy.control-plane",
    ]
    control_plane = specs[-1]
    assert "controlPlane.service.type=NodePort" in control_plane.argv
    assert "prometheus.create=true" in control_plane.argv


def test_prebuilt_kubernetes_deployment_skips_builds_and_uses_exact_images() -> None:
    function = replace(
        FUNCTION,
        image="localhost:5000/nanofaas/java-word-stats:v0.18.0-amd64-native",
    )
    request = ValidateWorkflowRequest(
        backend="k8s",
        functions=(function,),
        build_images=False,
        control_plane_image="localhost:5000/nanofaas/control-plane:v0.18.0-amd64-native",
    )

    specs = k8s_deployment_specs(request)

    assert [spec.task_id for spec in specs] == [
        "stack.preflight",
        "helm.deploy.control-plane",
    ]
    assert "controlPlane.image.repository=localhost:5000/nanofaas/control-plane" in specs[-1].argv
    assert "controlPlane.image.tag=v0.18.0-amd64-native" in specs[-1].argv
    registration = registration_specs(request)[0]
    assert function.image in registration.argv[-1]


def test_prebuilt_kubernetes_deployment_requires_control_plane_image() -> None:
    with pytest.raises(
        ValueError,
        match="control_plane_image is required when build_images is false",
    ):
        ValidateWorkflowRequest(
            backend="k8s",
            functions=(FUNCTION,),
            build_images=False,
        )


def test_registration_specs_are_reusable_without_invocation() -> None:
    request = ValidateWorkflowRequest(backend="k8s", functions=(FUNCTION,))

    specs = registration_specs(request)

    assert [spec.task_id for spec in specs] == ["functions.register.word-stats-java"]


def test_registration_specs_include_optional_scaling_config() -> None:
    target = ValidateFunction(
        key=FUNCTION.key,
        name=FUNCTION.name,
        image=FUNCTION.image,
        build_argv=FUNCTION.build_argv,
        payload=FUNCTION.payload,
        scaling_config={"strategy": "INTERNAL", "minReplicas": 0, "maxReplicas": 5},
    )

    spec = registration_specs(
        ValidateWorkflowRequest(backend="k8s", functions=(target,))
    )[0]

    command = " ".join(spec.argv)
    assert "scalingConfig" in command
    assert "INTERNAL" in command
    assert "maxReplicas" in command


def test_k8s_build_can_include_additional_control_plane_modules() -> None:
    specs = k8s_deployment_specs(
        ValidateWorkflowRequest(
            backend="k8s",
            functions=(FUNCTION,),
            additional_modules=("autoscaler",),
        )
    )

    build = next(spec for spec in specs if spec.task_id == "build.jvm")
    assert "-PcontrolPlaneModules=k8s-deployment-provider,autoscaler" in build.argv
    assert specs[0].role == "stack"


def test_buildpack_changes_only_the_jvm_build_command() -> None:
    specs = validate_task_specs(
        ValidateWorkflowRequest(
            backend="pool",
            build="buildpack",
            functions=(FUNCTION,),
        )
    )

    assert specs[0].argv == ("./gradlew", ":control-plane:bootBuildImage", "--no-daemon")


def test_function_commands_use_resolved_data_without_name_conventions() -> None:
    odd = ValidateFunction(
        key="catalog-key",
        name="api-name",
        image="registry.example/research/image:v2",
        build_argv=("custom-builder", "--fast"),
        payload='{"input":42}',
    )
    specs = validate_task_specs(ValidateWorkflowRequest(backend="container", functions=(odd,)))

    assert specs[1].argv == odd.build_argv
    assert "api-name" in specs[3].argv[-2]
    assert "registry.example/research/image:v2" in specs[3].argv[-2]
    assert specs[4].argv[-2] == '{"input":42}'
    assert specs[4].argv[-1].endswith("/v1/functions/api-name:invoke")


def test_cleanup_deletes_functions_before_kubernetes_releases() -> None:
    specs = validate_cleanup_specs(ValidateWorkflowRequest(backend="k8s", functions=(FUNCTION,)))

    assert [spec.task_id for spec in specs] == [
        "functions.delete.word-stats-java",
        "helm.uninstall.control-plane",
    ]
    assert all(spec.role == "stack" for spec in specs)


def test_resource_inspections_assert_provider_specific_values() -> None:
    function = replace(
        FUNCTION,
        resources={
            "requests": {"cpu": 0.25, "memoryMiB": 128},
            "limits": {"cpu": 1.0, "memoryMiB": 512},
        },
    )

    container = validate_task_specs(
        ValidateWorkflowRequest(backend="container", functions=(function,))
    )[-1]
    kubernetes = validate_task_specs(ValidateWorkflowRequest(backend="k8s", functions=(function,)))[
        -1
    ]

    assert "256 1000000000 134217728 536870912" in container.argv[-1]
    assert "250m 128Mi 1 512Mi" in kubernetes.argv[-1]


from dataclasses import replace
