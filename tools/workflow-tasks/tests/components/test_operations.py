from __future__ import annotations

from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation


def test_scenario_operation_holds_id_and_summary() -> None:
    op = ScenarioOperation(operation_id="op1", summary="do thing")
    assert op.operation_id == "op1"
    assert op.summary == "do thing"


def test_remote_command_operation_defaults_to_host_target_and_empty_env() -> None:
    op = RemoteCommandOperation(operation_id="op2", summary="run", argv=("echo", "hi"))
    assert op.execution_target == "host"
    assert dict(op.env) == {}
    assert op.argv == ("echo", "hi")


def test_remote_command_operation_is_frozen() -> None:
    op = RemoteCommandOperation(operation_id="op3", summary="run", argv=("ls",))
    try:
        op.summary = "x"  # type: ignore[misc]
    except AttributeError:
        return
    raise AssertionError("expected frozen dataclass")
