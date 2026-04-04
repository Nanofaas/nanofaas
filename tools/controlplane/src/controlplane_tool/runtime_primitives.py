"""
runtime_primitives.py

Reusable Python building blocks for the legacy-shell-retirement migration.
These classes replace small shell helpers currently scattered across
scripts/lib/e2e-*-backend.sh and scripts/lib/e2e-k3s-common.sh.

Milestones that depend on this module: M9, M10, M11, M12.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)


@dataclass
class CommandRunner:
    """Thin adapter that couples a ShellBackend with a working-directory root.

    This is the canonical way for Python orchestration code to run subprocess
    commands without hard-coding shell scripts.
    """

    shell: ShellBackend = field(default_factory=SubprocessShell)
    repo_root: Path = field(default_factory=Path.cwd)

    def run(
        self,
        command: list[str],
        *,
        dry_run: bool = False,
        env: dict[str, str] | None = None,
        cwd: Path | None = None,
    ) -> ShellExecutionResult:
        return self.shell.run(
            command,
            cwd=cwd or self.repo_root,
            env=env,
            dry_run=dry_run,
        )


@dataclass
class ContainerRuntimeOps:
    """Docker-compatible runtime operations (docker / podman / nerdctl)."""

    runner: CommandRunner
    runtime: str = "docker"

    def build(
        self,
        tag: str,
        context: Path,
        *,
        dockerfile: Path | None = None,
        build_args: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = [self.runtime, "build", "-t", tag]
        if dockerfile is not None:
            command.extend(["-f", str(dockerfile)])
        for key, value in (build_args or {}).items():
            command.extend(["--build-arg", f"{key}={value}"])
        command.append(str(context))
        return self.runner.run(command, dry_run=dry_run)

    def remove(
        self,
        *names: str,
        force: bool = True,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = [self.runtime, "rm"]
        if force:
            command.append("-f")
        command.extend(names)
        return self.runner.run(command, dry_run=dry_run)

    def run_container(
        self,
        image: str,
        *,
        name: str | None = None,
        detach: bool = False,
        ports: dict[int, int] | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = [self.runtime, "run"]
        if detach:
            command.append("-d")
        if name:
            command.extend(["--name", name])
        for host_port, container_port in (ports or {}).items():
            command.extend(["-p", f"{host_port}:{container_port}"])
        for key, value in (env or {}).items():
            command.extend(["-e", f"{key}={value}"])
        command.append(image)
        return self.runner.run(command, dry_run=dry_run)

    def list_containers(
        self,
        *,
        name_filter: str | None = None,
        all_containers: bool = False,
        format_str: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = [self.runtime, "ps"]
        if all_containers:
            command.append("-a")
        if name_filter:
            command.extend(["--filter", f"name={name_filter}"])
        if format_str:
            command.extend(["--format", format_str])
        return self.runner.run(command, dry_run=dry_run)

    def push(self, tag: str, *, dry_run: bool = False) -> ShellExecutionResult:
        return self.runner.run([self.runtime, "push", tag], dry_run=dry_run)


@dataclass
class KubectlOps:
    """kubectl operations with optional kubeconfig binding."""

    runner: CommandRunner
    kubeconfig: str | None = None
    namespace: str | None = None

    def _base(self) -> list[str]:
        command = ["kubectl"]
        if self.kubeconfig:
            command.extend(["--kubeconfig", self.kubeconfig])
        if self.namespace:
            command.extend(["-n", self.namespace])
        return command

    def apply(self, manifest: Path, *, dry_run: bool = False) -> ShellExecutionResult:
        return self.runner.run(
            [*self._base(), "apply", "-f", str(manifest)],
            dry_run=dry_run,
        )

    def delete(
        self,
        resource: str,
        name: str,
        *,
        ignore_not_found: bool = True,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = [*self._base(), "delete", resource, name]
        if ignore_not_found:
            command.append("--ignore-not-found")
        return self.runner.run(command, dry_run=dry_run)

    def rollout_restart(
        self,
        resource: str,
        name: str,
        *,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.runner.run(
            [*self._base(), "rollout", "restart", f"{resource}/{name}"],
            dry_run=dry_run,
        )

    def exec(
        self,
        pod: str,
        command: str,
        *,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.runner.run(
            [*self._base(), "exec", pod, "--", "bash", "-lc", command],
            dry_run=dry_run,
        )


# ---------------------------------------------------------------------------
# JSON / file helpers (replaces inline python3 -c snippets in shell scripts)
# ---------------------------------------------------------------------------

def read_json_field(path: Path, field: str) -> Any:
    """Read a dot-separated field path from a JSON file.

    Example::
        read_json_field(Path("response.json"), "status")
        read_json_field(Path("data.json"), "a.b.c")
    """
    data: Any = json.loads(Path(path).read_text(encoding="utf-8"))
    for part in field.split("."):
        if part == "":
            continue
        if isinstance(data, list):
            data = data[int(part)]
        else:
            data = data[part]
    return data


def write_json_file(path: Path, data: dict[str, Any]) -> None:
    """Write a dictionary as a pretty-printed JSON file."""
    Path(path).write_text(
        json.dumps(data, indent=2, sort_keys=True),
        encoding="utf-8",
    )


def wrap_payload(payload_path: Path, destination: Path) -> None:
    """Wrap a raw payload file in {"input": ...} for nanofaas invocation."""
    with payload_path.open(encoding="utf-8") as handle:
        payload = json.load(handle)
    destination.write_text(
        json.dumps({"input": payload}, separators=(",", ":")),
        encoding="utf-8",
    )
