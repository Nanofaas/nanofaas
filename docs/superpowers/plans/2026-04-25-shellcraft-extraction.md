# shellcraft — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `shell_backend.py`, `runtime_primitives.py`, and `net_utils.py` from `controlplane-tool` into a standalone Python library called `shellcraft` with no external dependencies, then wire `controlplane-tool` to use it via a backward-compatible shim.

**Architecture:** New standalone repo `shellcraft/` with three modules (`backend`, `runners`, `net`), stdlib-only, Python ≥ 3.11. After extraction, `controlplane-tool` replaces the three original files with shim re-exports that preserve existing behavior (including TUI workflow logging) so no other callsite needs to change.

**Tech Stack:** Python 3.11+, setuptools, pytest, uv (local install during dev)

---

## File map

### New repo: `~/shellcraft/` (create at a path of your choice)

| File | Responsibility |
|---|---|
| `src/shellcraft/__init__.py` | Re-exports public API |
| `src/shellcraft/backend.py` | `ShellBackend`, `ShellExecutionResult`, `SubprocessShell`, `RecordingShell`, `ScriptedShell` |
| `src/shellcraft/runners.py` | `CommandRunner`, `PlannedCommand`, `ContainerRuntimeOps`, `KubectlOps`, `read_json_field`, `write_json_file`, `wrap_payload` |
| `src/shellcraft/net.py` | `is_port_free`, `pick_local_port` |
| `tests/test_backend.py` | Tests ported from `controlplane-tool/tests/test_shell_backend.py` |
| `tests/test_runners.py` | Tests ported from `controlplane-tool/tests/test_runtime_primitives.py` |
| `tests/test_net.py` | Tests ported from `controlplane-tool/tests/test_net_utils.py` |
| `pyproject.toml` | Package metadata, no runtime deps |

### Modified in `tools/controlplane/`

| File | Change |
|---|---|
| `pyproject.toml` | Add `shellcraft` dependency via local path |
| `src/controlplane_tool/shell_backend.py` | Replace with shim: re-exports from shellcraft + workflow-aware SubprocessShell subclass |
| `src/controlplane_tool/runtime_primitives.py` | Replace with shim: `from shellcraft.runners import *` |
| `src/controlplane_tool/net_utils.py` | Replace with shim: `from shellcraft.net import *` |

---

## Task 1: Bootstrap the shellcraft repo

**Files:**
- Create: `~/shellcraft/pyproject.toml`
- Create: `~/shellcraft/src/shellcraft/__init__.py`
- Create: `~/shellcraft/src/shellcraft/backend.py`
- Create: `~/shellcraft/src/shellcraft/runners.py`
- Create: `~/shellcraft/src/shellcraft/net.py`
- Create: `~/shellcraft/tests/__init__.py`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p ~/shellcraft/src/shellcraft ~/shellcraft/tests
touch ~/shellcraft/tests/__init__.py
```

- [ ] **Step 2: Write pyproject.toml**

```toml
# ~/shellcraft/pyproject.toml
[project]
name = "shellcraft"
version = "0.1.0"
description = "Generic subprocess orchestration toolkit for Python DevOps tooling"
requires-python = ">=3.11"
dependencies = []

[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
package-dir = {"" = "src"}

[tool.setuptools.packages.find]
where = ["src"]

[dependency-groups]
dev = [
    "pytest>=8.3.4",
]
```

- [ ] **Step 3: Create empty stub modules**

```python
# ~/shellcraft/src/shellcraft/__init__.py
# (leave empty for now)
```

```python
# ~/shellcraft/src/shellcraft/backend.py
# (leave empty for now)
```

```python
# ~/shellcraft/src/shellcraft/runners.py
# (leave empty for now)
```

```python
# ~/shellcraft/src/shellcraft/net.py
# (leave empty for now)
```

- [ ] **Step 4: Init git repo and install in dev mode**

```bash
cd ~/shellcraft
git init
uv venv
uv pip install -e ".[dev]"
git add -A
git commit -m "chore: bootstrap shellcraft package"
```

---

## Task 2: Port backend.py

**Files:**
- Create: `~/shellcraft/tests/test_backend.py`
- Modify: `~/shellcraft/src/shellcraft/backend.py`

- [ ] **Step 1: Write the failing tests**

```python
# ~/shellcraft/tests/test_backend.py
from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from shellcraft.backend import (
    RecordingShell,
    ScriptedShell,
    ShellExecutionResult,
    SubprocessShell,
)


def test_shell_execution_result_ok_on_zero_return_code() -> None:
    r = ShellExecutionResult(command=["echo", "hi"], return_code=0)
    assert r.return_code == 0


def test_shell_execution_result_captures_stdout_stderr() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0, stdout="out", stderr="err")
    assert r.stdout == "out"
    assert r.stderr == "err"


def test_shell_execution_result_dry_run_defaults_to_false() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0)
    assert r.dry_run is False


def test_subprocess_shell_dry_run_returns_zero_without_executing() -> None:
    shell = SubprocessShell()
    result = shell.run(["rm", "-rf", "/"], dry_run=True)
    assert result.return_code == 0
    assert result.dry_run is True


def test_subprocess_shell_dry_run_does_not_call_subprocess(monkeypatch) -> None:
    calls = []
    monkeypatch.setattr("subprocess.run", lambda *a, **kw: calls.append(a) or MagicMock(returncode=0))
    shell = SubprocessShell()
    shell.run(["echo", "hello"], dry_run=True)
    assert calls == []


def test_subprocess_shell_returns_ok_on_zero_exit() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="hello", stderr="")
        result = shell.run(["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_subprocess_shell_passes_env_to_subprocess() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        shell.run(["cmd"], env={"MY_VAR": "val"})
    call_kwargs = mock_run.call_args[1]
    assert "MY_VAR" in call_kwargs["env"]
    assert call_kwargs["env"]["MY_VAR"] == "val"


def test_subprocess_shell_passes_cwd_to_subprocess(tmp_path: Path) -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        shell.run(["ls"], cwd=tmp_path)
    call_kwargs = mock_run.call_args[1]
    assert call_kwargs["cwd"] == tmp_path


def test_subprocess_shell_streams_output_to_listener() -> None:
    streamed: list[tuple[str, str]] = []
    shell = SubprocessShell(output_listener=lambda stream, line: streamed.append((stream, line)))
    result = shell.run(
        [sys.executable, "-c", "import sys; print('hello'); print('warn', file=sys.stderr)"]
    )
    assert result.return_code == 0
    assert ("stdout", "hello") in streamed
    assert ("stderr", "warn") in streamed


def test_recording_shell_records_commands() -> None:
    shell = RecordingShell()
    shell.run(["cmd1", "arg1"])
    shell.run(["cmd2"])
    assert shell.commands == [["cmd1", "arg1"], ["cmd2"]]


def test_recording_shell_always_returns_zero() -> None:
    shell = RecordingShell()
    result = shell.run(["fail"])
    assert result.return_code == 0


def test_recording_shell_does_not_exec_subprocess() -> None:
    shell = RecordingShell()
    with patch("subprocess.run") as mock_run:
        shell.run(["anything"])
    mock_run.assert_not_called()


def test_recording_shell_carries_env_in_result() -> None:
    shell = RecordingShell()
    result = shell.run(["cmd"], env={"A": "B"})
    assert result.env == {"A": "B"}


def test_scripted_shell_returns_configured_stdout() -> None:
    shell = ScriptedShell(stdout_map={("echo", "hi"): "hi\n"})
    result = shell.run(["echo", "hi"])
    assert result.stdout == "hi\n"


def test_scripted_shell_returns_configured_return_code() -> None:
    shell = ScriptedShell(return_code_map={("fail",): 1})
    result = shell.run(["fail"])
    assert result.return_code == 1


def test_scripted_shell_defaults_unknown_command_to_zero() -> None:
    shell = ScriptedShell()
    result = shell.run(["unknown"])
    assert result.return_code == 0
    assert result.stdout == ""


def test_scripted_shell_records_all_commands() -> None:
    shell = ScriptedShell()
    shell.run(["a"])
    shell.run(["b"])
    assert shell.commands == [["a"], ["b"]]


def test_scripted_shell_returns_configured_stderr() -> None:
    shell = ScriptedShell(stderr_map={("cmd",): "error output"})
    result = shell.run(["cmd"])
    assert result.stderr == "error output"
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd ~/shellcraft && uv run pytest tests/test_backend.py -v
```

Expected: `ImportError` — cannot import from `shellcraft.backend`

- [ ] **Step 3: Implement backend.py**

Copy the content below into `~/shellcraft/src/shellcraft/backend.py`. This is `shell_backend.py` adapted: the `workflow_log` / `has_workflow_sink` imports are removed and `_emit_output` only calls `output_listener`.

```python
# ~/shellcraft/src/shellcraft/backend.py
from __future__ import annotations

from dataclasses import dataclass, field
import os
from pathlib import Path
import subprocess
from threading import Thread
from typing import Callable


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

        if self.output_listener is None:
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
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
cd ~/shellcraft && uv run pytest tests/test_backend.py -v
```

Expected: all tests PASS

- [ ] **Step 5: Commit**

```bash
cd ~/shellcraft
git add src/shellcraft/backend.py tests/test_backend.py
git commit -m "feat: implement shellcraft.backend"
```

---

## Task 3: Port net.py

**Files:**
- Create: `~/shellcraft/tests/test_net.py`
- Modify: `~/shellcraft/src/shellcraft/net.py`

- [ ] **Step 1: Write the failing tests**

```python
# ~/shellcraft/tests/test_net.py
from __future__ import annotations

import socket
from unittest.mock import MagicMock, patch

from shellcraft.net import is_port_free, pick_local_port


def test_is_port_free_returns_true_when_port_available() -> None:
    with patch("shellcraft.net.socket.socket") as ms:
        instance = MagicMock()
        instance.__enter__ = lambda s: instance
        instance.__exit__ = MagicMock(return_value=False)
        instance.bind = MagicMock()
        ms.return_value = instance
        result = is_port_free(9999)
    assert result is True


def test_is_port_free_returns_false_when_port_in_use() -> None:
    with patch("shellcraft.net.socket.socket") as ms:
        instance = MagicMock()
        instance.__enter__ = lambda s: instance
        instance.__exit__ = MagicMock(return_value=False)
        instance.bind = MagicMock(side_effect=OSError("address in use"))
        ms.return_value = instance
        result = is_port_free(80)
    assert result is False


def test_is_port_free_returns_false_when_port_in_use_on_ipv6() -> None:
    def _fake_socket(family, socktype):  # noqa: ARG001
        instance = MagicMock()
        instance.__enter__ = lambda s: instance
        instance.__exit__ = MagicMock(return_value=False)
        if family == socket.AF_INET:
            instance.bind = MagicMock()
        elif family == socket.AF_INET6:
            instance.bind = MagicMock(side_effect=OSError("address in use"))
        else:
            raise AssertionError(f"unexpected family: {family}")
        return instance

    with patch("shellcraft.net.socket.socket", side_effect=_fake_socket):
        result = is_port_free(8080)

    assert result is False


def test_pick_local_port_returns_preferred_when_free() -> None:
    with patch("shellcraft.net.is_port_free", return_value=True):
        port = pick_local_port(preferred=9000)
    assert port == 9000


def test_pick_local_port_skips_preferred_when_taken() -> None:
    with patch("shellcraft.net.is_port_free", return_value=False):
        port = pick_local_port(preferred=9000)
    assert port != 9000
    assert 1024 < port < 65536


def test_pick_local_port_skips_blocked_ports() -> None:
    with patch("shellcraft.net.is_port_free", return_value=True):
        port = pick_local_port(preferred=9000, blocked={9000})
    assert port != 9000


def test_pick_local_port_returns_preferred_not_in_blocked() -> None:
    with patch("shellcraft.net.is_port_free", return_value=True):
        port = pick_local_port(preferred=9001, blocked={9000})
    assert port == 9001
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd ~/shellcraft && uv run pytest tests/test_net.py -v
```

Expected: `ImportError` — cannot import from `shellcraft.net`

- [ ] **Step 3: Implement net.py**

```python
# ~/shellcraft/src/shellcraft/net.py
from __future__ import annotations

import errno
import socket


def _can_bind(family: int, address: str, port: int) -> bool | None:
    try:
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            sock.bind((address, port))
    except OSError as exc:
        if family == socket.AF_INET6 and getattr(exc, "errno", None) in {
            errno.EAFNOSUPPORT,
            errno.EPROTONOSUPPORT,
            errno.EINVAL,
        }:
            return None
        message = str(exc).lower()
        if family == socket.AF_INET6 and any(
            marker in message
            for marker in ("address family not supported", "protocol not supported", "invalid argument")
        ):
            return None
        return False
    return True


def is_port_free(port: int) -> bool:
    """Return True if port can be bound on both IPv4 and IPv6 loopback."""
    if _can_bind(socket.AF_INET, "127.0.0.1", port) is False:
        return False
    ipv6_available = _can_bind(socket.AF_INET6, "::1", port)
    if ipv6_available is False:
        return False
    return True


def pick_local_port(preferred: int, blocked: set[int] | None = None) -> int:
    """Return preferred if free and not blocked, otherwise an OS-assigned port."""
    blocked = blocked or set()
    if preferred not in blocked and is_port_free(preferred):
        return preferred
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        candidate = int(sock.getsockname()[1])
    if candidate in blocked:
        return pick_local_port(preferred=0, blocked=blocked)
    return candidate
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
cd ~/shellcraft && uv run pytest tests/test_net.py -v
```

Expected: all tests PASS

- [ ] **Step 5: Commit**

```bash
cd ~/shellcraft
git add src/shellcraft/net.py tests/test_net.py
git commit -m "feat: implement shellcraft.net"
```

---

## Task 4: Port runners.py

**Files:**
- Create: `~/shellcraft/tests/test_runners.py`
- Modify: `~/shellcraft/src/shellcraft/runners.py`

- [ ] **Step 1: Write the failing tests**

```python
# ~/shellcraft/tests/test_runners.py
from __future__ import annotations

import json
from pathlib import Path

from shellcraft.backend import RecordingShell
from shellcraft.runners import (
    CommandRunner,
    ContainerRuntimeOps,
    KubectlOps,
    PlannedCommand,
    read_json_field,
    write_json_file,
    wrap_payload,
)


def test_command_runner_delegates_to_shell() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    result = runner.run(["echo", "hi"], dry_run=True)
    assert result.command == ["echo", "hi"]


def test_command_runner_records_commands() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    runner.run(["echo", "a"], dry_run=True)
    runner.run(["echo", "b"], dry_run=True)
    assert shell.commands == [["echo", "a"], ["echo", "b"]]


def test_planned_command_run_delegates_to_runner() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    cmd = PlannedCommand(command=["ls"], cwd=Path("/tmp"))
    cmd.run(runner, dry_run=True)
    assert shell.commands == [["ls"]]


def test_container_runtime_ops_build_uses_tag() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="docker")
    ops.build(tag="my-image:test", context=Path("/repo/app"), dry_run=True)
    assert any("my-image:test" in " ".join(cmd) for cmd in shell.commands)


def test_container_runtime_ops_build_passes_build_args() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="docker")
    ops.build(tag="img", context=Path("/ctx"), build_args={"FOO": "bar"}, dry_run=True)
    rendered = " ".join(shell.commands[0])
    assert "--build-arg" in rendered
    assert "FOO=bar" in rendered


def test_container_runtime_ops_remove_includes_name() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="docker")
    ops.remove("my-container", dry_run=True)
    assert any("my-container" in " ".join(cmd) for cmd in shell.commands)


def test_container_runtime_ops_uses_configured_runtime() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = ContainerRuntimeOps(runner=runner, runtime="podman")
    ops.build(tag="img", context=Path("/ctx"), dry_run=True)
    assert shell.commands[0][0] == "podman"


def test_kubectl_ops_apply_includes_manifest() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = KubectlOps(runner=runner)
    ops.apply(Path("/tmp/manifest.yaml"), dry_run=True)
    assert any("/tmp/manifest.yaml" in " ".join(cmd) for cmd in shell.commands)


def test_kubectl_ops_respects_kubeconfig() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = KubectlOps(runner=runner, kubeconfig="/home/user/.kube/config")
    ops.apply(Path("/tmp/manifest.yaml"), dry_run=True)
    rendered = [" ".join(cmd) for cmd in shell.commands]
    assert any("/home/user/.kube/config" in r for r in rendered)


def test_kubectl_ops_delete_adds_ignore_not_found() -> None:
    shell = RecordingShell()
    runner = CommandRunner(shell=shell, repo_root=Path("/repo"))
    ops = KubectlOps(runner=runner)
    ops.delete("deployment", "my-app", dry_run=True)
    rendered = " ".join(shell.commands[0])
    assert "--ignore-not-found" in rendered


def test_read_json_field_extracts_nested_value(tmp_path: Path) -> None:
    f = tmp_path / "data.json"
    f.write_text('{"a": {"b": "hello"}}', encoding="utf-8")
    assert read_json_field(f, "a.b") == "hello"


def test_write_json_file_roundtrips(tmp_path: Path) -> None:
    f = tmp_path / "out.json"
    write_json_file(f, {"name": "test", "value": 42})
    assert read_json_field(f, "name") == "test"
    assert read_json_field(f, "value") == 42


def test_wrap_payload_wraps_in_input_key(tmp_path: Path) -> None:
    payload = tmp_path / "payload.json"
    payload.write_text('{"x": 1}', encoding="utf-8")
    out = tmp_path / "wrapped.json"
    wrap_payload(payload, out)
    data = json.loads(out.read_text())
    assert data == {"input": {"x": 1}}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd ~/shellcraft && uv run pytest tests/test_runners.py -v
```

Expected: `ImportError` — cannot import from `shellcraft.runners`

- [ ] **Step 3: Implement runners.py**

```python
# ~/shellcraft/src/shellcraft/runners.py
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from shellcraft.backend import ShellBackend, ShellExecutionResult, SubprocessShell


@dataclass
class CommandRunner:
    """Couples a ShellBackend with a working-directory root."""

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


@dataclass(frozen=True)
class PlannedCommand:
    command: list[str]
    cwd: Path
    env: dict[str, str] = field(default_factory=dict)

    def run(self, runner: CommandRunner, *, dry_run: bool = False) -> ShellExecutionResult:
        return runner.run(self.command, cwd=self.cwd, env=self.env, dry_run=dry_run)


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

    def remove(self, *names: str, force: bool = True, dry_run: bool = False) -> ShellExecutionResult:
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
    """kubectl operations with optional kubeconfig and namespace binding."""

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
        return self.runner.run([*self._base(), "apply", "-f", str(manifest)], dry_run=dry_run)

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
        self, resource: str, name: str, *, dry_run: bool = False
    ) -> ShellExecutionResult:
        return self.runner.run(
            [*self._base(), "rollout", "restart", f"{resource}/{name}"],
            dry_run=dry_run,
        )

    def exec(self, pod: str, command: str, *, dry_run: bool = False) -> ShellExecutionResult:
        return self.runner.run(
            [*self._base(), "exec", pod, "--", "bash", "-lc", command],
            dry_run=dry_run,
        )


def read_json_field(path: Path, field: str) -> Any:
    """Read a dot-separated field path from a JSON file."""
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
    Path(path).write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")


def wrap_payload(payload_path: Path, destination: Path) -> None:
    """Wrap a raw payload file in {"input": ...} for nanofaas invocation."""
    with payload_path.open(encoding="utf-8") as handle:
        payload = json.load(handle)
    destination.write_text(json.dumps({"input": payload}, separators=(",", ":")), encoding="utf-8")
```

- [ ] **Step 4: Run tests and verify they pass**

```bash
cd ~/shellcraft && uv run pytest tests/test_runners.py -v
```

Expected: all tests PASS

- [ ] **Step 5: Commit**

```bash
cd ~/shellcraft
git add src/shellcraft/runners.py tests/test_runners.py
git commit -m "feat: implement shellcraft.runners"
```

---

## Task 5: Wire __init__.py and full test run

**Files:**
- Modify: `~/shellcraft/src/shellcraft/__init__.py`

- [ ] **Step 1: Write __init__.py**

```python
# ~/shellcraft/src/shellcraft/__init__.py
from shellcraft.backend import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from shellcraft.net import is_port_free, pick_local_port
from shellcraft.runners import (
    CommandRunner,
    ContainerRuntimeOps,
    KubectlOps,
    PlannedCommand,
    read_json_field,
    write_json_file,
    wrap_payload,
)

__all__ = [
    "CommandRunner",
    "ContainerRuntimeOps",
    "KubectlOps",
    "OutputListener",
    "PlannedCommand",
    "RecordingShell",
    "ScriptedShell",
    "ShellBackend",
    "ShellExecutionResult",
    "SubprocessShell",
    "is_port_free",
    "pick_local_port",
    "read_json_field",
    "wrap_payload",
    "write_json_file",
]
```

- [ ] **Step 2: Run the full test suite**

```bash
cd ~/shellcraft && uv run pytest -v
```

Expected: all tests PASS

- [ ] **Step 3: Commit and tag**

```bash
cd ~/shellcraft
git add src/shellcraft/__init__.py
git commit -m "feat: wire public API in __init__.py"
git tag v0.1.0
```

---

## Task 6: Migrate controlplane-tool

**Files:**
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/src/controlplane_tool/shell_backend.py`
- Modify: `tools/controlplane/src/controlplane_tool/runtime_primitives.py`
- Modify: `tools/controlplane/src/controlplane_tool/net_utils.py`

This task replaces the three original files with thin shims. All 22 existing import sites in `controlplane_tool` continue to work unchanged. The shim for `shell_backend.py` preserves the TUI workflow-logging behavior by subclassing `SubprocessShell`.

- [ ] **Step 1: Add shellcraft as a dependency**

In `tools/controlplane/pyproject.toml`, add to the `dependencies` list:

```toml
"shellcraft @ file:///absolute/path/to/shellcraft",
```

Replace `/absolute/path/to/shellcraft` with the actual path where you created the repo (e.g., `/Users/yourname/shellcraft`).

- [ ] **Step 2: Install the updated dependencies**

```bash
cd tools/controlplane
uv pip install -e ".[dev]"
```

Expected: shellcraft is installed alongside existing deps

- [ ] **Step 3: Replace shell_backend.py with the workflow-aware shim**

Overwrite `tools/controlplane/src/controlplane_tool/shell_backend.py` with:

```python
# controlplane_tool/shell_backend.py
# Shim: re-exports from shellcraft + workflow-aware SubprocessShell for TUI integration.
from __future__ import annotations

from pathlib import Path

from shellcraft.backend import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell as _ShellcraftSubprocessShell,
)

from controlplane_tool.console import has_workflow_sink, workflow_log

__all__ = [
    "OutputListener",
    "RecordingShell",
    "ScriptedShell",
    "ShellBackend",
    "ShellExecutionResult",
    "SubprocessShell",
]


class SubprocessShell(_ShellcraftSubprocessShell):
    """SubprocessShell with TUI workflow-log integration.

    When a workflow sink is active (TUI log panel) and no explicit
    output_listener is set, automatically wires workflow_log as the listener
    so subprocess output appears in the TUI — preserving the original behavior.
    """

    def run(
        self,
        command: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if not dry_run and self.output_listener is None and has_workflow_sink():
            shell = _ShellcraftSubprocessShell(
                output_listener=lambda s, l: workflow_log(l, stream=s)
            )
            return shell.run(command, cwd=cwd, env=env, dry_run=dry_run)
        return super().run(command, cwd=cwd, env=env, dry_run=dry_run)
```

- [ ] **Step 4: Replace runtime_primitives.py with a shim**

Overwrite `tools/controlplane/src/controlplane_tool/runtime_primitives.py` with:

```python
# controlplane_tool/runtime_primitives.py
# Shim: re-exports from shellcraft.runners.
from shellcraft.runners import (  # noqa: F401
    CommandRunner,
    ContainerRuntimeOps,
    KubectlOps,
    PlannedCommand,
    read_json_field,
    wrap_payload,
    write_json_file,
)
```

- [ ] **Step 5: Replace net_utils.py with a shim**

Overwrite `tools/controlplane/src/controlplane_tool/net_utils.py` with:

```python
# controlplane_tool/net_utils.py
# Shim: re-exports from shellcraft.net.
from shellcraft.net import is_port_free, pick_local_port  # noqa: F401
```

- [ ] **Step 6: Run the full controlplane-tool test suite**

```bash
cd tools/controlplane && uv run pytest -v
```

Expected: all tests PASS (same results as before the migration)

- [ ] **Step 7: Commit**

```bash
cd tools/controlplane
git add pyproject.toml \
    src/controlplane_tool/shell_backend.py \
    src/controlplane_tool/runtime_primitives.py \
    src/controlplane_tool/net_utils.py
git commit -m "feat(deps): migrate shell_backend, runtime_primitives, net_utils to shellcraft"
```
