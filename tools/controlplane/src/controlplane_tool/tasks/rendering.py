from __future__ import annotations

import shlex
from collections.abc import Mapping
import re

from controlplane_tool.tasks.models import CommandTaskSpec

_ENV_NAME_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*\Z")


def _render_env_assignment(name: str, value: str) -> str:
    if not _ENV_NAME_RE.fullmatch(name):
        raise ValueError(f"Invalid environment variable name: {name!r}")
    return f"{name}={shlex.quote(value)}"


def render_shell_command(
    argv: tuple[str, ...],
    *,
    env: Mapping[str, str] | None = None,
) -> str:
    if not argv:
        raise ValueError("Command argv must not be empty")
    prefixes = [
        _render_env_assignment(name, value)
        for name, value in sorted((env or {}).items())
    ]
    command = shlex.join(argv)
    return " ".join([*prefixes, command]) if prefixes else command


def render_task_command(task: CommandTaskSpec) -> str:
    rendered = render_shell_command(task.argv, env=task.env)
    if task.target == "vm" and task.remote_dir:
        return f"cd {shlex.quote(task.remote_dir)} && {rendered}"
    return rendered
