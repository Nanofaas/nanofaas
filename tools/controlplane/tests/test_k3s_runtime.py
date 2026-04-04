"""
Tests for k3s_runtime - K3sCurlRunner, HelmStackRunner, and helper functions (M11).

Gate: runners must guard against un-bootstrapped VMs, resolve function metadata from
scenario, cache the ClusterIP, and route loadtest to the Python runner (not deleted
experiments/e2e-loadtest-registry.sh).
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, call, patch

import pytest

from controlplane_tool.k3s_runtime import (
    HelmStackRunner,
    K3sCurlRunner,
    _function_family,
    _function_image,
    _function_payload,
    _function_runtime,
    _selected_functions,
)
from controlplane_tool.vm_models import VmRequest


# ---------------------------------------------------------------------------
# Helper function unit tests
# ---------------------------------------------------------------------------

def _make_fn(key: str, **kwargs) -> "Any":
    from controlplane_tool.scenario_models import ResolvedFunction

    defaults = dict(family="echo", runtime="java", description="test fn")
    defaults.update(kwargs)
    return ResolvedFunction(key=key, **defaults)


def _make_resolved(functions: list[dict]):
    """Build a minimal ResolvedScenario from dicts with required fields filled in."""
    from controlplane_tool.scenario_models import ResolvedScenario

    fns = [_make_fn(**fn) for fn in functions]
    return ResolvedScenario(
        name="test",
        base_scenario="k8s-vm",
        runtime="java",
        functions=fns,
    )


def test_selected_functions_returns_echo_test_when_resolved_is_none() -> None:
    assert _selected_functions(None) == ["echo-test"]


def test_selected_functions_returns_echo_test_when_functions_is_empty() -> None:
    resolved = _make_resolved([])
    assert _selected_functions(resolved) == ["echo-test"]


def test_selected_functions_returns_all_function_keys() -> None:
    resolved = _make_resolved([
        {"key": "echo-test"},
        {"key": "word-stats"},
    ])
    assert _selected_functions(resolved) == ["echo-test", "word-stats"]


def test_function_image_returns_default_when_resolved_is_none() -> None:
    assert _function_image("echo-test", None, "default:img") == "default:img"


def test_function_image_returns_resolved_image_when_present() -> None:
    resolved = _make_resolved([{"key": "echo-test", "image": "registry/fn:custom"}])
    assert _function_image("echo-test", resolved, "fallback") == "registry/fn:custom"


def test_function_image_returns_default_when_key_not_found() -> None:
    resolved = _make_resolved([{"key": "other-fn"}])
    assert _function_image("echo-test", resolved, "fallback:img") == "fallback:img"


def test_function_runtime_defaults_to_java_when_resolved_none() -> None:
    assert _function_runtime("echo-test", None) == "java"


def test_function_runtime_reads_from_resolved() -> None:
    resolved = _make_resolved([{"key": "word-stats", "runtime": "python"}])
    assert _function_runtime("word-stats", resolved) == "python"


def test_function_family_returns_none_when_resolved_none() -> None:
    assert _function_family("echo-test", None) is None


def test_function_family_reads_from_resolved() -> None:
    resolved = _make_resolved([{"key": "echo-test", "family": "echo"}])
    assert _function_family("echo-test", resolved) == "echo"


def test_function_payload_returns_default_hello_when_none() -> None:
    result = _function_payload("echo-test", None)
    parsed = json.loads(result)
    assert "input" in parsed
    assert "message" in parsed["input"]


def test_function_payload_reads_from_payload_path(tmp_path) -> None:
    payload_file = tmp_path / "payload.json"
    payload_file.write_text('{"message": "hi"}', encoding="utf-8")
    resolved = _make_resolved([{"key": "echo-test", "payload_path": payload_file}])
    result = _function_payload("echo-test", resolved)
    parsed = json.loads(result)
    assert parsed == {"input": {"message": "hi"}}


def test_function_payload_returns_default_when_key_not_found() -> None:
    resolved = _make_resolved([{"key": "other"}])
    result = _function_payload("echo-test", resolved)
    parsed = json.loads(result)
    assert "message" in parsed["input"]


def test_function_payload_returns_default_when_payload_path_missing() -> None:
    resolved = _make_resolved([{"key": "echo-test", "payload_path": None}])
    result = _function_payload("echo-test", resolved)
    parsed = json.loads(result)
    assert "message" in parsed["input"]


# ---------------------------------------------------------------------------
# K3sCurlRunner construction and guard tests
# ---------------------------------------------------------------------------

def _make_vm_request(lifecycle: str = "multipass", name: str = "test-vm") -> VmRequest:
    return VmRequest(lifecycle=lifecycle, name=name)


def test_k3s_curl_runner_construction_uses_provided_vm_request(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req, namespace="my-ns")
    assert runner.vm_request is vm_req
    assert runner.namespace == "my-ns"


def test_k3s_curl_runner_uses_provided_registry(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req, local_registry="myhost:5001")
    assert runner.registry.address == "myhost:5001"


def test_k3s_curl_runner_requires_skip_bootstrap_env(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req)
    with pytest.raises(RuntimeError, match="E2E_SKIP_VM_BOOTSTRAP"):
        runner.run()


def test_k3s_curl_runner_service_ip_is_cached(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req)
    runner._cached_service_ip = "10.96.0.1"
    assert runner._control_plane_service_ip() == "10.96.0.1"


def test_k3s_curl_runner_service_ip_not_fetched_twice_when_cached(tmp_path, monkeypatch) -> None:
    """ClusterIP lookup should hit _vm_exec only once, then use cache."""
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req)

    exec_calls: list[str] = []
    original_vm_exec = runner._vm_exec

    def counting_exec(cmd: str) -> str:
        exec_calls.append(cmd)
        if "jsonpath" in cmd:
            return "10.96.0.1"
        return ""

    runner._vm_exec = counting_exec  # type: ignore[method-assign]

    ip1 = runner._control_plane_service_ip()
    ip2 = runner._control_plane_service_ip()

    assert ip1 == ip2 == "10.96.0.1"
    # Must only have called kubectl get svc once
    svc_lookups = [c for c in exec_calls if "get svc" in c]
    assert len(svc_lookups) == 1


def test_k3s_curl_runner_service_ip_raises_when_empty(tmp_path, monkeypatch) -> None:
    vm_req = _make_vm_request()
    runner = K3sCurlRunner(tmp_path, vm_request=vm_req)
    runner._vm_exec = lambda cmd: ""  # type: ignore[method-assign]
    with pytest.raises(RuntimeError, match="ClusterIP"):
        runner._control_plane_service_ip()


# ---------------------------------------------------------------------------
# HelmStackRunner tests
# ---------------------------------------------------------------------------

def test_helm_stack_runner_construction_defaults(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)
    assert runner.namespace == "nanofaas"
    assert runner.registry.address == "localhost:5000"
    assert runner.runtime == "java"
    assert runner.noninteractive is True


def test_helm_stack_runner_requires_skip_bootstrap_env(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)
    with pytest.raises(RuntimeError, match="E2E_SKIP_VM_BOOTSTRAP"):
        runner.run()


def test_helm_stack_runner_build_env_includes_vm_name(tmp_path) -> None:
    vm_req = VmRequest(lifecycle="multipass", name="my-vm")
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)
    env = runner._build_env()
    assert env["VM_NAME"] == "my-vm"


def test_helm_stack_runner_build_env_defaults_vm_name_when_absent(tmp_path) -> None:
    vm_req = VmRequest(lifecycle="multipass")
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)
    env = runner._build_env()
    assert env["VM_NAME"] == "nanofaas-e2e"


def test_helm_stack_runner_build_env_sets_noninteractive_flag(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req, noninteractive=True)
    env = runner._build_env()
    assert env.get("E2E_K3S_HELM_NONINTERACTIVE") == "true"


def test_helm_stack_runner_build_env_omits_noninteractive_when_false(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req, noninteractive=False)
    env = runner._build_env()
    assert "E2E_K3S_HELM_NONINTERACTIVE" not in env


def test_helm_stack_runner_build_env_sets_host_when_external(tmp_path) -> None:
    vm_req = VmRequest(lifecycle="external", host="192.168.64.5")
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)
    env = runner._build_env()
    assert env["E2E_VM_HOST"] == "192.168.64.5"


def test_helm_stack_runner_run_invokes_python_loadtest_runner(tmp_path, monkeypatch) -> None:
    """HelmStackRunner must not call deleted e2e-loadtest-registry.sh (M12)."""
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)

    calls: list[list[str]] = []

    def fake_run(cmd, *, check, env):
        calls.append(cmd)
        return MagicMock(returncode=0)

    with patch("subprocess.run", side_effect=fake_run):
        runner.run()

    called_cmds = [" ".join(c) for c in calls]
    # Must call controlplane-tool loadtest run (Python runner)
    assert any("loadtest" in cmd and "run" in cmd for cmd in called_cmds)
    # Must NOT call deleted experiments/e2e-loadtest-registry.sh
    assert not any("e2e-loadtest-registry.sh" in cmd for cmd in called_cmds)


def test_helm_stack_runner_run_invokes_autoscaling_script(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)

    calls: list[list[str]] = []

    def fake_run(cmd, *, check, env):
        calls.append(cmd)
        return MagicMock(returncode=0)

    with patch("subprocess.run", side_effect=fake_run):
        runner.run()

    called_cmds = [" ".join(c) for c in calls]
    assert any("e2e-autoscaling.sh" in cmd for cmd in called_cmds)
