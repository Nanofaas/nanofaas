"""
Tests for cli_runtime — CliStackRunner, CliHostPlatformRunner, and related helpers.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess

import pytest

import controlplane_tool.cli_validation.cli_stack_runner as cli_stack_runner_mod
import controlplane_tool.cli.runtime as cli_runtime
import controlplane_tool.e2e.container_local_runner as container_local_runner_mod
import controlplane_tool.e2e.deploy_host_runner as deploy_host_runner_mod
from controlplane_tool.scenario.components import cli as cli_components
from controlplane_tool.scenario.components.cli import CliComponentContext
from controlplane_tool.cli_validation.cli_host_runner import CliHostPlatformRunner
from tui_toolkit import bind_workflow_context, bind_workflow_sink
from controlplane_tool.e2e.container_local_runner import ContainerLocalE2eRunner
from controlplane_tool.e2e.deploy_host_runner import DeployHostE2eRunner
from controlplane_tool.scenario.scenario_helpers import (
    function_image as _function_image,
    selected_functions as _selected_functions,
)
from controlplane_tool.core.shell_backend import ShellExecutionResult
from workflow_tasks.workflow.events import WorkflowContext
from controlplane_tool.infra.vm.vm_models import VmRequest


def _make_vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="test-vm")


class _FakeProcess:
    def terminate(self) -> None:
        return None

    def wait(self, timeout: float | None = None) -> int:
        _ = timeout
        return 0

    def kill(self) -> None:
        return None


def _task_events(sink, task_id: str) -> list:
    return [event for event in sink.events if event.task_id == task_id]


def _assert_balanced_phase(
    sink,
    *,
    task_id: str,
    parent_task_id: str,
) -> None:
    events = _task_events(sink, task_id)
    assert events
    task_kinds = [event.kind for event in events if event.kind.startswith("task.")]
    assert task_kinds == ["task.running", "task.completed"]
    task_titles = {event.title for event in events if event.kind.startswith("task.")}
    assert len(task_titles) == 1
    assert {event.parent_task_id for event in events} == {parent_task_id}


# ---------------------------------------------------------------------------
# _selected_functions and _function_image (shared helpers in cli_runtime)
# ---------------------------------------------------------------------------

def test_cli_selected_functions_returns_echo_test_when_none() -> None:
    assert _selected_functions(None) == ["echo-test"]


def test_cli_selected_functions_uses_custom_default(monkeypatch) -> None:
    assert _selected_functions(None, default="word-stats") == ["word-stats"]


def _rf(key: str, **kwargs):
    from controlplane_tool.scenario.scenario_models import ResolvedFunction

    defaults = dict(family="echo", runtime="java", description="test fn")
    defaults.update(kwargs)
    return ResolvedFunction(key=key, **defaults)


def test_cli_selected_functions_reads_from_resolved() -> None:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=[_rf("fn-a"), _rf("fn-b")],
    )
    assert _selected_functions(resolved) == ["fn-a", "fn-b"]


def test_cli_function_image_returns_default_when_none() -> None:
    assert _function_image("echo-test", None, "fallback:img") == "fallback:img"


def test_cli_function_image_returns_custom_image_from_resolved() -> None:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=[_rf("echo-test", image="custom/echo:v1")],
    )
    assert _function_image("echo-test", resolved, "fallback") == "custom/echo:v1"


def test_cli_runtime_re_exports_dedicated_cli_stack_runner() -> None:
    assert hasattr(cli_runtime, "CliStackRunner")


def test_cli_runtime_does_not_expose_cli_vm_runner() -> None:
    """CliVmRunner must be removed from cli.runtime after legacy CLI consumer cleanup."""
    assert not hasattr(cli_runtime, "CliVmRunner"), (
        "CliVmRunner is still exported from cli.runtime — remove it"
    )


# ---------------------------------------------------------------------------
# CliHostPlatformRunner
# ---------------------------------------------------------------------------

def test_cli_host_platform_runner_construction_defaults(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    assert runner.namespace == "nanofaas-host-cli-e2e"
    assert runner.skip_build is False
    assert runner.skip_cli_build is False


def test_cli_host_platform_runner_requires_skip_bootstrap_env(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    with pytest.raises(RuntimeError, match="E2E_SKIP_VM_BOOTSTRAP"):
        runner.run()


def test_cli_host_platform_runner_deleted_shell_backend_is_gone() -> None:
    """The deleted e2e-cli-host-backend.sh must not exist on disk (M10 gate)."""
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-host-backend.sh").exists(), (
        "e2e-cli-host-backend.sh was not deleted — M10 incomplete"
    )


def test_cli_host_platform_runner_resolves_external_host_from_vm_request(tmp_path) -> None:
    vm_req = VmRequest(lifecycle="external", host="10.0.0.10")
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    # _resolve_public_host uses vm_request.host for external lifecycle

    with pytest.MonkeyPatch().context() as mp:
        mp.delenv("E2E_PUBLIC_HOST", raising=False)
        mp.delenv("E2E_VM_HOST", raising=False)
        host = runner._resolve_public_host()
    assert host == "10.0.0.10"


def test_cli_host_platform_runner_uses_shared_platform_primitives(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(
        tmp_path,
        vm_request=vm_req,
        namespace="test-ns",
        release="test-release",
        local_registry="myreg:5001",
    )
    context = CliComponentContext(
        repo_root=tmp_path,
        release="test-release",
        namespace="test-ns",
        local_registry="myreg:5001",
        resolved_scenario=None,
    )

    assert runner._platform_install_command() == list(
        cli_components.plan_platform_install(context)[0].argv
    )
    assert runner._platform_status_command() == list(
        cli_components.plan_platform_status(context)[0].argv
    )
    assert runner._platform_uninstall_command() == list(
        cli_components._plan_platform_uninstall(context)[0].argv
    )


def test_cli_host_platform_runner_emits_balanced_top_level_phase_events_and_verify_children(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fake_sink,
) -> None:
    monkeypatch.setenv("E2E_SKIP_VM_BOOTSTRAP", "true")

    runner = CliHostPlatformRunner(tmp_path, vm_request=_make_vm_request())
    kubeconfig = tmp_path / "kubeconfig.yaml"
    kubeconfig.write_text("apiVersion: v1\n", encoding="utf-8")

    monkeypatch.setattr(runner, "_resolve_public_host", lambda: "10.0.0.25")
    monkeypatch.setattr(runner, "_export_kubeconfig", lambda: kubeconfig)
    monkeypatch.setattr(runner, "_build_cli_on_host", lambda: None)
    monkeypatch.setattr(runner, "_platform_install_command", lambda: ["platform", "install"])
    monkeypatch.setattr(runner, "_platform_status_command", lambda: ["platform", "status"])
    monkeypatch.setattr(runner, "_platform_uninstall_command", lambda: ["platform", "uninstall"])

    def _run_host_cli(kubeconfig_path: Path, command: list[str]) -> str:
        assert kubeconfig_path == kubeconfig
        if command == ["platform", "install"]:
            return "endpoint\thttp://10.0.0.25:30080"
        if command == ["platform", "status"]:
            return "deployment\tnanofaas-control-plane\t1/1"
        if command == ["platform", "uninstall"]:
            return ""
        raise AssertionError(f"Unexpected host CLI command: {command}")

    monkeypatch.setattr(runner, "_run_host_cli", _run_host_cli)
    monkeypatch.setattr(runner, "_run_host_cli_allow_fail", lambda kubeconfig_path, command: (1, ""))

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.cli_host_platform", task_id="cli.host_platform_flow")
    ):
        runner.run()

    _assert_balanced_phase(
        fake_sink,
        task_id="cli.host_platform.building",
        parent_task_id="cli.host_platform_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="cli.host_platform.deploy",
        parent_task_id="cli.host_platform_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="cli.host_platform.verify_phase",
        parent_task_id="cli.host_platform_flow",
    )
    assert [event.kind for event in _task_events(fake_sink, "cli.host_platform.verify")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "cli.host_platform.verify")
    } == {"cli.host_platform.verify_phase"}


def test_cli_stack_apply_operation_stages_manifest_before_apply(tmp_path) -> None:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="cli-stack",
        runtime="java",
        functions=[_rf("echo-test", image="registry.internal/echo-test:v1")],
    )
    context = CliComponentContext(
        repo_root=tmp_path,
        release="test-release",
        namespace="test-ns",
        local_registry="registry.internal:5000",
        resolved_scenario=resolved,
    )

    operation = cli_components.plan_fn_apply_selected(context)[0]

    assert operation.argv[:2] == ("bash", "-lc")
    assert "printf '%s'" in operation.argv[2]
    assert "/tmp/echo-test.json" in operation.argv[2]
    assert "fn apply -f /tmp/echo-test.json" in operation.argv[2]
    assert "NANOFAAS_ENDPOINT" not in operation.env


def test_cli_stack_components_use_gradle_install_dist_binary(tmp_path) -> None:
    context = CliComponentContext(
        repo_root=tmp_path,
        release="test-release",
        namespace="test-ns",
        local_registry="registry.internal:5000",
    )

    operation = cli_components.plan_platform_install(context)[0]
    command = " ".join(operation.argv)

    assert "/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas-cli" in command
    assert "/nanofaas-cli/building/install/" not in command


def test_container_local_runner_emits_balanced_top_level_phase_events_and_verify_children(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fake_sink,
) -> None:
    monkeypatch.setattr(container_local_runner_mod, "_resolve_scenario_file", lambda scenario_file: None)
    monkeypatch.setattr(container_local_runner_mod, "select_container_runtime", lambda runtime_adapter: "docker")

    runner = ContainerLocalE2eRunner(tmp_path)
    monkeypatch.setattr(
        runner,
        "_resolve_function",
        lambda resolved: ("container-local-e2e", "nanofaas/function-runtime:test", None, None, None),
    )
    monkeypatch.setattr(runner, "_build_artifacts", lambda: None)
    monkeypatch.setattr(runner, "_build_function_image", lambda image, runtime_kind, family: None)
    monkeypatch.setattr(container_local_runner_mod, "spawn_logged_process", lambda *args, **kwargs: _FakeProcess())
    monkeypatch.setattr(container_local_runner_mod, "wait_for_http_ok", lambda *args, **kwargs: True)
    monkeypatch.setattr(runner, "_wait_for_containers", lambda *args, **kwargs: None)
    monkeypatch.setattr(runner, "_cleanup", lambda *args, **kwargs: None)

    def _fake_subprocess_run(args: list[str], check: bool = False, **kwargs) -> subprocess.CompletedProcess[str]:
        _ = check, kwargs
        if "-o" in args:
            output_path = Path(args[args.index("-o") + 1])
            payload = {}
            if output_path.name == "register.json":
                payload = {
                    "name": "container-local-e2e",
                    "effectiveExecutionMode": "DEPLOYMENT",
                    "deploymentBackend": "container-local",
                    "endpointUrl": "http://127.0.0.1:19090/invoke",
                }
            elif output_path.name == "scale.json":
                payload = {"replicas": 2}
            elif output_path.name in {"invoke.json", "invoke-scaled.json"}:
                payload = {"status": "success"}
            output_path.write_text(json.dumps(payload), encoding="utf-8")
        return subprocess.CompletedProcess(args, 0)

    monkeypatch.setattr(container_local_runner_mod.subprocess, "run", _fake_subprocess_run)

    import httpx

    monkeypatch.setattr(
        httpx,
        "get",
        lambda url, timeout=5: type("_Resp", (), {"status_code": 404})(),
    )

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.container_local", task_id="container-local.run_flow")
    ):
        runner.run()

    _assert_balanced_phase(
        fake_sink,
        task_id="container-local.building",
        parent_task_id="container-local.run_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="container-local.deploy",
        parent_task_id="container-local.run_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="container-local.verify",
        parent_task_id="container-local.run_flow",
    )
    assert [event.kind for event in _task_events(fake_sink, "container-local.verify.scale")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "container-local.verify.scale")
    } == {"container-local.verify"}
    assert [event.kind for event in _task_events(fake_sink, "container-local.verify.cleanup")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "container-local.verify.cleanup")
    } == {"container-local.verify"}


def test_container_local_runner_builds_javascript_function_images(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = ContainerLocalE2eRunner(tmp_path)
    called: dict[str, list[str]] = {}

    def fake_run(command: list[str], check: bool = True) -> ShellExecutionResult:
        _ = check
        called["command"] = command
        return ShellExecutionResult(command=command, return_code=0)

    monkeypatch.setattr(runner, "_run", fake_run)

    runner._build_function_image("example/image:tag", "javascript", "word-stats")

    assert called["command"] == [
        "docker",
        "build",
        "-t",
        "example/image:tag",
        "-f",
        "examples/javascript/word-stats/Dockerfile",
        ".",
    ]


def test_container_local_runner_run_uses_explicit_resolved_scenario(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    class StopRun(RuntimeError):
        pass

    resolved = ResolvedScenario(
        name="selected-container-local",
        base_scenario="container-local",
        runtime="java",
        functions=[_rf("word-stats-javascript", runtime="javascript")],
        function_keys=["word-stats-javascript"],
    )
    runner = ContainerLocalE2eRunner(tmp_path)
    captured: dict[str, object] = {}

    monkeypatch.setattr(
        container_local_runner_mod,
        "_resolve_scenario_file",
        lambda scenario_file: (_ for _ in ()).throw(
            AssertionError("scenario file resolution should be skipped when resolved_scenario is provided")
        ),
    )

    def fake_resolve_function(incoming):  # noqa: ANN001
        captured["resolved"] = incoming
        raise StopRun()

    monkeypatch.setattr(runner, "_resolve_function", fake_resolve_function)

    with pytest.raises(StopRun):
        runner.run(resolved_scenario=resolved)

    assert captured["resolved"] is resolved


def test_deploy_host_runner_emits_balanced_top_level_phase_events_and_verify_children(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fake_sink,
) -> None:
    monkeypatch.setattr(deploy_host_runner_mod, "_resolve_scenario_file", lambda scenario_file: None)
    monkeypatch.setattr(deploy_host_runner_mod, "select_container_runtime", lambda: "docker")
    monkeypatch.setattr(deploy_host_runner_mod, "wait_for_http_any_status", lambda *args, **kwargs: True)

    class _FakeControlPlane:
        def __init__(self, port: int, request_body_path: Path) -> None:
            self.port = port
            self.request_body_path = request_body_path

        def start(self, work_dir: Path) -> None:
            _ = work_dir

        def stop(self) -> None:
            return None

    monkeypatch.setattr(deploy_host_runner_mod, "FakeControlPlane", _FakeControlPlane)

    runner = DeployHostE2eRunner(tmp_path)
    monkeypatch.setattr(runner, "_resolve_functions", lambda resolved: [("deploy-e2e", None)])
    monkeypatch.setattr(runner, "_build_cli", lambda *, skip=False: None)
    monkeypatch.setattr(runner, "_start_registry", lambda container_name, docker="docker": None)
    monkeypatch.setattr(runner, "_stop_registry", lambda container_name, docker="docker": None)
    monkeypatch.setattr(
        runner,
        "_write_function_yaml",
        lambda work_dir, function_name, image_repo, tag, example_dir: work_dir / "function.yaml",
    )
    monkeypatch.setattr(runner, "_run", lambda command, check=True: ShellExecutionResult(command=command, return_code=0))
    monkeypatch.setattr(runner, "_verify_registry_push", lambda image_repo, tag: None)
    monkeypatch.setattr(runner, "_verify_register_request", lambda request_body_path, function_name, image_repo, tag: None)

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.deploy_host", task_id="deploy-host.run_flow")
    ):
        runner.run()

    _assert_balanced_phase(
        fake_sink,
        task_id="deploy-host.building",
        parent_task_id="deploy-host.run_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="deploy-host.deploy",
        parent_task_id="deploy-host.run_flow",
    )
    _assert_balanced_phase(
        fake_sink,
        task_id="deploy-host.verify",
        parent_task_id="deploy-host.run_flow",
    )
    assert [event.kind for event in _task_events(fake_sink, "deploy-host.verify.deploy-e2e")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "deploy-host.verify.deploy-e2e")
    } == {"deploy-host.verify"}


def test_deploy_host_runner_run_uses_explicit_resolved_scenario(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario

    class StopRun(RuntimeError):
        pass

    resolved = ResolvedScenario(
        name="selected-deploy-host",
        base_scenario="deploy-host",
        runtime="java",
        functions=[
            _rf("word-stats-javascript", runtime="javascript"),
            _rf("json-transform-javascript", runtime="javascript"),
        ],
        function_keys=["word-stats-javascript", "json-transform-javascript"],
    )
    runner = DeployHostE2eRunner(tmp_path)
    captured: dict[str, object] = {}

    monkeypatch.setattr(
        deploy_host_runner_mod,
        "_resolve_scenario_file",
        lambda scenario_file: (_ for _ in ()).throw(
            AssertionError("scenario file resolution should be skipped when resolved_scenario is provided")
        ),
    )

    def fake_resolve_functions(incoming):  # noqa: ANN001
        captured["resolved"] = incoming
        raise StopRun()

    monkeypatch.setattr(runner, "_resolve_functions", fake_resolve_functions)

    with pytest.raises(StopRun):
        runner.run(resolved_scenario=resolved)

    assert captured["resolved"] is resolved


def test_cli_stack_runner_emits_explicit_verify_parent_context_for_planned_steps(
    monkeypatch: pytest.MonkeyPatch,
    fake_sink,
) -> None:
    monkeypatch.setattr(cli_stack_runner_mod, "_resolve_scenario", lambda scenario_file: None)

    runner = cli_stack_runner_mod.CliStackRunner(
        repo_root=Path("/repo"),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    monkeypatch.setattr(
        runner,
        "plan_steps",
        lambda resolved_scenario=None: [
            cli_stack_runner_mod.ScenarioPlanStep(
                summary="Install platform",
                command=["echo", "install"],
                env={},
                step_id="cli.platform_install",
            ),
            cli_stack_runner_mod.ScenarioPlanStep(
                summary="Run platform status",
                command=["echo", "status"],
                env={},
                step_id="cli.platform_status",
            ),
        ],
    )

    class _Shell:
        def run(self, command, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            return ShellExecutionResult(command=list(command), return_code=0, env=env or {})

    runner._shell = _Shell()

    with bind_workflow_sink(fake_sink), bind_workflow_context(
        WorkflowContext(flow_id="e2e.cli_stack", task_id="cli_stack.vm_e2e_flow")
    ):
        runner.run()

    _assert_balanced_phase(
        fake_sink,
        task_id="cli-stack.verify",
        parent_task_id="cli_stack.vm_e2e_flow",
    )
    assert [event.kind for event in _task_events(fake_sink, "cli.platform_install")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "cli.platform_install")
    } == {"cli-stack.verify"}
    assert [event.kind for event in _task_events(fake_sink, "cli.platform_status")] == [
        "task.running",
        "task.completed",
    ]
    assert {
        event.parent_task_id for event in _task_events(fake_sink, "cli.platform_status")
    } == {"cli-stack.verify"}
