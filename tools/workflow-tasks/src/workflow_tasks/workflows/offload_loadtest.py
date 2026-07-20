"""Offload load-test scenario: mixed-policy traffic against edge and cloud control planes.

Deploys the SAME control-plane image to two k3s clusters (edge on `stack`,
cloud on `cloud`) via `k8s_deployment_specs`, registers an offloadable
function on both (pressure-triggered offload from edge) and a
non-offloadable control function on edge only, then k6 drives mixed traffic
so a conservation checker can prove k6's offloaded-header count reconciles
with both control planes' Prometheus counters.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, replace
from typing import Literal

from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.workflows.validate import (
    Build,
    ValidateFunction,
    ValidateWorkflowRequest,
    k8s_deployment_specs,
    validate_cleanup_specs,
)

_ADDITIONAL_MODULES = ("offload", "async-queue", "sync-queue")
_EDGE_ENDPOINT = "http://localhost:30080"
_CLOUD_ENDPOINT = "http://localhost:30080"
_EXTRA_ENV_INDEX_RE = re.compile(r"controlPlane\.extraEnv\[(\d+)\]\.name=")


@dataclass(frozen=True, slots=True)
class OffloadLoadtestRequest:
    offloadable: ValidateFunction
    control: ValidateFunction
    build: Build = "docker"
    namespace: str = "nanofaas-e2e"
    registry: str = "localhost:5000"


def _workflow_request(
    request: OffloadLoadtestRequest, functions: tuple[ValidateFunction, ...]
) -> ValidateWorkflowRequest:
    return ValidateWorkflowRequest(
        backend="k8s",
        functions=functions,
        build=request.build,
        namespace=request.namespace,
        registry=request.registry,
        additional_modules=_ADDITIONAL_MODULES,
    )


def _next_extra_env_index(argv: tuple[str, ...]) -> int:
    indices = [
        int(match.group(1))
        for argument in argv
        if (match := _EXTRA_ENV_INDEX_RE.search(argument)) is not None
    ]
    return max(indices) + 1 if indices else 0


def edge_deployment_specs(
    request: OffloadLoadtestRequest, offload_target_url: str
) -> tuple[CommandTaskSpec, ...]:
    specs = k8s_deployment_specs(
        _workflow_request(request, (request.offloadable, request.control)),
        expose_node_ports=True,
        sync_queue_admission_enabled=True,
    )

    def _with_offload_target(spec: CommandTaskSpec) -> CommandTaskSpec:
        if spec.task_id != "helm.deploy.control-plane":
            return spec
        index = _next_extra_env_index(spec.argv)
        extra = (
            "--set",
            f"controlPlane.extraEnv[{index}].name=NANOFAAS_OFFLOAD_TARGETURL",
            "--set",
            f"controlPlane.extraEnv[{index}].value={offload_target_url}",
        )
        return replace(spec, argv=spec.argv + extra)

    return tuple(_with_offload_target(spec) for spec in specs)


def cloud_deployment_specs(request: OffloadLoadtestRequest) -> tuple[CommandTaskSpec, ...]:
    specs = k8s_deployment_specs(
        _workflow_request(request, (request.offloadable,)),
        expose_node_ports=True,
        sync_queue_admission_enabled=True,
    )
    return tuple(
        replace(spec, role="cloud", task_id=f"cloud.{spec.task_id}") for spec in specs
    )


def _registration_body(
    function: ValidateFunction, offload: dict[str, object] | None
) -> dict[str, object]:
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
    if offload is not None:
        body["offload"] = offload
    return body


def _register_task(
    prefix: Literal["edge", "cloud"],
    function: ValidateFunction,
    endpoint: str,
    offload: dict[str, object] | None,
    *,
    role: Literal["stack", "cloud"],
) -> CommandTaskSpec:
    task_id = f"offload-loadtest.register.{prefix}.{function.name}"
    return CommandTaskSpec(
        task_id=task_id,
        summary=task_id.replace(".", " "),
        argv=(
            "curl",
            "-fsS",
            "-H",
            "Content-Type: application/json",
            "--data",
            json.dumps(_registration_body(function, offload), separators=(",", ":")),
            f"{endpoint}/v1/functions",
        ),
        role=role,
    )


def offload_registration_specs(request: OffloadLoadtestRequest) -> tuple[CommandTaskSpec, ...]:
    # The cloud copy must absorb whatever the edge sheds, not reproduce the edge's
    # own admission pressure — registering it with the edge's tight concurrency
    # made cloud itself reject most offloaded calls (429), which the edge then
    # counts as nanofaas_offload_failure_total.
    cloud_offloadable = replace(request.offloadable, concurrency=20, queue_size=100)
    return (
        _register_task("edge", request.offloadable, _EDGE_ENDPOINT, None, role="stack"),
        _register_task(
            "edge", request.control, _EDGE_ENDPOINT, {"enabled": False}, role="stack"
        ),
        _register_task("cloud", cloud_offloadable, _CLOUD_ENDPOINT, None, role="cloud"),
    )


def offload_cleanup_specs(request: OffloadLoadtestRequest) -> tuple[CommandTaskSpec, ...]:
    edge = validate_cleanup_specs(_workflow_request(request, (request.offloadable, request.control)))
    cloud = tuple(
        replace(spec, role="cloud", task_id=f"cloud.{spec.task_id}")
        for spec in validate_cleanup_specs(_workflow_request(request, (request.offloadable,)))
    )
    return edge + cloud
