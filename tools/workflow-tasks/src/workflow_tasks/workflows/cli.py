from __future__ import annotations

import json
import shlex
from dataclasses import dataclass, replace

from workflow_tasks.execution.roles import ExecutionRole
from workflow_tasks.tasks.models import CommandTaskSpec


@dataclass(frozen=True, slots=True)
class CliFunction:
    name: str
    image: str
    payload: str
    resources: dict[str, object] | None = None


@dataclass(frozen=True, slots=True)
class CliWorkflowRequest:
    functions: tuple[CliFunction, ...]
    cli_role: ExecutionRole = "host"
    namespace: str = "nanofaas-e2e"
    endpoint: str = "http://127.0.0.1:8080"
    binary: str = "clients/cli/build/install/nanofaas-cli/bin/nanofaas-cli"

    def __post_init__(self) -> None:
        if self.cli_role == "loadgen":
            raise ValueError("CLI workflow can run only on host or stack")
        if not self.functions:
            raise ValueError("CLI workflow requires at least one function")


def _task(request: CliWorkflowRequest, task_id: str, *argv: str) -> CommandTaskSpec:
    return CommandTaskSpec(
        task_id=task_id,
        summary=task_id.replace(".", " "),
        argv=argv,
        role=request.cli_role,
    )


def _command(request: CliWorkflowRequest, *arguments: str) -> tuple[str, ...]:
    return (
        request.binary,
        "--endpoint",
        request.endpoint,
        "--namespace",
        request.namespace,
        *arguments,
    )


def cli_task_specs(request: CliWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    tasks = [
        _task(
            request,
            "cli.build",
            "./gradlew",
            ":nanofaas-cli:installDist",
            "--no-daemon",
        )
    ]
    for function in request.functions:
        body: dict[str, object] = {
            "name": function.name,
            "image": function.image,
            "executionMode": "DEPLOYMENT",
            "timeoutMs": 5000,
            "concurrency": 2,
            "queueSize": 20,
            "maxRetries": 3,
        }
        if function.resources is not None:
            body["resources"] = function.resources
        manifest = json.dumps(body, separators=(",", ":"))
        apply = (
            f"manifest=$(mktemp); trap 'rm -f \"$manifest\"' EXIT; "
            f"printf '%s' {shlex.quote(manifest)} > \"$manifest\"; "
            + " ".join(
                shlex.quote(value)
                for value in _command(request, "fn", "apply", "--file", "$manifest")
            ).replace("'$manifest'", '"$manifest"')
        )
        tasks.append(
            _task(
                request,
                f"cli.function.apply.{function.name}",
                "bash",
                "-lc",
                apply,
            )
        )
    tasks.append(_task(request, "cli.function.list", *_command(request, "fn", "list")))
    for function in request.functions:
        invoke = " ".join(
            shlex.quote(value)
            for value in _command(request, "invoke", function.name, "--data", function.payload)
        )
        tasks.append(
            _task(
                request,
                f"cli.function.invoke.{function.name}",
                "bash",
                "-lc",
                " && ".join((
                    f"response=$({invoke})",
                    "printf '%s' \"$response\" | grep -q '\"status\":\"success\"'",
                    "printf '%s' \"$response\" | grep -q '\"output\"'",
                )),
            )
        )
    return tuple(tasks)


def cli_cleanup_specs(request: CliWorkflowRequest) -> tuple[CommandTaskSpec, ...]:
    return tuple(
        replace(
            _task(
                request,
                f"cli.function.delete.{function.name}",
                *_command(request, "fn", "delete", function.name),
            ),
            expected_exit_codes=frozenset({0, 1}),
        )
        for function in request.functions
    )
