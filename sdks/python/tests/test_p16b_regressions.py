"""Completion ordering and independent saturation-byte observations."""

import asyncio
from concurrent.futures import Future, ThreadPoolExecutor
import json
import importlib
import threading

import pytest

from nanofaas.runtime.app import _await_concurrent_future
import nanofaas.runtime.app as runtime_app
from sdks.python.tests.runtime_corpus_adapter import (
    ScenarioHarness, execute_corpus_against_runtime,
)
from sdks.python.tests.test_saturation_wire_corpus import CORPUS


@pytest.mark.parametrize("when", ["immediate", "during-registration", "after-registration"])
@pytest.mark.parametrize("outcome", ["result", "exception", "cancel"])
def test_bridge_propagates_each_terminal_state_across_registration(when, outcome):
    registered = threading.Event()
    complete = threading.Event()

    class RegistrationFuture(Future):
        def add_done_callback(self, callback):
            if when == "during-registration":
                complete.set()
                assert finished.wait(1)
            super().add_done_callback(callback)
            registered.set()

    source = RegistrationFuture()
    finished = threading.Event()
    error = ValueError("physical failure")

    def finish():
        if outcome == "cancel":
            source.cancel()
        elif outcome == "exception":
            source.set_exception(error)
        else:
            source.set_result(42)
        finished.set()

    def worker():
        assert complete.wait(1)
        finish()

    async def exercise():
        errors = []
        asyncio.get_running_loop().set_exception_handler(lambda _, info: errors.append(info))
        if when == "immediate":
            finish()
        thread = None if when == "immediate" else threading.Thread(target=worker)
        if thread:
            thread.start()
        task = asyncio.create_task(_await_concurrent_future(source))
        await asyncio.sleep(0)
        assert registered.is_set()
        complete.set()
        try:
            if outcome == "cancel":
                with pytest.raises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 1)
            elif outcome == "exception":
                with pytest.raises(ValueError) as caught:
                    await asyncio.wait_for(task, 1)
                assert caught.value is error
            else:
                assert await asyncio.wait_for(task, 1) == 42
            await asyncio.sleep(0)
            assert not errors
        finally:
            if thread:
                thread.join(1)
                assert not thread.is_alive()

    asyncio.run(exercise())


@pytest.mark.parametrize("finish_first", [False, True])
def test_bridge_wait_cancellation_never_cancels_physical_work(finish_first):
    async def exercise():
        source = Future()
        assert source.set_running_or_notify_cancel()
        task = asyncio.create_task(_await_concurrent_future(source))
        await asyncio.sleep(0)
        if finish_first:
            source.set_result(42)  # Delivery queued, awaiter cancellation wins.
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not source.cancelled()
        if not finish_first:
            source.set_result(42)
        await asyncio.sleep(0)
        assert source.result() == 42

    asyncio.run(exercise())


def test_bridge_repeated_immediate_executor_completions():
    async def exercise():
        with ThreadPoolExecutor(max_workers=1) as executor:
            for _ in range(1000):
                source = executor.submit(lambda: 42)
                assert await asyncio.wait_for(_await_concurrent_future(source), 1) == 42

    asyncio.run(exercise())


def test_bridge_physical_completion_after_awaiter_loop_shutdown(caplog):
    source = Future()
    assert source.set_running_or_notify_cancel()
    loop = asyncio.new_event_loop()

    async def cancel_wait():
        task = asyncio.create_task(_await_concurrent_future(source))
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    try:
        loop.run_until_complete(cancel_wait())
    finally:
        loop.close()
    error = ValueError("late physical failure")
    worker = threading.Thread(target=source.set_exception, args=(error,))
    worker.start()
    worker.join(1)
    assert not worker.is_alive()
    assert not source.cancelled()
    assert source.exception() is error
    assert not caplog.records


@pytest.mark.parametrize("field,value", [
    ("inputBytes", 999999),
    ("serializedCallbackBytes", 511),
    ("outputBytes", 1),
])
def test_saturated_byte_declarations_cannot_construct_the_workload(tmp_path, field, value):
    corpus = json.loads(CORPUS.read_text())
    scenario = next(s for s in corpus["scenarios"] if s["id"] == "health-under-saturation")
    scenario["initialCounters"][field] = value
    corpus["scenarios"] = [scenario]
    path = tmp_path / "mutated.json"
    path.write_text(json.dumps(corpus))
    with pytest.raises(AssertionError) as caught:
        execute_corpus_against_runtime(path)
    mismatch = caught.value.args[0]
    assert mismatch["phase"] == "initial"
    assert mismatch["actual"][field] != value


def test_adapter_counts_live_asgi_output_instead_of_constant_zero(monkeypatch):
    corpus = json.loads(CORPUS.read_text())
    scenario = next(s for s in corpus["scenarios"] if s["id"] == "health-under-saturation")
    runtime = importlib.reload(runtime_app)
    harness = ScenarioHarness(corpus, scenario, runtime, asyncio.sleep)
    snapshots = []
    capture = harness._capture_resources

    def observe():
        snapshot = capture()
        snapshots.append(snapshot)
        return snapshot

    monkeypatch.setattr(harness, "_capture_resources", observe)

    async def exercise():
        response = await harness._exchange("GET", "/health")
        assert response.status_code == 200
        assert max(s["outputBytes"] for s in snapshots) == len(response.content) > 0
        assert capture()["outputBytes"] == 0
        assert (await runtime._runtime_work.shutdown(1)).drained

    try:
        asyncio.run(exercise())
    finally:
        importlib.reload(runtime_app)
