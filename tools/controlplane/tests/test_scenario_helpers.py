"""
Tests for scenario_helpers — the shared, stateless scenario resolution helpers.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from controlplane_tool.scenario_helpers import (
    function_family,
    function_image,
    function_payload,
    function_runtime,
    resolve_scenario,
    selected_functions,
)


# ---------------------------------------------------------------------------
# Fixtures / factories
# ---------------------------------------------------------------------------

def _make_fn(key: str, **kwargs):
    from controlplane_tool.scenario_models import ResolvedFunction

    defaults = dict(family="echo", runtime="java", description="test fn")
    defaults.update(kwargs)
    return ResolvedFunction(key=key, **defaults)


def _make_resolved(*fn_dicts):
    from controlplane_tool.scenario_models import ResolvedScenario

    fns = [_make_fn(**d) for d in fn_dicts]
    return ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=fns,
    )


# ---------------------------------------------------------------------------
# resolve_scenario
# ---------------------------------------------------------------------------

def test_resolve_scenario_returns_none_when_no_file() -> None:
    assert resolve_scenario(None) is None


def test_resolve_scenario_raises_for_missing_file() -> None:
    with pytest.raises(Exception):
        resolve_scenario(Path("/nonexistent/scenario.toml"))


# ---------------------------------------------------------------------------
# selected_functions
# ---------------------------------------------------------------------------

def test_selected_functions_default_when_none() -> None:
    assert selected_functions(None) == ["echo-test"]


def test_selected_functions_default_when_empty_functions() -> None:
    resolved = _make_resolved()
    assert selected_functions(resolved) == ["echo-test"]


def test_selected_functions_custom_default_when_none() -> None:
    assert selected_functions(None, default="word-stats") == ["word-stats"]


def test_selected_functions_returns_all_keys() -> None:
    resolved = _make_resolved({"key": "fn-a"}, {"key": "fn-b"})
    assert selected_functions(resolved) == ["fn-a", "fn-b"]


def test_selected_functions_single_key() -> None:
    resolved = _make_resolved({"key": "echo-test"})
    assert selected_functions(resolved) == ["echo-test"]


# ---------------------------------------------------------------------------
# function_image
# ---------------------------------------------------------------------------

def test_function_image_default_when_none() -> None:
    assert function_image("echo-test", None, "fallback:img") == "fallback:img"


def test_function_image_default_when_key_not_found() -> None:
    resolved = _make_resolved({"key": "other"})
    assert function_image("echo-test", resolved, "fallback:img") == "fallback:img"


def test_function_image_returns_resolved_image() -> None:
    resolved = _make_resolved({"key": "echo-test", "image": "custom/echo:v1"})
    assert function_image("echo-test", resolved, "fallback") == "custom/echo:v1"


def test_function_image_default_when_image_is_none() -> None:
    resolved = _make_resolved({"key": "echo-test", "image": None})
    assert function_image("echo-test", resolved, "fallback") == "fallback"


# ---------------------------------------------------------------------------
# function_runtime
# ---------------------------------------------------------------------------

def test_function_runtime_java_default_when_none() -> None:
    assert function_runtime("echo-test", None) == "java"


def test_function_runtime_java_default_when_key_not_found() -> None:
    resolved = _make_resolved({"key": "other"})
    assert function_runtime("echo-test", resolved) == "java"


def test_function_runtime_reads_from_resolved() -> None:
    resolved = _make_resolved({"key": "word-stats", "runtime": "python"})
    assert function_runtime("word-stats", resolved) == "python"


# ---------------------------------------------------------------------------
# function_family
# ---------------------------------------------------------------------------

def test_function_family_none_when_resolved_none() -> None:
    assert function_family("echo-test", None) is None


def test_function_family_none_when_key_not_found() -> None:
    resolved = _make_resolved({"key": "other"})
    assert function_family("echo-test", resolved) is None


def test_function_family_reads_from_resolved() -> None:
    resolved = _make_resolved({"key": "echo-test", "family": "echo"})
    assert function_family("echo-test", resolved) == "echo"


# ---------------------------------------------------------------------------
# function_payload
# ---------------------------------------------------------------------------

def test_function_payload_default_message_when_none() -> None:
    result = json.loads(function_payload("echo-test", None))
    assert result == {"input": {"message": "hello"}}


def test_function_payload_custom_default_message() -> None:
    result = json.loads(function_payload("echo-test", None, default_message="hi"))
    assert result == {"input": {"message": "hi"}}


def test_function_payload_default_when_key_not_found() -> None:
    resolved = _make_resolved({"key": "other"})
    result = json.loads(function_payload("echo-test", resolved))
    assert "message" in result["input"]


def test_function_payload_reads_per_function_payload_path(tmp_path) -> None:
    payload_file = tmp_path / "payload.json"
    payload_file.write_text('{"data": 42}', encoding="utf-8")
    resolved = _make_resolved({"key": "echo-test", "payload_path": payload_file})
    result = json.loads(function_payload("echo-test", resolved))
    assert result == {"input": {"data": 42}}


def test_function_payload_reads_scenario_level_payloads(tmp_path) -> None:
    payload_file = tmp_path / "payload.json"
    payload_file.write_text('{"x": 1}', encoding="utf-8")
    from controlplane_tool.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=[_make_fn("echo-test")],
        payloads={"echo-test": payload_file},
    )
    result = json.loads(function_payload("echo-test", resolved))
    assert result == {"input": {"x": 1}}


def test_function_payload_falls_back_to_default_when_path_missing(tmp_path) -> None:
    resolved = _make_resolved({"key": "echo-test", "payload_path": tmp_path / "gone.json"})
    result = json.loads(function_payload("echo-test", resolved))
    assert "message" in result["input"]
