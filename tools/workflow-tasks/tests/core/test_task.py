from __future__ import annotations
from typing import Any
from workflow_tasks.core.task import Task


def test_task_protocol_is_satisfied_by_dataclass() -> None:
    from dataclasses import dataclass

    @dataclass
    class MyTask:
        task_id: str = "my.task"
        title: str = "My Task"

        def run(self) -> None:
            pass

    task = MyTask()
    assert isinstance(task, Task)


def test_task_protocol_requires_task_id() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoId:
        title: str = "x"

        def run(self) -> None:
            pass

    assert not isinstance(NoId(), Task)


def test_task_protocol_requires_title() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoTitle:
        task_id: str = "x"

        def run(self) -> None:
            pass

    assert not isinstance(NoTitle(), Task)


def test_task_protocol_requires_run() -> None:
    from dataclasses import dataclass

    @dataclass
    class NoRun:
        task_id: str = "x"
        title: str = "x"

    assert not isinstance(NoRun(), Task)


def test_task_run_can_return_value() -> None:
    from dataclasses import dataclass

    @dataclass
    class ValueTask:
        task_id: str = "value.task"
        title: str = "Value Task"

        def run(self) -> int:
            return 42

    task = ValueTask()
    result = task.run()
    assert result == 42
