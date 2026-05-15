from __future__ import annotations

from types import MappingProxyType
from typing import get_args

from workflow_tasks.tasks.models import (
    CommandTaskSpec,
    ExecutionTarget,
    TaskResult,
    TaskStatus,
)


def test_task_type_aliases_cover_expected_values() -> None:
    assert set(get_args(ExecutionTarget)) == {"host", "vm"}
    assert set(get_args(TaskStatus)) == {"pending", "running", "passed", "failed", "skipped"}


def test_command_task_spec_defaults_to_host_target_and_empty_env() -> None:
    task = CommandTaskSpec(task_id="build.compile", summary="Compile project", argv=("./gradlew", "build"))
    assert task.target == "host"
    assert task.env == {}
    assert task.cwd is None
    assert task.remote_dir is None
    assert task.expected_exit_codes == frozenset({0})


def test_vm_command_task_can_declare_remote_dir() -> None:
    task = CommandTaskSpec(
        task_id="images.build",
        summary="Build image",
        target="vm",
        argv=("docker", "build", "-t", "img", "."),
        remote_dir="/home/ubuntu/nanofaas",
    )
    assert task.target == "vm"
    assert task.remote_dir == "/home/ubuntu/nanofaas"


def test_command_task_spec_defensively_copies_env() -> None:
    env = {"A": "B"}
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "x"), env=env)
    env["A"] = "changed"
    assert dict(task.env) == {"A": "B"}


def test_command_task_spec_env_is_immutable() -> None:
    task = CommandTaskSpec(task_id="x", summary="X", argv=("echo", "x"), env={"A": "B"})
    assert isinstance(task.env, MappingProxyType)


def test_task_result_ok_from_expected_exit_codes() -> None:
    success = TaskResult(task_id="x", status="passed", return_code=17, expected_exit_codes=frozenset({17}))
    failure = TaskResult(task_id="x", status="failed", return_code=17, expected_exit_codes=frozenset({0}))
    assert success.ok is True
    assert failure.ok is False
