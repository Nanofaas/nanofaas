from __future__ import annotations

from types import SimpleNamespace

import pytest

from controlplane_tool.release.remote_retry import (
    CONNECTION_DEAD,
    retry_on_connection_death,
)


def _op(results: list[object]):
    state = {"i": 0}

    def run() -> object:
        outcome = results[state["i"]]
        state["i"] += 1
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    return run, state


def test_retries_a_dropped_connection_then_succeeds() -> None:
    run, state = _op([SimpleNamespace(return_code=CONNECTION_DEAD), SimpleNamespace(return_code=0)])
    result = retry_on_connection_death(run, describe="probe", sleep=lambda _s: None)
    assert result.return_code == 0
    assert state["i"] == 2


def test_does_not_retry_a_real_nonzero_exit() -> None:
    run, state = _op([SimpleNamespace(return_code=7), SimpleNamespace(return_code=0)])
    result = retry_on_connection_death(run, describe="probe", sleep=lambda _s: None)
    assert result.return_code == 7  # returned as-is; caller decides it's a failure
    assert state["i"] == 1


def test_retries_a_connect_exception_then_succeeds() -> None:
    run, state = _op([ConnectionResetError("reset"), SimpleNamespace(return_code=0)])
    result = retry_on_connection_death(run, describe="probe", sleep=lambda _s: None)
    assert result.return_code == 0
    assert state["i"] == 2


def test_gives_up_after_exhausting_attempts() -> None:
    run, _ = _op([SimpleNamespace(return_code=CONNECTION_DEAD)] * 4)
    result = retry_on_connection_death(run, describe="probe", sleep=lambda _s: None)
    assert result.return_code == CONNECTION_DEAD  # last attempt returned to the caller


def test_reraises_a_persistent_connect_exception() -> None:
    run, _ = _op([ConnectionResetError("reset")] * 4)
    with pytest.raises(ConnectionResetError):
        retry_on_connection_death(run, describe="probe", sleep=lambda _s: None)
