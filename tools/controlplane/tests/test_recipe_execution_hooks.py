from __future__ import annotations

import pytest
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _make_k6_result(fn: str):
    from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result
    return TwoVmK6Result(
        run_dir=Path("/tmp"),
        k6_summary_path=Path(f"/tmp/{fn}.json"),
        target_function=fn,
        started_at=_utcnow(),
        ended_at=_utcnow(),
    )


def test_on_loadgen_run_k6_calls_matrix_not_single() -> None:
    """Regression: RunK6Matrix must iterate ALL targets, not just [0]."""
    from controlplane_tool.scenario.tasks.loadtest import RunK6Matrix

    fn_keys = ["word-stats-java", "json-transform-java", "word-stats-python"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    task = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    result = task.run()

    assert runner.run_k6_for_function.call_count == 3
    called_fns = [c.args[1] for c in runner.run_k6_for_function.call_args_list]
    assert called_fns == fn_keys
    assert len(result.results) == 3


def test_run_k6_matrix_first_result_available_for_prometheus() -> None:
    """_on_prometheus_snapshot uses first result — must not fail when matrix has multiple."""
    from controlplane_tool.scenario.tasks.loadtest import RunK6Matrix

    fn_keys = ["word-stats-java", "json-transform-java"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    matrix_result = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    ).run()

    assert matrix_result.results[0].target_function == "word-stats-java"
    assert matrix_result.window is not None


def test_register_functions_spec_built_from_resolved_scenario() -> None:
    """FunctionSpec list must include name + image for each selected function."""
    from controlplane_tool.scenario.tasks.functions import FunctionSpec
    from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions

    resolved = MagicMock()
    resolved.functions = [
        MagicMock(key="word-stats-java", image="registry/word-stats-java:e2e"),
        MagicMock(key="json-transform-java", image="registry/json-transform-java:e2e"),
    ]
    local_registry = "localhost:5000"
    runtime_image_default = f"{local_registry}/nanofaas/function-runtime:e2e"

    specs = [
        FunctionSpec(
            name=fn_key,
            image=function_image(fn_key, resolved, runtime_image_default),
        )
        for fn_key in selected_functions(resolved)
    ]

    assert len(specs) == 2
    assert specs[0].name == "word-stats-java"
    assert specs[0].image == "registry/word-stats-java:e2e"
    assert specs[1].name == "json-transform-java"
    assert specs[1].image == "registry/json-transform-java:e2e"
    assert specs[0].execution_mode == "DEPLOYMENT"
    assert specs[0].timeout_ms == 5000


def test_register_functions_step_has_correct_step_id() -> None:
    """Replacement step for cli.fn_apply_selected must have step_id=functions.register."""
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    def _stub_action() -> None:
        pass

    step = ScenarioPlanStep(
        summary="Register selected functions via REST API",
        command=["python", "-c", "# RegisterFunctions via REST"],
        step_id="functions.register",
        action=_stub_action,
    )
    assert step.step_id == "functions.register"
    assert step.action is not None


def test_scenario_plan_protocol_is_satisfied_by_existing_dataclass() -> None:
    """Existing ScenarioPlan dataclass must satisfy the new ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol
    from controlplane_tool.e2e.e2e_runner import E2ePlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo", "x"], step_id="test.step")
    plan = E2ePlan(
        scenario=MagicMock(),
        request=MagicMock(),
        steps=[step],
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["test.step"]


def test_scenario_plan_task_ids_skips_empty_step_ids() -> None:
    """Steps without step_id are excluded from task_ids."""
    from controlplane_tool.e2e.e2e_runner import E2ePlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    steps = [
        ScenarioPlanStep(summary="a", command=["echo"], step_id="a.step"),
        ScenarioPlanStep(summary="b", command=["echo"], step_id=""),
        ScenarioPlanStep(summary="c", command=["echo"], step_id="c.step"),
    ]
    plan = E2ePlan(scenario=MagicMock(), request=MagicMock(), steps=steps)
    assert plan.task_ids == ["a.step", "c.step"]


def test_control_plane_nodeports_enabled_for_all_vm_loadtest_scenarios() -> None:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.core.models import ScenarioName
    from workflow_tasks.vm.models import VmLifecycle

    scenarios: tuple[tuple[ScenarioName, VmLifecycle], ...] = (
        ("two-vm-loadtest", "multipass"),
        ("azure-vm-loadtest", "azure"),
        ("proxmox-vm-loadtest", "proxmox"),
    )

    for scenario_name, lifecycle in scenarios:
        steps = plan_recipe_steps(
            Path("/repo"),
            E2eRequest(
                scenario=scenario_name,
                runtime="java",
                vm=VmRequest(lifecycle=lifecycle, name=f"{scenario_name}-stack"),
                loadgen_vm=VmRequest(lifecycle=lifecycle, name=f"{scenario_name}-loadgen"),
            ),
            scenario_name,
            component_ids=("helm.deploy_control_plane",),
        )

        assert len(steps) == 1
        command = " ".join(steps[0].command)
        assert "controlPlane.service.type=NodePort" in command
        assert "prometheus.service.type=NodePort" in command


def test_plan_recipe_steps_uses_proxmox_provider_for_proxmox_lifecycle(monkeypatch) -> None:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    calls: list[str] = []

    class FakeProxmox:
        def __init__(self, repo_root):
            calls.append(str(repo_root))

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            calls.append(f"ensure:{request.name}")

        def teardown(self, request):
            calls.append(f"teardown:{request.name}")

        def exec_argv(self, request, argv, env=None, cwd=None):
            calls.append(f"exec:{request.name}:{argv[0]}")
            return SimpleNamespace(return_code=0, stdout="", stderr="")

        def connection_host(self, request):
            return "10.0.2.10"

        def ssh_endpoint(self, request):
            return ("149.132.176.73", 20001)

        def ssh_private_key_path(self, request):
            return Path("/tmp/id_ed25519")

        def wait_for_ssh(self, request):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )

    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        component_ids=("vm.ensure_running", "images.build_core"),
    )

    assert steps[0].action is not None
    steps[0].action()

    assert calls[0] == "/repo"
    assert "ensure:stack" in calls


def test_proxmox_register_functions_uses_published_control_plane_endpoint(
    monkeypatch,
) -> None:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest
    from controlplane_tool.scenario.two_vm_loadtest_config import (
        TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
    )

    captured: dict[str, str] = {}

    class FakeProxmox:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def connection_host(self, request):
            return "10.0.2.10"

        def publish_port(self, request, *, service, guest_port):
            assert service == "CONTROL_PLANE_HTTP"
            assert guest_port == TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT
            return ("149.132.176.73", 30080)

    class FakeRegisterFunctions:
        def __init__(self, *, task_id, title, control_plane_url, specs):
            captured["control_plane_url"] = control_plane_url

        def run(self):
            pass

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )
    monkeypatch.setattr(
        "controlplane_tool.e2e.e2e_runner.RegisterFunctions",
        FakeRegisterFunctions,
    )

    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        component_ids=("cli.fn_apply_selected",),
    )

    assert steps[0].action is not None
    steps[0].action()

    assert captured["control_plane_url"] == "http://149.132.176.73:30080"


def test_plan_recipe_steps_rewrites_proxmox_repo_sync_and_ansible_commands(
    monkeypatch,
) -> None:
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    class FakeProxmox:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            pass

        def teardown(self, request):
            pass

        def exec_argv(self, request, argv, env=None, cwd=None):
            return SimpleNamespace(return_code=0, stdout="", stderr="")

        def connection_host(self, request):
            return "10.0.2.10"

        def ssh_endpoint(self, request):
            return ("149.132.176.73", 20001)

        def ssh_private_key_path(self, request):
            return Path("/tmp/id_ed25519")

        def wait_for_ssh(self, request):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )
    monkeypatch.setattr(
        "workflow_tasks.components.bootstrap._find_ssh_private_key_path",
        lambda _: None,
    )

    shell = RecordingShell()
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        shell=shell,
        component_ids=("repo.sync_to_vm", "vm.provision_base"),
    )
    for step in steps:
        assert step.action is not None
        step.action()

    rsync_command = shell.commands[0]
    ansible_command = shell.commands[1]

    assert "ubuntu@149.132.176.73:/home/ubuntu/nanofaas/" in rsync_command
    assert "-p 20001" in rsync_command[rsync_command.index("-e") + 1]
    assert ansible_command[ansible_command.index("-i") + 1] == "149.132.176.73,"
    assert "-e" in ansible_command
    assert "ansible_port=20001" in ansible_command
    assert "--private-key" in ansible_command
    assert ansible_command[ansible_command.index("--private-key") + 1] == "/tmp/id_ed25519"


def test_plan_recipe_steps_defers_proxmox_ssh_endpoint_until_actions(
    monkeypatch,
) -> None:
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    calls: list[str] = []

    class FakeProxmox:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            pass

        def teardown(self, request):
            pass

        def exec_argv(self, request, argv, env=None, cwd=None):
            return SimpleNamespace(return_code=0, stdout="", stderr="")

        def connection_host(self, request):
            return "10.0.2.10"

        def ssh_endpoint(self, request):
            calls.append(f"ssh_endpoint:{request.name}")
            return ("149.132.176.73", 20001)

        def ssh_private_key_path(self, request):
            return Path("/tmp/id_ed25519")

        def wait_for_ssh(self, request):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )
    monkeypatch.setattr(
        "workflow_tasks.components.bootstrap._find_ssh_private_key_path",
        lambda _: None,
    )

    shell = RecordingShell()
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        shell=shell,
        component_ids=("repo.sync_to_vm", "vm.provision_base"),
    )

    assert calls == []

    assert steps[0].action is not None
    steps[0].action()
    assert calls == ["ssh_endpoint:stack"]

    assert steps[1].action is not None
    steps[1].action()
    assert calls == ["ssh_endpoint:stack", "ssh_endpoint:stack"]


def test_plan_recipe_steps_replaces_existing_proxmox_ansible_private_key(
    monkeypatch,
) -> None:
    from controlplane_tool.core.shell_backend import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    class FakeProxmox:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            pass

        def teardown(self, request):
            pass

        def exec_argv(self, request, argv, env=None, cwd=None):
            return SimpleNamespace(return_code=0, stdout="", stderr="")

        def connection_host(self, request):
            return "10.0.2.10"

        def ssh_endpoint(self, request):
            return ("149.132.176.73", 20001)

        def ssh_private_key_path(self, request):
            return Path("/tmp/proxmox_key")

        def wait_for_ssh(self, request):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )
    monkeypatch.setattr(
        "workflow_tasks.components.bootstrap._find_ssh_private_key_path",
        lambda _: Path("/old/key"),
    )

    shell = RecordingShell()
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )

    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        shell=shell,
        component_ids=("vm.provision_base",),
    )
    assert steps[0].action is not None
    steps[0].action()

    ansible_command = shell.commands[0]

    assert "--private-key" in ansible_command
    assert ansible_command[ansible_command.index("--private-key") + 1] == "/tmp/proxmox_key"
    assert "/old/key" not in ansible_command


def test_proxmox_host_command_failure_reports_stdout_and_stderr(monkeypatch) -> None:
    from controlplane_tool.core.shell_backend import ShellBackend, ShellExecutionResult
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.infra.vm.vm_models import VmRequest

    class FailingShell(ShellBackend):
        def __init__(self):
            self.commands: list[list[str]] = []

        def run(self, command, *, cwd=None, env=None, dry_run=False):
            self.commands.append(command)
            return ShellExecutionResult(
                command=command,
                return_code=2,
                stdout="PLAY RECAP\nnanofaas : failed=1\n",
                stderr="[WARNING]: Module remote_tmp /root/.ansible/tmp did not exist\n",
                dry_run=dry_run,
                env=dict(env or {}),
            )

    class FakeProxmox:
        def __init__(self, repo_root):
            pass

        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

        def ensure_running(self, request):
            pass

        def teardown(self, request):
            pass

        def exec_argv(self, request, argv, env=None, cwd=None):
            return SimpleNamespace(return_code=0, stdout="", stderr="")

        def connection_host(self, request):
            return "10.0.2.10"

        def ssh_endpoint(self, request):
            return ("149.132.176.73", 20001)

        def ssh_private_key_path(self, request):
            return Path("/tmp/id_ed25519")

        def wait_for_ssh(self, request):
            return None

    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        FakeProxmox,
    )
    monkeypatch.setattr(
        "workflow_tasks.components.bootstrap._find_ssh_private_key_path",
        lambda _: None,
    )

    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="loadgen"),
    )
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "proxmox-vm-loadtest",
        shell=FailingShell(),
        component_ids=("vm.provision_base",),
    )

    assert steps[0].action is not None
    with pytest.raises(RuntimeError) as exc_info:
        steps[0].action()

    detail = str(exc_info.value)
    assert "PLAY RECAP" in detail
    assert "failed=1" in detail
    assert "remote_tmp" in detail
