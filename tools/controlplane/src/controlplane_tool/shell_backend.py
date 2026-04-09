from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path
import subprocess
from threading import Thread
from typing import Callable

from controlplane_tool.console import has_workflow_sink, workflow_log


@dataclass(frozen=True)
class ShellExecutionResult:
    command: list[str]
    return_code: int
    stdout: str = ""
    stderr: str = ""
    dry_run: bool = False
    env: dict[str, str] = field(default_factory=dict)


OutputListener = Callable[[str, str], None]


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
    def __init__(self, output_listener: OutputListener | None = None) -> None:
        self.output_listener = output_listener

    def _emit_output(self, stream: str, line: str) -> None:
        if self.output_listener is not None:
            self.output_listener(stream, line)
        workflow_log(line, stream=stream)

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

        if self.output_listener is None and not has_workflow_sink():
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

        process = subprocess.Popen(
            command,
            cwd=cwd,
            env={**os.environ, **(env or {})},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=1,
        )

        stdout_chunks: list[str] = []
        stderr_chunks: list[str] = []

        def _pump(pipe, stream: str, chunks: list[str]) -> None:  # noqa: ANN001
            try:
                while True:
                    line = pipe.readline()
                    if line == "":
                        break
                    chunks.append(line)
                    self._emit_output(stream, line.rstrip("\n"))
            finally:
                pipe.close()

        stdout_thread = Thread(target=_pump, args=(process.stdout, "stdout", stdout_chunks))
        stderr_thread = Thread(target=_pump, args=(process.stderr, "stderr", stderr_chunks))
        stdout_thread.start()
        stderr_thread.start()
        return_code = process.wait()
        stdout_thread.join()
        stderr_thread.join()

        return ShellExecutionResult(
            command=command,
            return_code=return_code,
            stdout="".join(stdout_chunks),
            stderr="".join(stderr_chunks),
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
