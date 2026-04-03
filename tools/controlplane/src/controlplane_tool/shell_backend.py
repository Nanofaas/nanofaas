from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path
import subprocess


@dataclass(frozen=True)
class ShellExecutionResult:
    command: list[str]
    return_code: int
    stdout: str = ""
    stderr: str = ""
    dry_run: bool = False
    env: dict[str, str] = field(default_factory=dict)


class ShellBackend:
    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        raise NotImplementedError


class SubprocessShell(ShellBackend):
    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if dry_run:
            return ShellExecutionResult(
                command=command,
                return_code=0,
                dry_run=True,
                env=env or {},
            )

        completed = subprocess.run(
            command,
            cwd=cwd,
            env={**os.environ, **(env or {})},
            text=True,
            capture_output=True,
            check=False,
        )
        return ShellExecutionResult(
            command=command,
            return_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            dry_run=False,
            env=env or {},
        )


@dataclass
class RecordingShell(ShellBackend):
    commands: list[list[str]] = field(default_factory=list)

    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        _ = cwd, env
        self.commands.append(command)
        return ShellExecutionResult(
            command=command,
            return_code=0,
            dry_run=dry_run,
            env=env or {},
        )


@dataclass
class ScriptedShell(ShellBackend):
    stdout_map: dict[tuple[str, ...], str] = field(default_factory=dict)
    stderr_map: dict[tuple[str, ...], str] = field(default_factory=dict)
    return_code_map: dict[tuple[str, ...], int] = field(default_factory=dict)
    commands: list[list[str]] = field(default_factory=list)

    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        _ = cwd
        self.commands.append(command)
        key = tuple(command)
        return ShellExecutionResult(
            command=command,
            return_code=self.return_code_map.get(key, 0),
            stdout=self.stdout_map.get(key, ""),
            stderr=self.stderr_map.get(key, ""),
            dry_run=dry_run,
            env=env or {},
        )
