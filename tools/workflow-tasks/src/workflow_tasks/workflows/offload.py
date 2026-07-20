"""Two-instance offload scenario: an edge control plane eagerly proxies to a cloud one.

The cloud instance runs the real function through the container-local
deployment backend; the edge registers the same function as LOCAL with an
`offload.mode=always` policy, so every invocation must come back with the
`X-NanoFaaS-Offloaded` header. A final negative check deletes the remote
function and requires the edge to answer 502 (no local fallback).
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import json
from typing import Literal

from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.workflows.validate import ValidateFunction


CLOUD_ENDPOINT = "http://127.0.0.1:19090"
CLOUD_MANAGEMENT = "http://127.0.0.1:19091"
EDGE_ENDPOINT = "http://127.0.0.1:18080"
EDGE_MANAGEMENT = "http://127.0.0.1:18081"
START_CLOUD_TASK_ID = "offload.start.cloud"
START_EDGE_TASK_ID = "offload.start.edge"
JAR_PATH = "platform/control-plane/build/libs/app.jar"

CLOUD_ARGV = (
    "java",
    "-jar",
    JAR_PATH,
    "--server.port=19090",
    "--management.server.port=19091",
    "--sync-queue.enabled=false",
    "--nanofaas.deployment.default-backend=container-local",
    "--nanofaas.container-local.runtime-adapter=docker",
    "--nanofaas.container-local.bind-host=127.0.0.1",
)
EDGE_ARGV = (
    "java",
    "-jar",
    JAR_PATH,
    "--server.port=18080",
    "--management.server.port=18081",
    "--sync-queue.enabled=false",
    f"--nanofaas.offload.target-url={CLOUD_ENDPOINT}",
)


@dataclass(frozen=True, slots=True)
class OffloadWorkflowRequest:
    functions: tuple[ValidateFunction, ...]

    def __post_init__(self) -> None:
        if not self.functions:
            raise ValueError("offload workflow requires at least one function")


def _task(task_id: str, *argv: str) -> CommandTaskSpec:
    return CommandTaskSpec(
        task_id=task_id,
        summary=task_id.replace(".", " "),
        argv=argv,
        role="host",
    )


def _bash(task_id: str, command: str) -> CommandTaskSpec:
    return _task(task_id, "bash", "-lc", command)


def _registration_body(function: ValidateFunction, side: Literal["cloud", "edge"]) -> str:
    body: dict[str, object] = {
        "name": function.name,
        "image": function.image,
        "executionMode": "DEPLOYMENT" if side == "cloud" else "LOCAL",
        "timeoutMs": function.timeout_ms,
        "concurrency": function.concurrency,
        "queueSize": function.queue_size,
        "maxRetries": function.max_retries,
    }
    if side == "edge":
        body["offload"] = {"mode": "always"}
    return json.dumps(body, separators=(",", ":"))


def _register(function: ValidateFunction, side: Literal["cloud", "edge"]) -> CommandTaskSpec:
    endpoint = CLOUD_ENDPOINT if side == "cloud" else EDGE_ENDPOINT
    return _task(
        f"offload.register.{side}.{function.key}",
        "curl",
        "-fsS",
        "-H",
        "Content-Type: application/json",
        "--data",
        _registration_body(function, side),
        f"{endpoint}/v1/functions",
    )


def offload_task_specs(request: OffloadWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    """Render the offload scenario without depending on an execution provider."""
    tasks: list[CommandTaskSpec] = [
        _task(
            "offload.build.control-plane",
            "./gradlew",
            ":control-plane:bootJar",
            "-PcontrolPlaneModules=offload,container-deployment-provider",
            "--quiet",
        )
    ]
    tasks.extend(
        _task(f"images.build.{function.key}", *function.build_argv)
        for function in request.functions
    )
    tasks.append(_task(START_CLOUD_TASK_ID, *CLOUD_ARGV))
    tasks.append(_task(START_EDGE_TASK_ID, *EDGE_ARGV))
    for function in request.functions:
        tasks.append(_register(function, "cloud"))
        tasks.append(_register(function, "edge"))
    for function in request.functions:
        tasks.append(
            _bash(
                f"offload.invoke.eager.{function.key}",
                'headers=$(mktemp) && curl -fsS -D "$headers" '
                "-H 'Content-Type: application/json' "
                f"--data '{function.payload}' "
                f"'{EDGE_ENDPOINT}/v1/functions/{function.name}:invoke' "
                '&& grep -qi "^x-nanofaas-offloaded:" "$headers"',
            )
        )
        tasks.append(
            _bash(
                f"offload.verify.metrics.{function.key}",
                f"metrics=$(curl -fsS '{EDGE_MANAGEMENT}/actuator/prometheus') "
                "&& printf '%s' \"$metrics\" | grep -F "
                f"'nanofaas_offload_total{{function=\"{function.name}\",trigger=\"eager\"}}' "
                "&& ! printf '%s' \"$metrics\" | grep -F 'nanofaas_offload_failure_total'",
            )
        )
    # last: no local fallback — removing the remote function must surface 502
    probe = request.functions[0]
    tasks.append(
        _bash(
            f"offload.verify.remote-missing.{probe.key}",
            f"curl -fsS -X DELETE '{CLOUD_ENDPOINT}/v1/functions/{probe.name}' "
            "&& test \"$(curl -s -o /dev/null -w '%{http_code}' "
            "-H 'Content-Type: application/json' "
            f"--data '{probe.payload}' "
            f"'{EDGE_ENDPOINT}/v1/functions/{probe.name}:invoke')\" = 502",
        )
    )
    return tuple(tasks)


def offload_cleanup_specs(request: OffloadWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    return tuple(
        replace(
            _task(
                f"offload.delete.{side}.{function.key}",
                "curl",
                "-fsS",
                "-X",
                "DELETE",
                f"{endpoint}/v1/functions/{function.name}",
            ),
            expected_exit_codes=frozenset({0, 7, 22}),
        )
        for function in request.functions
        for side, endpoint in (("edge", EDGE_ENDPOINT), ("cloud", CLOUD_ENDPOINT))
    )
