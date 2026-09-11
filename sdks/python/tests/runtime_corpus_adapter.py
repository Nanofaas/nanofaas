"""Executable Python-runtime adapter for the shared saturation wire corpus."""

from __future__ import annotations

import asyncio
import contextvars
import importlib
import json
import logging
import os
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import httpx

SRC = Path(__file__).resolve().parents[1] / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

import nanofaas.runtime.app as runtime_app
from nanofaas.sdk import decorator


@dataclass(frozen=True)
class ExecutionSummary:
    scenarios: int
    barriers: int
    callbacks: int
    callback_attempts: int
    stops: int
    restarts: int
    observations: int
    nonzero_counters: tuple[str, ...]


@dataclass(frozen=True)
class ScenarioExecution:
    barriers: int
    callbacks: int
    callback_attempts: int
    stops: int
    observations: int
    nonzero_counters: frozenset[str]


class JsonLogCapture(logging.Handler):
    def __init__(self, formatter):
        super().__init__()
        self.setFormatter(formatter)
        self.entries = []
        self._lock = threading.Lock()

    def emit(self, record):
        entry = json.loads(self.format(record))
        with self._lock:
            self.entries.append(entry)


class SizedJsonRequest:
    def __init__(self, request_id: str, size: int, observe_bytes=None):
        prefix = json.dumps(
            {"input": {"requestId": request_id}}, separators=(",", ":")
        ).encode()
        if len(prefix) > size:
            raise AssertionError(f"request fixture exceeds declared size {size}")
        self.body = prefix + (b" " * (size - len(prefix)))
        self.headers = {"content-length": str(size)}
        self.observe_bytes = observe_bytes

    async def stream(self):
        if self.observe_bytes is not None:
            self.observe_bytes(len(self.body))
        yield self.body


async def _wait_until(predicate, timeout: float, original_sleep) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await original_sleep(0)


class ScenarioHarness:
    def __init__(self, corpus: dict, scenario: dict, runtime, original_sleep):
        self.corpus = corpus
        self.scenario = scenario
        self.runtime = runtime
        self.original_sleep = original_sleep
        self.deadline = scenario["deadlineMs"] / 1000.0
        self.requests = {request["id"]: request for request in scenario["requests"]}
        self.handler_specs = {
            handler["requestId"]: handler for handler in scenario["backend"]["handlers"]
        }
        self.callback_specs = {
            callback["requestId"]: callback for callback in scenario["backend"]["callbacks"]
        }
        self.expected_callbacks = {
            callback["requestId"]: callback for callback in scenario["expected"]["callbacks"]
        }
        self.barriers = {
            barrier["id"]: asyncio.Event() for barrier in scenario["harness"]["barriers"]
        }
        for barrier in scenario["harness"]["barriers"]:
            if barrier["initialState"] == "open":
                self.barriers[barrier["id"]].set()
        self.invocations: dict[str, asyncio.Task] = {}
        self.responses = {}
        self.handler_states = {
            request_id: {
                "started": False,
                "cancelRequested": False,
                "terminal": "not-started",
            }
            for request_id in self.handler_specs
        }
        self.callback_calls: list[dict] = []
        self.callback_lock = threading.Lock()
        self.resource_lock = threading.Lock()
        self.resource_peaks = {
            "activeHandlers": 0,
            "inputBytes": 0,
            "outputBytes": 0,
            "pendingCallbacks": 0,
            "pendingCallbackBytes": 0,
            "serializedCallbackBytes": 0,
        }
        self.observations: set[str] = set()
        self.handler_invocations: list[str] = []
        self.sent_requests: list[str] = []
        self.redispatch_authorized: set[str] = set()
        self.runtime_started = False
        self.initial_counters_verified = False
        self.verify_initial_at_handler_admission = False
        self.real_start_handler = self.runtime._runtime_work.start_handler
        self.failure_metric_before = self._failure_metric_value()
        self.log_capture = JsonLogCapture(self.runtime.sdk_logging.JsonFormatter())
        self.callback_filler = None
        self.callback_filler_task = None
        self.callback_filler_started = asyncio.Event()
        self.handler_filler = None
        self.handler_filler_release = asyncio.Event()
        self.received_bytes = contextvars.ContextVar("corpus_received_bytes", default=0)
        self.handler_inputs = {}
        self.output_messages = {}
        self.physical_payloads = {}
        self.callback_filler_release = threading.Event()
        self.stop_task = None
        self.stop_report = None
        self.stop_count = 0
        self.restart_count = 0
        self._register_handler()

    def _failure_metric_value(self) -> float:
        return self.runtime.RUNTIME_CALLBACK_DELIVERY_FAILURES_TOTAL.labels(
            function=self.runtime.FUNCTION_NAME
        )._value.get()

    def _capture_resources(self) -> dict[str, int]:
        snapshot = self.runtime._runtime_work.snapshot()
        manager_values = {
            "activeHandlers": snapshot.active_handlers,
            "pendingCallbacks": snapshot.pending_callbacks,
            "pendingCallbackBytes": snapshot.pending_callback_bytes,
        }
        metric_values = {
            "activeHandlers": self.runtime.RUNTIME_ACTIVE_HANDLERS.labels(
                function=self.runtime.FUNCTION_NAME
            )._value.get(),
            "pendingCallbacks": self.runtime.RUNTIME_PENDING_CALLBACKS.labels(
                function=self.runtime.FUNCTION_NAME
            )._value.get(),
            "pendingCallbackBytes": self.runtime.RUNTIME_PENDING_CALLBACK_BYTES.labels(
                function=self.runtime.FUNCTION_NAME
            )._value.get(),
        }
        assert metric_values == manager_values
        with self.resource_lock:
            values = {
                **manager_values,
                "inputBytes": sum(
                    size for work, size in self.handler_inputs.items() if not work.done()
                ),
                "outputBytes": sum(len(body) for body in self.output_messages.values()),
                "serializedCallbackBytes": sum(
                    len(body) for body in self.physical_payloads.values()
                ),
            }
            for name, value in values.items():
                self.resource_peaks[name] = max(self.resource_peaks[name], value)
        return values

    def _observe_runtime_bytes(self, name: str, value: int) -> None:
        """Record bytes observed at an actual runtime input/output/HTTP boundary."""
        with self.resource_lock:
            self.resource_peaks[name] = max(self.resource_peaks[name], value)

    def _assert_initial_counters(self) -> None:
        actual = self._capture_resources()
        assert actual == self.scenario["initialCounters"], {
            "scenario": self.scenario["id"],
            "phase": "initial",
            "actual": actual,
            "expected": self.scenario["initialCounters"],
        }
        self.initial_counters_verified = True

    def _observed_start_handler(self, handler, input_data):
        if self.verify_initial_at_handler_admission:
            self._assert_initial_counters()
            self.verify_initial_at_handler_admission = False
        return self.real_start_handler(handler, input_data)

    def _register_handler(self) -> None:
        harness = self

        @decorator.nanofaas_function
        async def corpus_handler(input_data):
            request_id = input_data["requestId"]
            with harness.resource_lock:
                harness.handler_inputs[asyncio.current_task()] = harness.received_bytes.get()
            if request_id == "capacity-fixture":
                harness.callback_filler_started.set()
                await harness.handler_filler_release.wait()
                return {"result": "ok"}
            spec = harness.handler_specs[request_id]
            state = harness.handler_states[request_id]
            state["started"] = True
            harness.handler_invocations.append(request_id)
            resources = harness._capture_resources()
            assert resources["activeHandlers"] > 0
            harness.observations.add("handler-start")
            behavior = spec["behavior"]
            if spec["barrier"] is not None:
                harness.barriers[spec["barrier"]].set()
            if behavior == "fail":
                state["terminal"] = "failed"
                raise RuntimeError("backend detail must not cross the runtime boundary")
            if behavior == "block-until-cancelled":
                try:
                    await asyncio.Future()
                except asyncio.CancelledError:
                    state["cancelRequested"] = True
                    state["terminal"] = (
                        "timed-out"
                        if harness.scenario["kind"] == "handler-timeout"
                        else "cancelled"
                    )
                    harness.observations.add("handler-cancel")
                    raise
            if spec["outputRelationToLimit"] == "above-limit":
                state["terminal"] = "output-rejected"
                return "x" * spec["outputBytes"]
            state["terminal"] = "succeeded"
            return {"result": "ok"}

    def callback_post(self, url, *, data, headers, timeout):
        if url.endswith("/capacity-fixture:complete"):
            with self.resource_lock:
                self.physical_payloads[url] = data
            try:
                self._capture_resources()
                assert self.callback_filler_release.wait(self.deadline)
                return SimpleNamespace(status_code=204)
            finally:
                with self.resource_lock:
                    self.physical_payloads.pop(url)
        dispatch_attempt = headers.get("X-Dispatch-Attempt")
        request_id = next(
            request_id
            for request_id, request in self.requests.items()
            if str(request["metadata"]["dispatchAttempt"]) == dispatch_attempt
        )
        behavior = self.callback_specs[request_id]["behavior"]
        self._observe_runtime_bytes("serializedCallbackBytes", len(data))
        resources = self._capture_resources()
        assert resources["pendingCallbacks"] > 0
        assert resources["pendingCallbackBytes"] >= len(data)
        call = {
            "method": "POST",
            "url": url,
            "headers": {key.lower(): value for key, value in headers.items()},
            "payload": json.loads(data),
            "timeout": timeout,
            "status": 204 if behavior == "succeed" else 503,
        }
        with self.callback_lock:
            self.callback_calls.append(call)
        self.observations.add("callback-attempt")
        if call["status"] < 400:
            self.observations.add("callback-delivery")
        return SimpleNamespace(status_code=call["status"])

    def _calls_for(self, request_id: str) -> list[dict]:
        attempt = str(self.requests[request_id]["metadata"]["dispatchAttempt"])
        with self.callback_lock:
            return [
                call
                for call in self.callback_calls
                if call["headers"].get("x-dispatch-attempt") == attempt
            ]

    async def _exchange(self, method, path, **kwargs):
        async def observed_app(scope, receive, send):
            token = self.received_bytes.set(0)

            async def observed_receive():
                message = await receive()
                size = len(message.get("body", b""))
                self.received_bytes.set(self.received_bytes.get() + size)
                self._observe_runtime_bytes("inputBytes", self.received_bytes.get())
                return message

            async def observed_send(message):
                body = message.get("body", b"")
                with self.resource_lock:
                    self.output_messages[id(message)] = body
                self._capture_resources()
                try:
                    await send(message)
                finally:
                    with self.resource_lock:
                        self.output_messages.pop(id(message))

            try:
                await self.runtime.app(scope, observed_receive, observed_send)
            finally:
                self.received_bytes.reset(token)

        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=observed_app), base_url="http://runtime"
        ) as client:
            return await client.request(method, path, **kwargs)

    async def _invoke(self, request_id: str):
        request = self.requests[request_id]
        metadata = request["metadata"]
        body = SizedJsonRequest(
            request_id,
            request["payload"]["inputBytes"],
            lambda size: self._observe_runtime_bytes("inputBytes", size),
        )
        return await self._exchange(
            request["method"], request["path"], content=body.body,
            headers={
                "X-Execution-Id": metadata["executionId"],
                "X-Trace-Id": metadata["traceId"],
                "X-Callback-Url": metadata["callbackUrl"],
                "X-Dispatch-Attempt": str(metadata["dispatchAttempt"]),
            },
        )

    async def _await_callback(self, request_id: str) -> None:
        expected_attempts = self.expected_callbacks[request_id]["attempts"]
        await _wait_until(
            lambda: len(self._calls_for(request_id)) == expected_attempts
            and self.runtime._runtime_work.snapshot().pending_callbacks == 0,
            self.deadline,
            self.original_sleep,
        )

    async def _fill_declared_initial_state(self) -> None:
        # Fixed workload policy: a request at 1/8 of the runtime input cap,
        # and a callback at 1/2 of its byte cap. Declarations are assertions
        # only; changing them must never change what the runtime executes.
        kind = self.scenario["kind"]
        if kind in ("health-under-saturation", "handler-saturated"):
            body = SizedJsonRequest("capacity-fixture", self.runtime.MAX_INPUT_BYTES // 8)
            self.handler_filler = asyncio.create_task(self._exchange(
                "POST", "/invoke", content=body.body,
                headers={"X-Execution-Id": "capacity-fixture"},
            ))
            await asyncio.wait_for(self.callback_filler_started.wait(), self.deadline)

        invocation_owns_declared_callback = kind == "handler-saturated"
        if not invocation_owns_declared_callback:
            target_size = self.runtime.MAX_CALLBACK_BYTES // 2
            payload = {"success": True, "output": "", "error": None}
            overhead = len(self.runtime._encode_json_bounded(payload, target_size))
            payload["output"] = "c" * (target_size - overhead)
            self.callback_filler = self.runtime._runtime_work.reserve_callback(
                self.runtime.MAX_CALLBACK_BYTES
            )
            self.callback_filler_task = self.runtime._runtime_work.start_reserved_callback_task(
                self.callback_filler, self.runtime._send_callback_with_reservation,
                self.callback_filler, "http://callback.invalid", "capacity-fixture", None, payload,
            )
            await _wait_until(lambda: bool(self.physical_payloads), self.deadline, self.original_sleep)

        if invocation_owns_declared_callback:
            self.verify_initial_at_handler_admission = True
        else:
            self._assert_initial_counters()

    async def _drain_fixture_state(self) -> None:
        self.callback_filler_release.set()
        if self.callback_filler_task is not None:
            await asyncio.wait_for(self.callback_filler_task, timeout=self.deadline)
        elif self.callback_filler is not None:
            self.callback_filler.release()
        self.handler_filler_release.set()
        if self.handler_filler is not None:
            response = await asyncio.wait_for(self.handler_filler, timeout=self.deadline)
            assert response.status_code == 200
        await _wait_until(
            lambda: not any(self.runtime._runtime_work.snapshot().__dict__.values()),
            self.deadline,
            self.original_sleep,
        )

    def _assert_runtime_started(self) -> None:
        assert self.runtime_started

    async def _run_action(self, action: dict) -> None:
        name = action["action"]
        request_id = action["requestId"]
        if name == "start-runtime":
            assert not self.runtime_started
            assert self.runtime._runtime_work._accepting
            assert not any(self.runtime._runtime_work.snapshot().__dict__.values())
            self.runtime_started = True
            has_capacity_fixture = any(
                item["action"] in ("fill-callback-capacity", "fill-handler-capacity")
                for item in self.scenario["harness"]["actions"]
            )
            if not has_capacity_fixture:
                self._assert_initial_counters()
            return
        if name == "send-request":
            self._assert_runtime_started()
            assert self.initial_counters_verified or self.verify_initial_at_handler_admission
            if self.stop_task is not None and self.restart_count == 0:
                assert not self.runtime._runtime_work._accepting
            else:
                assert self.runtime._runtime_work._accepting
            execution_id = self.requests[request_id]["metadata"]["executionId"]
            prior_same_execution = [
                sent
                for sent in self.sent_requests
                if self.requests[sent]["metadata"]["executionId"] == execution_id
            ]
            if prior_same_execution:
                assert request_id in self.redispatch_authorized
            self.invocations[request_id] = asyncio.create_task(self._invoke(request_id))
            self.sent_requests.append(request_id)
            return
        if name == "await-response":
            if request_id in self.responses:
                return
            self.responses[request_id] = await asyncio.wait_for(
                asyncio.shield(self.invocations[request_id]), timeout=self.deadline
            )
            self._observe_runtime_bytes(
                "outputBytes", len(self.responses[request_id].content)
            )
            self.observations.add("wire-response")
            return
        if name == "await-callback":
            await self._await_callback(request_id)
            return
        if name == "await-barrier":
            await asyncio.wait_for(
                self.barriers[action["barrier"]].wait(), timeout=self.deadline
            )
            return
        if name == "cancel-request":
            task = self.invocations[request_id]
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
            self.observations.add("no-wire-response")
            return
        if name == "fill-callback-capacity":
            self._assert_runtime_started()
            assert self.runtime._runtime_work._accepting
            await self._fill_declared_initial_state()
            return
        if name == "drain-callbacks":
            await self._drain_fixture_state()
            return
        if name == "fill-handler-capacity":
            self._assert_runtime_started()
            assert self.runtime._runtime_work._accepting
            await self._fill_declared_initial_state()
            return
        if name == "drain-handlers":
            await self._drain_fixture_state()
            return
        if name == "probe-health":
            self._assert_runtime_started()
            assert self.runtime._runtime_work._accepting
            assert self.initial_counters_verified
            self.responses[request_id] = await self._exchange("GET", "/health")
            self._observe_runtime_bytes(
                "outputBytes", len(self.responses[request_id].content)
            )
            self.observations.update(("wire-response", "health-response"))
            return
        if name == "begin-stop":
            self._assert_runtime_started()
            assert self.initial_counters_verified
            assert self.runtime._runtime_work._accepting
            self.stop_count += 1
            self.stop_task = asyncio.create_task(
                self.runtime._runtime_work.shutdown(self.deadline)
            )
            await _wait_until(
                lambda: not self.runtime._runtime_work._accepting,
                self.deadline,
                self.original_sleep,
            )
            self.callback_filler_release.set()
            if action["barrier"] is not None:
                self.barriers[action["barrier"]].set()
            return
        if name == "await-stop":
            self.stop_report = await asyncio.wait_for(
                self.stop_task, timeout=self.deadline
            )
            assert self.stop_report.drained is True
            if "stop-complete" in self.scenario["expected"]["observations"]:
                self.observations.add("stop-complete")
            return
        if name == "start-runtime-again":
            assert not self.runtime._runtime_work._accepting
            self.restart_count += 1
            self.runtime = importlib.reload(runtime_app)
            self._register_handler()
            assert self.runtime._runtime_work._accepting is True
            assert not any(self.runtime._runtime_work.snapshot().__dict__.values())
            self.runtime_started = True
            self.observations.add("restart-complete")
            return
        if name == "control-plane-redispatch":
            self._assert_runtime_started()
            assert self.runtime._runtime_work._accepting
            assert request_id not in self.sent_requests
            target = self.requests[request_id]["metadata"]
            prior = [
                self.requests[sent]
                for sent in self.sent_requests
                if self.requests[sent]["metadata"]["executionId"]
                == target["executionId"]
            ]
            assert prior
            assert target["dispatchAttempt"] == max(
                item["metadata"]["dispatchAttempt"] for item in prior
            ) + 1
            assert self.handler_invocations == self.sent_requests
            assert self.runtime._runtime_work.snapshot().active_handlers == 0
            assert self.runtime._runtime_work.snapshot().pending_callbacks == 0
            self.redispatch_authorized.add(request_id)
            return
        raise AssertionError(f"unsupported corpus action {name}")

    def _assert_responses(self) -> None:
        definitions = self.corpus["contractDefinitions"][
            self.corpus["policy"]["definitionsRef"]
        ]
        for expected in self.scenario["expected"]["responses"]:
            if expected["connectionOutcome"] == "client-disconnected":
                assert expected["requestId"] not in self.responses
                continue
            response = self.responses[expected["requestId"]]
            outcome = definitions["wireOutcomes"][expected["outcomeRef"]]
            assert response.status_code == outcome["status"] == expected["status"]
            body = response.body if hasattr(response, "body") else response.content
            actual_body = json.loads(body)
            assert actual_body == outcome["body"] == expected["body"], {
                "scenario": self.scenario["id"],
                "request": expected["requestId"],
                "actual": actual_body,
                "expected": expected["body"],
            }
            response_headers = {key.lower(): value for key, value in response.headers.items()}
            for key, value in expected["requiredHeaders"].items():
                assert response_headers[key].split(";")[0] == value

    def _assert_handlers(self) -> None:
        definitions = self.corpus["contractDefinitions"][
            self.corpus["policy"]["definitionsRef"]
        ]
        for expected in self.scenario["expected"]["handlers"]:
            actual = self.handler_states[expected["requestId"]]
            declared = definitions["handlerLifecycles"][expected["lifecycleRef"]]
            assert actual == declared
            assert actual == {
                "started": expected["started"],
                "cancelRequested": expected["cancelRequested"],
                "terminal": expected["terminal"],
            }

    def _assert_callbacks(self) -> None:
        definitions = self.corpus["contractDefinitions"][
            self.corpus["policy"]["definitionsRef"]
        ]
        config = self.corpus["runtimeConfigurations"][self.scenario["runtimeConfigRef"]]
        for expected in self.scenario["expected"]["callbacks"]:
            request_id = expected["requestId"]
            calls = self._calls_for(request_id)
            required = bool(self.requests[request_id]["metadata"]["callbackUrl"])
            attempted = bool(calls)
            delivered = any(call["status"] < 400 for call in calls)
            if not required:
                terminal = "not-required"
            elif delivered:
                terminal = "delivered"
            elif calls:
                terminal = "exhausted"
            else:
                terminal = "rejected-before-handler"
            actual = {
                "required": required,
                "attempted": attempted,
                "delivered": delivered,
                "attempts": len(calls),
                "terminal": terminal,
            }
            declared = definitions["callbackLifecycles"][expected["lifecycleRef"]]
            attempt_rule = declared["attempts"]
            declared_attempts = (
                attempt_rule["value"]
                if attempt_rule["operator"] == "literal"
                else config[attempt_rule["field"]]
            )
            assert actual == {
                "required": declared["required"],
                "attempted": declared["attempted"],
                "delivered": declared["delivered"],
                "attempts": declared_attempts,
                "terminal": declared["terminal"],
            }
            assert actual == {
                key: expected[key]
                for key in ("required", "attempted", "delivered", "attempts", "terminal")
            }
            assert [
                int(call["headers"]["x-dispatch-attempt"]) for call in calls
            ] == expected["dispatchAttempts"]
            projection = expected["requestProjection"]
            if projection is not None:
                for call in calls:
                    assert {
                        "method": call["method"],
                        "url": call["url"],
                        "headers": call["headers"],
                        "payload": call["payload"],
                    } == projection

    def _assert_identity(self) -> None:
        expected = self.scenario["expected"]["identity"]
        actual_attempts = [
            self.requests[request_id]["metadata"]["dispatchAttempt"]
            for request_id in self.sent_requests
            if self.requests[request_id]["metadata"]["executionId"]
            == expected["executionId"]
        ]
        assert actual_attempts == expected["requestDispatchAttempts"]
        unexpected_invocations = [
            request_id
            for request_id in self.handler_invocations
            if request_id not in self.sent_requests
        ]
        assert len(unexpected_invocations) == expected["runtimeRedispatchCount"]

    def _assert_observations(self, actual_counters: dict[str, int]) -> None:
        expected = self.scenario["expected"]
        definitions = self.corpus["contractDefinitions"][
            self.corpus["policy"]["definitionsRef"]
        ]
        assert definitions["observationSets"][expected["observationSetRef"]] == expected[
            "observations"
        ]

        assert actual_counters == expected["finalCounters"], {
            "scenario": self.scenario["id"],
            "phase": "final",
            "actual": actual_counters,
            "expected": expected["finalCounters"],
        }
        assert definitions["finalCountersRule"] == {"operator": "all-zero"}
        assert not any(actual_counters.values())
        self.observations.add("counters-zero")

        if "callback-failure-metric" in expected["observations"]:
            assert self._failure_metric_value() == self.failure_metric_before + 1
            self.observations.add("callback-failure-metric")

        if "structured-log" in expected["observations"]:
            execution_id = expected["identity"]["executionId"]
            trace_id = self.requests[next(iter(self.requests))]["metadata"]["traceId"]
            assert any(
                entry["level"] == "ERROR"
                and entry["message"] == "Callback failed after all retries"
                and entry["execution_id"] == execution_id
                and entry["trace_id"] == trace_id
                for entry in self.log_capture.entries
            )
            self.observations.add("structured-log")

        if "runtime-redispatch-zero" in expected["observations"]:
            assert expected["identity"]["runtimeRedispatchCount"] == 0
            assert self.handler_invocations == self.sent_requests
            self.observations.add("runtime-redispatch-zero")

        expected_observations = set(expected["observations"])
        assert self.observations == expected_observations, {
            "scenario": self.scenario["id"],
            "missing": sorted(expected_observations - self.observations),
            "unexpected": sorted(self.observations - expected_observations),
        }

    async def run(self) -> ScenarioExecution:
        async def deterministic_sleep(delay):
            if delay in (0.1, 0.5, 2.0):
                await self.original_sleep(0)
            else:
                await self.original_sleep(delay)

        self.runtime.logger.addHandler(self.log_capture)
        try:
            with patch.object(
                self.runtime.requests, "post", side_effect=self.callback_post
            ), patch.object(
                self.runtime.asyncio, "sleep", side_effect=deterministic_sleep
            ), patch.object(
                self.runtime._runtime_work,
                "start_handler",
                side_effect=self._observed_start_handler,
            ):
                async with asyncio.timeout(self.deadline):
                    for action in sorted(
                        self.scenario["harness"]["actions"],
                        key=lambda item: item["sequence"],
                    ):
                        await self._run_action(action)
                    await _wait_until(
                        lambda: self.runtime._runtime_work.snapshot().active_handlers == 0,
                        self.deadline,
                        self.original_sleep,
                    )

                self._assert_responses()
                self._assert_handlers()
                self._assert_callbacks()
                self._assert_identity()
                assert self.initial_counters_verified
                actual_counters = self._capture_resources()
                self._assert_observations(actual_counters)
        finally:
            self.runtime.logger.removeHandler(self.log_capture)
            self.callback_filler_release.set()
            self.handler_filler_release.set()
            if self.callback_filler_task is not None:
                await asyncio.gather(self.callback_filler_task, return_exceptions=True)
            if self.handler_filler is not None:
                await asyncio.gather(self.handler_filler, return_exceptions=True)
            await self.runtime._runtime_work.shutdown(self.deadline)

        if self.runtime._runtime_work._accepting:
            await self.runtime._runtime_work.shutdown(self.deadline)
        return ScenarioExecution(
            barriers=len(self.barriers),
            callbacks=sum(bool(self._calls_for(request_id)) for request_id in self.requests),
            callback_attempts=len(self.callback_calls),
            stops=self.stop_count,
            observations=len(self.observations),
            nonzero_counters=frozenset(
                name for name, peak in self.resource_peaks.items() if peak > 0
            ),
        )


def _runtime_environment(config: dict) -> dict[str, str]:
    return {
        "NANOFAAS_MAX_CONCURRENT_HANDLERS": str(config["maxConcurrentHandlers"]),
        "NANOFAAS_CALLBACK_WORKERS": "1",
        "NANOFAAS_MAX_PENDING_CALLBACKS": str(config["maxPendingCallbacks"]),
        "NANOFAAS_MAX_INPUT_BYTES": str(config["maxInputBytes"]),
        "NANOFAAS_MAX_OUTPUT_BYTES": str(config["maxOutputBytes"]),
        "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES": str(config["maxPendingCallbackBytes"]),
        "NANOFAAS_MAX_PENDING_CALLBACK_BYTES": str(config["maxPendingCallbackBytes"]),
        "NANOFAAS_HANDLER_TIMEOUT": str(config["handlerTimeoutMs"]),
        "NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT": str(config["callbackAttemptTimeoutMs"]),
        "NANOFAAS_CALLBACK_MAX_ATTEMPTS": str(config["callbackMaxAttempts"]),
        "NANOFAAS_BODY_READ_TIMEOUT": str(config["bodyReadTimeoutMs"]),
        "NANOFAAS_SHUTDOWN_TIMEOUT": str(config["shutdownTimeoutMs"]),
        "FUNCTION_NAME": "python-corpus-adapter",
    }


def execute_corpus_against_runtime(corpus_path: Path) -> ExecutionSummary:
    corpus = json.loads(corpus_path.read_text(encoding="utf-8"))

    async def execute_all():
        barriers = callbacks = callback_attempts = stops = restarts = observations = 0
        nonzero_counters = set()
        for scenario in corpus["scenarios"]:
            config = corpus["runtimeConfigurations"][scenario["runtimeConfigRef"]]
            with patch.dict(os.environ, _runtime_environment(config)):
                runtime = importlib.reload(runtime_app)
                harness = ScenarioHarness(corpus, scenario, runtime, asyncio.sleep)
                result = await harness.run()
                barriers += result.barriers
                callbacks += result.callbacks
                callback_attempts += result.callback_attempts
                stops += result.stops
                observations += result.observations
                nonzero_counters.update(result.nonzero_counters)
                restarts += harness.restart_count
        return ExecutionSummary(
            scenarios=len(corpus["scenarios"]),
            barriers=barriers,
            callbacks=callbacks,
            callback_attempts=callback_attempts,
            stops=stops,
            restarts=restarts,
            observations=observations,
            nonzero_counters=tuple(sorted(nonzero_counters)),
        )

    try:
        return asyncio.run(execute_all())
    finally:
        importlib.reload(runtime_app)
