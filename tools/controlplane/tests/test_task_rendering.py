from pathlib import Path

import pytest

from controlplane_tool.tasks.models import CommandTaskSpec
from controlplane_tool.tasks.rendering import render_shell_command, render_task_command


def test_render_shell_command_quotes_arguments_and_env() -> None:
    rendered = render_shell_command(
        argv=("docker", "build", "-t", "local image", "."),
        env={"A": "one two"},
    )

    assert rendered == "A='one two' docker build -t 'local image' ."


def test_render_shell_command_sorts_env_keys() -> None:
    rendered = render_shell_command(
        argv=("echo", "ok"),
        env={"B": "2", "A": "1"},
    )

    assert rendered == "A=1 B=2 echo ok"


def test_render_shell_command_rejects_invalid_env_key() -> None:
    with pytest.raises(ValueError, match="Invalid environment variable name"):
        render_shell_command(
            argv=("echo", "ok"),
            env={"A; echo PWNED #": "x"},
        )


def test_render_shell_command_rejects_empty_argv() -> None:
    with pytest.raises(ValueError, match="Command argv must not be empty"):
        render_shell_command(argv=())


def test_render_vm_task_prefixes_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        target="vm",
        argv=("docker", "build", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )

    assert render_task_command(task) == "cd /home/ubuntu/nanofaas && docker build ."


def test_render_host_task_uses_cwd_as_metadata_not_shell_prefix() -> None:
    task = CommandTaskSpec(
        task_id="x",
        summary="X",
        target="host",
        argv=("pytest", "-q"),
        cwd=Path("/repo"),
    )

    assert render_task_command(task) == "pytest -q"
