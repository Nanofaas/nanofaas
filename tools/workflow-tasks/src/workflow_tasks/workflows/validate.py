from __future__ import annotations

import json
import shlex
from dataclasses import dataclass, replace
from typing import Literal

from workflow_tasks.components.helm import control_plane_helm_values
from workflow_tasks.tasks.models import CommandTaskSpec

Backend = Literal["pool", "container", "k8s"]
Build = Literal["docker", "buildpack"]


@dataclass(frozen=True, slots=True)
class ValidateFunction:
    key: str
    name: str
    image: str
    build_argv: tuple[str, ...]
    payload: str
    resources: dict[str, object] | None = None
    scaling_config: dict[str, object] | None = None
    timeout_ms: int = 5000
    concurrency: int = 2
    queue_size: int = 20
    max_retries: int = 3


@dataclass(frozen=True, slots=True)
class ValidateWorkflowRequest:
    backend: Backend
    functions: tuple[ValidateFunction, ...]
    build: Build = "docker"
    namespace: str = "nanofaas-e2e"
    registry: str = "localhost:5000"
    additional_modules: tuple[str, ...] = ()
    build_images: bool = True
    control_plane_image: str | None = None

    def __post_init__(self) -> None:
        if not self.functions:
            raise ValueError("validate workflow requires at least one function")
        if not self.build_images and not self.control_plane_image:
            raise ValueError("control_plane_image is required when build_images is false")


def _task(task_id: str, *argv: str, role: Literal["host", "stack"] = "host") -> CommandTaskSpec:
    return CommandTaskSpec(task_id=task_id, summary=task_id.replace(".", " "), argv=argv, role=role)


def _build(request: ValidateWorkflowRequest, role: Literal["host", "stack"]) -> CommandTaskSpec:
    target = (
        ":control-plane:bootBuildImage"
        if request.build == "buildpack"
        else ":control-plane:bootJar"
    )
    targets = (target,)
    if request.backend == "k8s" and request.build == "docker":
        targets += (":services:java:warm-echo:bootJar",)
    modules = {
        "container": "container-deployment-provider",
        "k8s": "k8s-deployment-provider",
    }
    selected_modules = (
        ((modules[request.backend],) if request.backend in modules else ())
        + request.additional_modules
    )
    module_args = (
        (f"-PcontrolPlaneModules={','.join(selected_modules)}",) if selected_modules else ()
    )
    return _task(
        "build.jvm",
        "./gradlew",
        *targets,
        *module_args,
        "--no-daemon",
        role=role,
    )


def _endpoint(request: ValidateWorkflowRequest) -> str:
    if request.backend != "k8s":
        return "http://127.0.0.1:18080"
    return (
        "http://$(kubectl -n "
        f"{request.namespace} get service control-plane "
        "-o=jsonpath='{.spec.clusterIP}'):8080"
    )


def _curl(request: ValidateWorkflowRequest, *arguments: str) -> tuple[str, ...]:
    if request.backend != "k8s":
        return ("curl", *arguments)
    command = " ".join(json.dumps(argument) for argument in ("curl", *arguments))
    return ("bash", "-lc", command)


def _k8s_cpu(value: object) -> str:
    number = float(str(value))
    return str(int(number)) if number.is_integer() else f"{int(number * 1000)}m"


def _resource_inspection(
    request: ValidateWorkflowRequest,
    function: ValidateFunction,
    role: Literal["host", "stack"],
) -> CommandTaskSpec:
    if request.backend == "container":
        container = f"nanofaas-{function.name}-r1"
        if function.resources is None:
            argv = ("docker", "inspect", "--format={{json .HostConfig}}", container)
        else:
            requests = function.resources.get("requests", {})
            limits = function.resources.get("limits", {})
            assert isinstance(requests, dict) and isinstance(limits, dict)
            request_memory = requests.get("memoryMiB")
            limit_memory = limits.get("memoryMiB")
            expected = " ".join(
                str(value)
                for value in (
                    max(2, int(float(str(requests.get("cpu", 0))) * 1024 + 0.5)),
                    int(float(str(limits.get("cpu", 0))) * 1_000_000_000),
                    0 if request_memory == limit_memory else int(request_memory or 0) * 1024 * 1024,
                    int(limit_memory or 0) * 1024 * 1024,
                )
            )
            command = (
                "actual=$(docker inspect --format "
                "'{{.HostConfig.CpuShares}} {{.HostConfig.NanoCpus}} "
                f"{{{{.HostConfig.MemoryReservation}}}} {{{{.HostConfig.Memory}}}}' {shlex.quote(container)}); "
                f'test "$actual" = {shlex.quote(expected)}'
            )
            argv = ("bash", "-lc", command)
        return _task(f"resources.inspect.container.{function.key}", *argv, role=role)

    base = (
        "kubectl",
        "get",
        "deployment",
        f"fn-{function.name}",
        "-n",
        request.namespace,
    )
    if function.resources is None:
        argv = (*base, "-o=jsonpath={.spec.template.spec.containers[0].resources}")
    else:
        requests = function.resources.get("requests", {})
        limits = function.resources.get("limits", {})
        assert isinstance(requests, dict) and isinstance(limits, dict)
        expected = " ".join(
            (
                _k8s_cpu(requests.get("cpu", 0)),
                f"{requests.get('memoryMiB', 0)}Mi",
                _k8s_cpu(limits.get("cpu", 0)),
                f"{limits.get('memoryMiB', 0)}Mi",
            )
        )
        jsonpath = (
            "{.spec.template.spec.containers[0].resources.requests.cpu} "
            "{.spec.template.spec.containers[0].resources.requests.memory} "
            "{.spec.template.spec.containers[0].resources.limits.cpu} "
            "{.spec.template.spec.containers[0].resources.limits.memory}"
        )
        command = (
            "actual=$("
            + " ".join(shlex.quote(value) for value in (*base, f"-o=jsonpath={jsonpath}"))
            + f'); test "$actual" = {shlex.quote(expected)}'
        )
        argv = ("bash", "-lc", command)
    return _task(f"resources.inspect.k8s.{function.key}", *argv, role=role)


def _set_args(values: dict[str, str]) -> tuple[str, ...]:
    return tuple(
        argument
        for key, value in values.items()
        for argument in ("--set", f"{key}={value}")
    )


def k8s_deployment_specs(
    request: ValidateWorkflowRequest,
    *,
    expose_node_ports: bool = False,
    metrics_profile: str | None = None,
    sync_queue_admission_enabled: bool = False,
) -> tuple[CommandTaskSpec, ...]:
    if request.backend != "k8s":
        raise ValueError("Kubernetes deployment specs require the k8s backend")

    tasks = [_task("stack.preflight", "kubectl", "version", "--client", role="stack")]
    if request.build_images:
        tasks.append(_build(request, "stack"))
        for name, image, dockerfile, context in (
            (
                "control-plane",
                f"{request.registry}/nanofaas/control-plane:e2e",
                "platform/control-plane/Dockerfile",
                "platform/control-plane",
            ),
            (
                "warm-echo",
                f"{request.registry}/nanofaas/java-warm-echo:e2e",
                "services/java/warm-echo/Dockerfile",
                "services/java/warm-echo",
            ),
        ):
            tasks.append(
                _task(
                    f"images.build.{name}",
                    "docker",
                    "build",
                    "-f",
                    dockerfile,
                    "-t",
                    image,
                    context,
                    role="stack",
                )
            )
            tasks.append(_task(f"images.push.{name}", "docker", "push", image, role="stack"))

        for function in request.functions:
            tasks.append(_task(f"images.build.{function.key}", *function.build_argv, role="stack"))
            tasks.append(
                _task(
                    f"images.push.{function.key}",
                    "docker",
                    "push",
                    function.image,
                    role="stack",
                )
            )

    control_plane_image = request.control_plane_image or (
        f"{request.registry}/nanofaas/control-plane:e2e"
    )
    control_values = control_plane_helm_values(
        namespace=request.namespace,
        control_plane_image=control_plane_image,
        expose_node_port=expose_node_ports,
        metrics_profile=metrics_profile,
        sync_queue_admission_enabled=sync_queue_admission_enabled,
    )
    tasks.append(
        _task(
            "helm.deploy.control-plane",
            "helm",
            "upgrade",
            "--install",
            "nanofaas",
            "deploy/helm/nanofaas",
            "--namespace",
            request.namespace,
            "--create-namespace",
            "--wait",
            *_set_args(control_values),
            role="stack",
        )
    )
    return tuple(tasks)


def registration_specs(request: ValidateWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    role: Literal["host", "stack"] = "stack" if request.backend == "k8s" else "host"
    endpoint = _endpoint(request)
    specs: list[CommandTaskSpec] = []
    for function in request.functions:
        body: dict[str, object] = {
            "name": function.name,
            "image": function.image,
            "executionMode": "DEPLOYMENT",
            "timeoutMs": function.timeout_ms,
            "concurrency": function.concurrency,
            "queueSize": function.queue_size,
            "maxRetries": function.max_retries,
        }
        if function.resources is not None:
            body["resources"] = function.resources
        if function.scaling_config is not None:
            body["scalingConfig"] = function.scaling_config
        specs.append(
            _task(
                f"functions.register.{function.key}",
                *_curl(
                    request,
                    "-fsS",
                    "-H",
                    "Content-Type: application/json",
                    "--data",
                    json.dumps(body, separators=(",", ":")),
                    f"{endpoint}/v1/functions",
                ),
                role=role,
            )
        )
    return tuple(specs)


def validate_task_specs(request: ValidateWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    """Render the validate workflow without depending on an execution provider."""
    if request.backend == "pool":
        return (
            _build(request, "host"),
            _task(
                "validate.pool",
                "./gradlew",
                ":control-plane:test",
                "--tests",
                "*Pool*",
                "--no-daemon",
            ),
        )

    role: Literal["host", "stack"] = "stack" if request.backend == "k8s" else "host"
    if request.backend == "k8s":
        tasks = list(
            k8s_deployment_specs(
                request,
                metrics_profile="advanced",
                sync_queue_admission_enabled=True,
            )
        )
    else:
        tasks = [_build(request, role)]
        for function in request.functions:
            tasks.append(
                _task(f"images.build.{function.key}", *function.build_argv, role=role)
            )

    if request.backend == "container":
        tasks.append(
            _task(
                "container.start.control-plane",
                "java",
                "-jar",
                "platform/control-plane/build/libs/app.jar",
                "--server.port=18080",
                "--management.server.port=18081",
                "--sync-queue.enabled=false",
                "--nanofaas.deployment.default-backend=container-local",
                "--nanofaas.container-local.runtime-adapter=docker",
                "--nanofaas.container-local.bind-host=127.0.0.1",
            )
        )
    tasks.extend(registration_specs(request))
    endpoint = _endpoint(request)
    for function in request.functions:
        tasks.append(
            _task(
                f"functions.invoke.{function.key}",
                *_curl(
                    request,
                    "-fsS",
                    "-H",
                    "Content-Type: application/json",
                    "--data",
                    function.payload,
                    f"{endpoint}/v1/functions/{function.name}:invoke",
                ),
                role=role,
            )
        )
        tasks.append(_resource_inspection(request, function, role))
    return tuple(tasks)


def validate_cleanup_specs(
    request: ValidateWorkflowRequest,
) -> tuple[CommandTaskSpec, ...]:
    if request.backend == "pool":
        return ()
    role: Literal["host", "stack"] = "stack" if request.backend == "k8s" else "host"
    endpoint = _endpoint(request)
    cleanup = [
        replace(
            _task(
                f"functions.delete.{function.key}",
                *_curl(
                    request,
                    "-fsS",
                    "-X",
                    "DELETE",
                    f"{endpoint}/v1/functions/{function.name}",
                ),
                role=role,
            ),
            expected_exit_codes=frozenset({0, 7, 22}),
        )
        for function in request.functions
    ]
    if request.backend == "k8s":
        cleanup.append(
            _task(
                "helm.uninstall.control-plane",
                "helm",
                "uninstall",
                "nanofaas",
                "--namespace",
                request.namespace,
                "--ignore-not-found",
                role=role,
            )
        )
    return tuple(cleanup)
