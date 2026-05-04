"""
Tests for k3s_runtime - K3sCurlRunner, HelmStackRunner, and helper functions (M11).

Gate: runners must guard against un-bootstrapped VMs, resolve function metadata from
scenario, cache the ClusterIP, and route loadtest through the controlplane runner
(not deleted experiments/e2e-loadtest-registry.sh).
"""
from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.e2e.k3s_curl_runner import K3sCurlRunner
from controlplane_tool.e2e.helm_stack_runner import HelmStackRunner
from controlplane_tool.scenario.scenario_helpers import (
    function_family as _function_family,
    function_image as _function_image,
    function_runtime as _function_runtime,
    selected_functions as _selected_functions,
)
from controlplane_tool.scenario.scenario_helpers import function_payload as _function_payload
from controlplane_tool.infra.vm.vm_models import VmRequest


# ---------------------------------------------------------------------------
# Helper function unit tests
# ---------------------------------------------------------------------------

def _make_fn(key: str, **kwargs) -> "Any":
    from controlplane_tool.scenario.scenario_models import ResolvedFunction

    defaults = dict(family="echo", runtime="java", description="test fn")
    defaults.update(kwargs)
    return ResolvedFunction(key=key, **defaults)


def _make_resolved(functions: list[dict]):
    """Build a minimal ResolvedScenario from dicts with required fields filled in."""
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    fns = [_make_fn(**fn) for fn in functions]
    return ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
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


def test_k3s_curl_runner_waits_for_managed_function_before_invoking(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    resolved = _make_resolved([
        {"key": "word-stats-java", "family": "word-stats", "image": "localhost:5000/nanofaas/java-word-stats:e2e"}
    ])

    calls: list[tuple[str, str]] = []

    runner._delete_function = lambda fn_key: calls.append(("delete", fn_key))  # type: ignore[attr-defined]
    runner._register_function = lambda fn_key, fn_image: calls.append(("register", fn_key))  # type: ignore[method-assign]
    runner._invoke_function = lambda fn_key, payload: calls.append(("invoke", fn_key))  # type: ignore[method-assign]
    runner._enqueue_function = lambda fn_key, payload: (calls.append(("enqueue", fn_key)) or "exec-1")  # type: ignore[method-assign]
    runner._poll_execution = lambda exec_id: calls.append(("poll", exec_id))  # type: ignore[method-assign]
    runner._await_managed_function_ready = lambda fn_key: calls.append(("wait", fn_key))  # type: ignore[method-assign]

    runner._run_function_workflow("word-stats-java", resolved)

    assert calls == [
        ("delete", "word-stats-java"),
        ("register", "word-stats-java"),
        ("wait", "word-stats-java"),
        ("invoke", "word-stats-java"),
        ("enqueue", "word-stats-java"),
        ("poll", "exec-1"),
        ("delete", "word-stats-java"),
    ]


def test_k3s_curl_runner_register_function_uses_deterministic_scaling(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    captured: dict[str, Any] = {}

    runner._kubectl_curl = lambda *args, **kwargs: pytest.fail("unexpected _kubectl_curl call")  # type: ignore[method-assign]
    runner._kubectl_curl_with_status = lambda method, path, body_json=None: (  # type: ignore[attr-defined]
        captured.update({"method": method, "path": path, "body_json": body_json}) or (201, '{"name":"word-stats-java"}')
    )

    runner._register_function("word-stats-java", "localhost:5000/nanofaas/java-word-stats:e2e")

    payload = json.loads(captured["body_json"])
    assert captured["method"] == "POST"
    assert captured["path"] == "/v1/functions"
    assert payload["name"] == "word-stats-java"
    assert payload["image"] == "localhost:5000/nanofaas/java-word-stats:e2e"
    assert payload["scalingConfig"]["minReplicas"] == 1
    assert payload["scalingConfig"]["maxReplicas"] == 1


def test_k3s_curl_runner_register_function_raises_on_non_created_status(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())

    runner._kubectl_curl = lambda *args, **kwargs: pytest.fail("unexpected _kubectl_curl call")  # type: ignore[method-assign]
    runner._kubectl_curl_with_status = lambda method, path, body_json=None: (  # type: ignore[attr-defined]
        409, '{"message":"already registered"}'
    )

    with pytest.raises(RuntimeError, match="already registered"):
        runner._register_function("word-stats-java", "localhost:5000/nanofaas/java-word-stats:e2e")


def test_k3s_curl_runner_reconciles_function_before_and_after_workflow(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    resolved = _make_resolved([
        {"key": "word-stats-java", "family": "word-stats", "image": "localhost:5000/nanofaas/java-word-stats:e2e"}
    ])

    calls: list[tuple[str, str]] = []

    runner._delete_function = lambda fn_key: calls.append(("delete", fn_key))  # type: ignore[attr-defined]
    runner._register_function = lambda fn_key, fn_image: calls.append(("register", fn_key))  # type: ignore[method-assign]
    runner._invoke_function = lambda fn_key, payload: calls.append(("invoke", fn_key))  # type: ignore[method-assign]
    runner._enqueue_function = lambda fn_key, payload: (calls.append(("enqueue", fn_key)) or "exec-1")  # type: ignore[method-assign]
    runner._poll_execution = lambda exec_id: calls.append(("poll", exec_id))  # type: ignore[method-assign]
    runner._await_managed_function_ready = lambda fn_key: calls.append(("wait", fn_key))  # type: ignore[method-assign]

    runner._run_function_workflow("word-stats-java", resolved)

    assert calls == [
        ("delete", "word-stats-java"),
        ("register", "word-stats-java"),
        ("wait", "word-stats-java"),
        ("invoke", "word-stats-java"),
        ("enqueue", "word-stats-java"),
        ("poll", "exec-1"),
        ("delete", "word-stats-java"),
    ]


def test_k3s_curl_runner_retries_sync_invoke_until_success(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    responses = iter([
        '{"executionId":"one","status":"error","error":{"code":"POOL_ERROR","message":"Connection refused"}}',
        '{"executionId":"one","status":"success","output":{"wordCount":3},"error":null}',
    ])
    calls: list[tuple[str, str]] = []

    def fake_kubectl_curl(method: str, path: str, body_json: str | None = None) -> str:
        calls.append((method, path))
        return next(responses)

    runner._kubectl_curl = fake_kubectl_curl  # type: ignore[method-assign]

    runner._invoke_function("word-stats-java", '{"input":{"text":"nano faas nano","topN":2}}')

    assert calls == [
        ("POST", "/v1/functions/word-stats-java:invoke"),
        ("POST", "/v1/functions/word-stats-java:invoke"),
    ]


def test_k3s_curl_runner_verify_prometheus_metrics_accepts_legacy_names(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    runner._control_plane_service_ip = lambda: "10.96.0.1"  # type: ignore[method-assign]
    runner._vm_exec = lambda cmd: "\n".join([  # type: ignore[method-assign]
        "function_enqueue_total{function=\"word-stats-java\"} 1",
        "function_success_total{function=\"word-stats-java\"} 1",
        "function_queue_depth{function=\"word-stats-java\"} 0",
        "function_inFlight{function=\"word-stats-java\"} 0",
    ])

    runner._verify_prometheus_metrics()


def test_k3s_curl_runner_verify_prometheus_metrics_accepts_sync_queue_names(tmp_path) -> None:
    runner = K3sCurlRunner(tmp_path, vm_request=_make_vm_request())
    runner._control_plane_service_ip = lambda: "10.96.0.1"  # type: ignore[method-assign]
    runner._vm_exec = lambda cmd: "\n".join([  # type: ignore[method-assign]
        "function_enqueue_total{function=\"word-stats-java\"} 1",
        "function_success_total{function=\"word-stats-java\"} 1",
        "sync_queue_depth{function=\"word-stats-java\"} 0",
        "function_dispatch_total{function=\"word-stats-java\"} 2",
    ])

    runner._verify_prometheus_metrics()


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


def test_helm_stack_runner_run_invokes_controlplane_loadtest_runner(tmp_path, monkeypatch) -> None:
    """HelmStackRunner must not call deleted e2e-loadtest-registry.sh (M12)."""
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)

    calls: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        calls.append(cmd)
        return MagicMock(returncode=0)

    with patch("subprocess.run", side_effect=fake_run):
        runner.run()

    called_cmds = [" ".join(c) for c in calls]
    # Must call controlplane-tool loadtest run.
    assert any("loadtest" in cmd and "run" in cmd for cmd in called_cmds)
    # Must NOT call deleted experiments/e2e-loadtest-registry.sh
    assert not any("e2e-loadtest-registry.sh" in cmd for cmd in called_cmds)


def test_helm_stack_runner_run_invokes_autoscaling_script(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")
    vm_req = _make_vm_request()
    runner = HelmStackRunner(tmp_path, vm_request=vm_req)

    calls: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        calls.append(cmd)
        return MagicMock(returncode=0)

    with patch("subprocess.run", side_effect=fake_run):
        runner.run()

    called_cmds = [" ".join(c) for c in calls]
    assert any("autoscaling.py" in cmd for cmd in called_cmds)
