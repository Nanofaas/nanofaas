from pathlib import Path

import pytest

from workflow_tasks import bind_workflow_sink, workflow_log
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, E2ePlan, ScenarioPlanStep
from controlplane_tool.scenario.scenario_loader import load_scenario_file
from controlplane_tool.scenario.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario.components.composer import compose_recipe
from controlplane_tool.scenario.components import operation_to_plan_step
from controlplane_tool.scenario.components import RemoteCommandOperation
from controlplane_tool.scenario.components.recipes import build_scenario_recipe
from controlplane_tool.scenario.scenario_models import ScenarioSpec
from controlplane_tool.core.shell_backend import RecordingShell, ScriptedShell, ShellBackend, ShellExecutionResult
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.infra.vm.vm_cluster_workflows import build_vm_cluster_prelude_plan
from workflow_tasks.tasks.models import CommandTaskSpec


def test_dry_run_plan_describes_vm_backed_scenario_steps() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert plan.scenario.name == "k3s-junit-curl"
    assert any("ensure vm" in step.summary.lower() for step in plan.steps)
    assert any(step.summary == "Ensure registry container" for step in plan.steps)
    assert any(step.summary == "Configure k3s registry" for step in plan.steps)
    assert any(step.summary == "Deploy control-plane via Helm" for step in plan.steps)
    assert all(step.step_id for step in plan.steps)


def test_select_scenarios_applies_only_and_skip_filters() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plans = runner.plan_all(only=["k3s-junit-curl", "docker"], skip=["docker"])

    assert [plan.scenario.name for plan in plans] == ["k3s-junit-curl"]


def test_e2e_all_vm_plan_bootstraps_shared_vm_once() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(only=["k3s-junit-curl"])

    ensure_steps = [
        step for scenario in plans for step in scenario.steps if "Ensure VM is running" == step.summary
    ]
    assert len(ensure_steps) == 1


def test_container_local_plan_no_longer_routes_to_shell_backend() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="container-local", runtime="java")
    )

    assert not any(
        "e2e-container-local-backend.sh" in " ".join(step.command)
        for step in plan.steps
    )
    assert all(step.step_id for step in plan.steps)


def test_deploy_host_plan_no_longer_routes_to_shell_backend() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="deploy-host", runtime="java")
    )

    assert not any(
        "e2e-deploy-host-backend.sh" in " ".join(step.command)
        for step in plan.steps
    )
    assert all(step.step_id for step in plan.steps)


def test_k3s_junit_curl_plan_uses_unified_python_and_junit_steps() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert not any("e2e-k3s-curl-backend.sh" in command for command in rendered)
    assert any("K8sE2eTest" in command for command in rendered)
    # The honest Workflow runs the k3s-curl verification in-process (a CallableTask)
    # rather than shelling out to controlplane_tool.e2e.k3s_curl_runner, so its
    # display step has no subprocess command; assert the step is present by id.
    assert "tests.run_k3s_curl_checks" in [step.step_id for step in plan.steps]
    assert all(step.step_id for step in plan.steps)


def test_helm_stack_plan_no_longer_routes_to_shell_backend() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="helm-stack",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert not any("e2e-helm-stack-backend.sh" in command for command in rendered)
    assert all(step.step_id for step in plan.steps)


def test_helm_stack_plan_shares_k3s_junit_curl_prelude() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    vm_request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    k3s_plan = runner.plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=vm_request,
        )
    )
    helm_stack_plan = runner.plan(
        E2eRequest(
            scenario="helm-stack",
            runtime="java",
            vm=vm_request,
        )
    )

    k3s_recipe_ids = [component.component_id for component in compose_recipe(build_scenario_recipe("k3s-junit-curl"))]
    helm_recipe_ids = [component.component_id for component in compose_recipe(build_scenario_recipe("helm-stack"))]
    shared_prefix_length = 0
    for lhs, rhs in zip(k3s_recipe_ids, helm_recipe_ids):
        if lhs != rhs:
            break
        shared_prefix_length += 1

    assert k3s_recipe_ids[:shared_prefix_length] == helm_recipe_ids[:shared_prefix_length]
    assert helm_recipe_ids[shared_prefix_length:] == [
        "loadtest.install_k6",
        "loadtest.run",
        "experiments.autoscaling",
    ]
    shared_step_prefix = 0
    for lhs, rhs in zip(k3s_plan.steps, helm_stack_plan.steps):
        if lhs.summary != rhs.summary:
            break
        shared_step_prefix += 1

    assert [step.summary for step in helm_stack_plan.steps[:shared_step_prefix]] == [
        step.summary for step in k3s_plan.steps[:shared_step_prefix]
    ]
    assert [step.summary for step in helm_stack_plan.steps[shared_step_prefix:]] == [
        "Install k6 for load testing",
        "Run k6 loadtest via controlplane runner",
        "Run autoscaling experiment (Python)",
    ]


def test_vm_cluster_prelude_plan_keeps_shared_image_and_helm_values() -> None:
    resolved_scenario = load_scenario_file(
        Path("tools/controlplane/scenarios/k8s-demo-java.toml")
    )
    vm = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    prelude = build_vm_cluster_prelude_plan(
        vm=vm,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        namespace="nanofaas-e2e",
        local_registry="localhost:5000",
        runtime="java",
        resolved_scenario=resolved_scenario,
    )

    assert "localhost:5000/nanofaas/control-plane:e2e" in prelude.build_core_script
    assert "localhost:5000/nanofaas/function-runtime:e2e" in prelude.build_core_script
    assert "functionRuntime.image.repository" in prelude.deploy_function_runtime_script


def test_vm_cluster_prelude_plan_uses_k3s_component_planners() -> None:
    resolved_scenario = load_scenario_file(
        Path("tools/controlplane/scenarios/k8s-demo-java.toml")
    )
    vm = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    prelude = build_vm_cluster_prelude_plan(
        vm=vm,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        namespace="nanofaas-e2e",
        local_registry="localhost:5000",
        runtime="java",
        resolved_scenario=resolved_scenario,
    )

    assert prelude.install_k3s.command[0] == "ansible-playbook"
    assert "provision-k3s.yml" in prelude.install_k3s.command[-1]
    assert prelude.configure_registry.command[0] == "ansible-playbook"
    assert "configure-k3s-registry.yml" in prelude.configure_registry.command[-1]
    assert "helm/nanofaas-namespace" in prelude.install_namespace_script
    assert "namespace.name=nanofaas-e2e" in prelude.install_namespace_script


def test_helm_stack_plan_adds_structured_loadtest_tail() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="helm-stack",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert [step.summary for step in plan.steps[-2:]] == [
        "Run k6 loadtest via controlplane runner",
        "Run autoscaling experiment (Python)",
    ]
    assert [step.summary for step in plan.steps[-3:]] == [
        "Install k6 for load testing",
        "Run k6 loadtest via controlplane runner",
        "Run autoscaling experiment (Python)",
    ]
    assert all(step.step_id for step in plan.steps)


def test_two_vm_loadtest_plan_adds_default_loadgen_vm_for_action() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plan = runner.plan(
        E2eRequest(
            scenario="two-vm-loadtest",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert plan.request.loadgen_vm is not None
    assert plan.request.loadgen_vm.name == "nanofaas-e2e-loadgen"


def test_e2e_all_two_vm_loadtest_plan_adds_default_loadgen_vm() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(only=["two-vm-loadtest"])

    assert len(plans) == 1
    request = plans[0].request
    assert request.vm is not None
    assert request.loadgen_vm is not None
    assert request.loadgen_vm.lifecycle == request.vm.lifecycle
    assert request.loadgen_vm.name == "nanofaas-e2e-loadgen"
    assert request.loadgen_vm.user == request.vm.user
    assert request.loadgen_vm.home == request.vm.home
    assert request.loadgen_vm.cpus == 2
    assert request.loadgen_vm.memory == "2G"
    assert request.loadgen_vm.disk == "10G"


def test_e2e_all_two_vm_loadtest_plan_derives_external_loadgen_vm() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(
        only=["two-vm-loadtest"],
        vm_request=VmRequest(
            lifecycle="external",
            host="stack.example",
            user="dev",
            home="/srv/dev",
        ),
    )

    assert len(plans) == 1
    loadgen_vm = plans[0].request.loadgen_vm
    assert loadgen_vm is not None
    assert loadgen_vm.lifecycle == "external"
    assert loadgen_vm.host == "stack.example"
    assert loadgen_vm.user == "dev"
    assert loadgen_vm.home == "/srv/dev"


def test_e2e_all_two_vm_loadtest_plan_accepts_explicit_loadgen_vm() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    loadgen_vm = VmRequest(
        lifecycle="multipass",
        name="custom-loadgen",
        cpus=3,
        memory="4G",
        disk="20G",
    )

    plans = runner.plan_all(
        only=["two-vm-loadtest"],
        loadgen_vm_request=loadgen_vm,
    )

    assert len(plans) == 1
    assert plans[0].request.loadgen_vm == loadgen_vm


def test_e2e_all_non_two_vm_plan_does_not_add_loadgen_vm() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(only=["k3s-junit-curl"])

    assert len(plans) == 1
    assert plans[0].request.loadgen_vm is None


def test_cli_stack_plan_defaults_to_isolated_namespace_for_all_recipe_steps() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="cli-stack",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
            namespace=None,
        )
    )

    rendered = [
        " ".join(step.command)
        for step in plan.steps
        if any(
            token in " ".join(step.command)
            for token in (
                "platform install",
                "platform status",
                "helm uninstall",
            )
        )
    ]

    assert rendered
    assert all("nanofaas-cli-stack-e2e" in command for command in rendered)


def test_buildpack_plan_assigns_step_ids_to_all_executable_steps() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="buildpack", runtime="java")
    )

    assert all(step.step_id for step in plan.steps)


def test_helm_stack_execute_resolves_vm_host_for_autoscaling_env() -> None:
    class CapturingShell(ShellBackend):
        def __init__(self) -> None:
            self.calls: list[tuple[list[str], dict[str, str]]] = []

        def run(self, command, *, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            self.calls.append((list(command), dict(env or {})))
            return ShellExecutionResult(command=list(command), return_code=0, env=dict(env or {}), dry_run=dry_run)

    shell = CapturingShell()
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda _: "10.0.0.1",
    )
    plan = runner.plan(
        E2eRequest(
            scenario="helm-stack",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    # _execute_steps is the legacy recipe-step engine still used to resolve
    # <multipass-ip:NAME> placeholders for VM-backed display steps; helm-stack's
    # own run() drives a Workflow with a real VM, so exercise the step engine
    # directly to assert host resolution into the autoscaling env.
    runner._execute_steps(plan)

    autoscaling_call = next(
        env for command, env in shell.calls if "experiments/autoscaling.py" in " ".join(command)
    )
    assert autoscaling_call["NAMESPACE"] == "nanofaas-e2e"
    assert autoscaling_call["E2E_VM_HOST"] == "10.0.0.1"
    assert autoscaling_call["E2E_PUBLIC_HOST"] == "10.0.0.1"


def test_run_all_bootstraps_vm_once_and_reuses_it() -> None:
    from unittest.mock import patch
    from controlplane_tool.e2e.k3s_curl_runner import K3sCurlRunner

    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell, host_resolver=lambda _: "10.0.0.1")

    with patch.object(K3sCurlRunner, "verify_existing_stack", return_value=None):
        runner.run_all(only=["k3s-junit-curl"], runtime="java")

    launches = [command for command in shell.commands if command[:2] == ["multipass", "launch"]]
    assert len(launches) <= 1


def test_run_all_tears_down_vm_when_cleanup_vm_true() -> None:
    import json
    from multipass import FakeBackend, MultipassClient
    from multipass._backend import CommandResult

    name = "nanofaas-e2e"
    info_payload = json.dumps({
        "info": {
            name: {
                "state": "Running",
                "ipv4": ["10.0.0.1"],
                "image_release": "24.04",
                "image_hash": "",
                "cpu_count": 1,
                "memory": {},
                "disks": {},
                "mounts": {},
            }
        }
    })
    backend = FakeBackend({
        ("multipass", "info", name, "--format", "json"): CommandResult(
            args=[], returncode=0, stdout=info_payload, stderr=""
        ),
    })
    backend.set_default(CommandResult(args=[], returncode=0, stdout="", stderr=""))

    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
        multipass_client=MultipassClient(backend=backend),
    )

    from unittest.mock import patch
    from controlplane_tool.e2e.k3s_curl_runner import K3sCurlRunner

    with patch.object(K3sCurlRunner, "verify_existing_stack", return_value=None):
        runner.run_all(only=["k3s-junit-curl"], runtime="java")

    assert any("delete" in call for call in backend.calls)


def test_plan_tracks_resolved_scenario_selection(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            function_preset="demo-java",
            resolved_scenario=load_scenario_file(
                Path("tools/controlplane/scenarios/k8s-demo-java.toml")
            ),
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert plan.request.resolved_scenario is not None
    assert plan.request.resolved_scenario.function_keys == [
        "word-stats-java",
        "json-transform-java",
    ]


def test_runner_writes_manifest_and_exports_it_to_backend(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    resolved = resolve_scenario_spec(
        ScenarioSpec(
            name="k3s-demo-java",
            base_scenario="k3s-junit-curl",
            runtime="java",
            function_preset="demo-java",
        )
    )

    plan = runner.plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            function_preset="demo-java",
            resolved_scenario=resolved,
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    manifest_files = list(tmp_path.glob("*.json"))
    assert manifest_files
    rendered = [" ".join(step.command) for step in plan.steps]
    assert any("nanofaas.e2e.scenarioManifest" in command for command in rendered)


def test_k3s_junit_curl_plan_exports_remote_manifest_to_test_command(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            function_preset="demo-java",
            resolved_scenario=load_scenario_file(
                Path("tools/controlplane/scenarios/k8s-demo-java.toml")
            ),
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert any("nanofaas.e2e.scenarioManifest" in command for command in rendered)


def test_k3s_junit_curl_plan_binds_user_kubeconfig_for_cluster_steps() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert any(
        step.summary == "Deploy control-plane via Helm"
        and step.env["KUBECONFIG"] == "/home/ubuntu/.kube/config"
        for step in plan.steps
    )
    assert any(step.env.get("KUBECONFIG") == "/home/ubuntu/.kube/config" for step in plan.steps)
    assert all(step.step_id for step in plan.steps)


def test_operation_to_plan_step_uses_operation_id_as_step_identity() -> None:
    request = E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    operation = RemoteCommandOperation(
        operation_id="tests.run_k3s_curl_checks",
        summary="Run k3s-junit-curl verification",
        argv=("python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack"),
    )

    step = operation_to_plan_step(operation, request=request, on_k3s_curl_verify=lambda: None)

    assert step.step_id == "tests.run_k3s_curl_checks"


def test_operation_to_plan_step_preserves_command_env_and_step_id_after_task_bridge(monkeypatch) -> None:
    operation = RemoteCommandOperation(
        operation_id="operation.step",
        summary="Operation step",
        argv=("echo", "operation"),
        env={"SOURCE": "operation"},
        execution_target="host",
    )
    request = E2eRequest(scenario="docker")
    monkeypatch.setattr(
        "controlplane_tool.scenario.components.executor.operation_to_task_spec",
        lambda _operation: CommandTaskSpec(
            task_id="task.step",
            summary="Task step",
            argv=("echo", "task"),
            env={"SOURCE": "task"},
        ),
    )

    step = operation_to_plan_step(operation, request=request)

    assert step.step_id == "task.step"
    assert step.summary == "Task step"
    assert step.command == ["echo", "task"]
    assert step.env == {"SOURCE": "task"}


def test_execute_binds_step_context_for_nested_workflow_events(fake_sink) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(
                summary="Run top-level verification",
                command=["echo", "noop"],
                env={},
                step_id="tests.run_k3s_curl_checks",
                action=lambda: workflow_log("nested progress"),
            )
        ],
    )

    with bind_workflow_sink(fake_sink):
        runner._execute_steps(plan)

    assert len(fake_sink.events) == 1
    assert fake_sink.events[0].flow_id == "docker"
    assert fake_sink.events[0].task_id == "tests.run_k3s_curl_checks"
    assert fake_sink.events[0].line == "nested progress"


def test_execute_rejects_step_without_id() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(
                summary="Broken step",
                command=["false"],
                step_id="",
            )
        ],
    )

    with pytest.raises(ValueError, match="step_id"):
        runner._execute_steps(plan)


def test_execute_emits_step_progress_events() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(summary="First step", command=["echo", "one"], step_id="docker.first"),
            ScenarioPlanStep(summary="Second step", command=["echo", "two"], step_id="docker.second"),
        ],
    )

    events = []

    runner._execute_steps(plan, event_listener=events.append)

    assert [(event.step_index, event.status, event.step.summary) for event in events] == [
        (1, "running", "First step"),
        (1, "success", "First step"),
        (2, "running", "Second step"),
        (2, "success", "Second step"),
    ]


def test_execute_emits_failure_event_when_step_fails() -> None:
    shell = ScriptedShell(
        return_code_map={("false",): 7},
        stderr_map={("false",): "kaboom"},
    )
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[ScenarioPlanStep(summary="Broken step", command=["false"], step_id="docker.broken")],
    )

    events = []

    try:
        runner._execute_steps(plan, event_listener=events.append)
    except RuntimeError as exc:
        assert "Broken step" in str(exc)
    else:
        raise AssertionError("expected runner.execute() to fail")

    assert [(event.step_index, event.status, event.step.summary) for event in events] == [
        (1, "running", "Broken step"),
        (1, "failed", "Broken step"),
    ]


def test_execute_runs_always_cleanup_steps_after_failure() -> None:
    shell = ScriptedShell(return_code_map={("false",): 7})
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)
    cleanup_calls: list[str] = []
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(summary="Broken step", command=["false"], step_id="docker.broken"),
            ScenarioPlanStep(
                summary="Cleanup step",
                command=["echo", "cleanup"],
                step_id="vm.down",
                action=lambda: cleanup_calls.append("vm.down"),
                always_run=True,
            ),
        ],
    )

    with pytest.raises(RuntimeError, match="Broken step"):
        runner._execute_steps(plan)

    assert cleanup_calls == ["vm.down"]


def test_execute_reports_main_and_cleanup_failures() -> None:
    shell = ScriptedShell(return_code_map={("false",): 7})
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)
    plan = E2ePlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(summary="Broken step", command=["false"], step_id="docker.broken"),
            ScenarioPlanStep(
                summary="Cleanup step",
                command=["echo", "cleanup"],
                step_id="vm.down",
                action=lambda: (_ for _ in ()).throw(RuntimeError("cleanup failed")),
                always_run=True,
            ),
        ],
    )

    with pytest.raises(RuntimeError) as excinfo:
        runner._execute_steps(plan)

    message = str(excinfo.value)
    assert "Broken step" in message
    assert "Cleanup failed:" in message
    assert "cleanup failed" in message


def test_plan_all_returns_typed_builder_for_two_vm_loadtest(tmp_path: Path) -> None:
    """plan_all() must return TwoVmLoadtestPlan for two-vm-loadtest, not generic ScenarioPlan."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["two-vm-loadtest"])

    assert len(plans) == 1
    assert isinstance(plans[0], TwoVmLoadtestPlan), (
        f"Expected TwoVmLoadtestPlan, got {type(plans[0])}"
    )
    assert "loadgen.run_k6" in plans[0].task_ids
    assert "vm.stack.ensure_running" in plans[0].task_ids


def test_plan_all_returns_typed_builder_for_k3s_junit_curl(tmp_path: Path) -> None:
    """plan_all() must return K3sJunitCurlPlan for k3s-junit-curl, not generic ScenarioPlan."""
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["k3s-junit-curl"])

    assert len(plans) == 1
    assert isinstance(plans[0], K3sJunitCurlPlan), (
        f"Expected K3sJunitCurlPlan, got {type(plans[0])}"
    )


def test_plan_all_returns_typed_builder_for_helm_stack(tmp_path: Path) -> None:
    """plan_all() must return HelmStackPlan for helm-stack."""
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["helm-stack"])

    assert len(plans) == 1
    assert isinstance(plans[0], HelmStackPlan), (
        f"Expected HelmStackPlan, got {type(plans[0])}"
    )


def test_plan_all_returns_typed_builder_for_cli_stack(tmp_path: Path) -> None:
    """plan_all() must return CliStackPlan for cli-stack."""
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli-stack"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliStackPlan), (
        f"Expected CliStackPlan, got {type(plans[0])}"
    )


def test_plan_all_returns_typed_builder_for_azure_vm_loadtest(tmp_path: Path) -> None:
    """plan_all() must return AzureVmLoadtestPlan when explicit azure credentials are provided."""
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan

    azure_request = VmRequest(
        lifecycle="azure",
        name="nanofaas-azure",
        azure_resource_group="my-rg",
        azure_location="westeurope",
    )
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["azure-vm-loadtest"], vm_request=azure_request)

    assert len(plans) == 1
    assert isinstance(plans[0], AzureVmLoadtestPlan), (
        f"Expected AzureVmLoadtestPlan, got {type(plans[0])}"
    )
    assert "loadgen.run_k6" in plans[0].task_ids
    assert "vm.stack.ensure_running" in plans[0].task_ids


def test_plan_all_skips_azure_vm_loadtest_without_credentials(tmp_path: Path) -> None:
    """plan_all() must skip azure-vm-loadtest when no vm_request is provided."""
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["azure-vm-loadtest"])

    assert len(plans) == 0


def test_plan_all_returns_typed_builder_for_proxmox_vm_loadtest(tmp_path: Path) -> None:
    """plan_all() must return ProxmoxVmLoadtestPlan when explicit proxmox credentials are provided."""
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import ProxmoxVmLoadtestPlan

    proxmox_request = VmRequest(
        lifecycle="proxmox",
        name="nanofaas-proxmox",
        proxmox_host="192.168.1.100",
        proxmox_node="pve",
        proxmox_password="secret",
    )
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["proxmox-vm-loadtest"], vm_request=proxmox_request)

    assert len(plans) == 1
    assert isinstance(plans[0], ProxmoxVmLoadtestPlan)
    assert "loadgen.run_k6" in plans[0].task_ids


def test_plan_all_skips_proxmox_vm_loadtest_without_credentials(tmp_path: Path) -> None:
    """plan_all() must skip proxmox-vm-loadtest when no vm_request is provided."""
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["proxmox-vm-loadtest"])

    assert len(plans) == 0


def test_plan_all_propagates_proxmox_credentials_to_loadgen_vm(tmp_path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm_request = VmRequest(
        lifecycle="proxmox",
        proxmox_host="pve.example.com",
        proxmox_node="venus",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=101,
        proxmox_ssh_key_path="/home/user/.ssh/id_rsa",
    )
    plans = runner.plan_all(only=["proxmox-vm-loadtest"], vm_request=vm_request)

    assert len(plans) == 1
    loadgen_vm = plans[0].request.loadgen_vm
    assert loadgen_vm.proxmox_host == "pve.example.com"
    assert loadgen_vm.proxmox_node == "venus"
    assert loadgen_vm.proxmox_user == "root@pam"
    assert loadgen_vm.proxmox_password == "secret"
    assert loadgen_vm.proxmox_template_id == 101
    assert loadgen_vm.proxmox_ssh_key_path == "/home/user/.ssh/id_rsa"


def test_e2e_runner_run_forwards_event_listener_to_builder_plan(tmp_path: Path) -> None:
    """E2eRunner.run() must forward event_listener when dispatching to a TwoVmLoadtestPlan."""
    from unittest.mock import patch, MagicMock
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan

    captured: dict = {}
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    listener = lambda event: None  # noqa: E731

    original_plan = runner.plan(request)

    def fake_plan(req):
        original_plan.run = MagicMock(
            side_effect=lambda event_listener=None: captured.update({"event_listener": event_listener})
        )
        return original_plan

    with patch.object(runner, "plan", side_effect=fake_plan):
        runner.run(request, event_listener=listener)

    assert captured.get("event_listener") is listener, (
        "event_listener was not forwarded — run() must call plan.run(event_listener=event_listener)"
    )


def test_run_all_dispatches_builder_plans_via_plan_run(tmp_path: Path) -> None:
    """run_all() must call plan.run() for builder plans, not _execute_steps directly."""
    from unittest.mock import patch
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan

    run_called: list[str] = []

    def capturing_run(self_plan, event_listener=None):  # noqa: ANN001
        run_called.append(type(self_plan).__name__)

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path, host_resolver=lambda _: "10.0.0.1")

    with patch.object(K3sJunitCurlPlan, "run", capturing_run):
        runner.run_all(only=["k3s-junit-curl"])

    assert "K3sJunitCurlPlan" in run_called, (
        "run_all() must call plan.run() for K3sJunitCurlPlan, not _execute_steps directly"
    )


def test_plan_and_plan_all_produce_consistent_step_ids_for_k3s(tmp_path: Path) -> None:
    """plan() and plan_all() must return the same step IDs for k3s-junit-curl.

    Before this fix, plan() used the recipe builder while plan_all() used
    _planner.vm_backed_steps() — two different implementations. After the fix,
    both use the same builder factory.
    """
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan_single = runner.plan(request)
    plans_all = runner.plan_all(only=["k3s-junit-curl"])

    assert len(plans_all) == 1
    assert plan_single.task_ids == plans_all[0].task_ids, (
        "plan() and plan_all() must produce the same step IDs for k3s-junit-curl"
    )


def test_plan_returns_typed_cli_vm_plan(tmp_path: Path) -> None:
    """plan() must return CliVmPlan for cli scenario."""
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(
        scenario="cli",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    ))

    assert isinstance(plan, CliVmPlan), f"Expected CliVmPlan, got {type(plan)}"
    assert "cli.vm_e2e_flow" in plan.task_ids


def test_plan_returns_typed_cli_host_plan(tmp_path: Path) -> None:
    """plan() must return CliHostPlan for cli-host scenario."""
    from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(
        scenario="cli-host",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    ))

    assert isinstance(plan, CliHostPlan), f"Expected CliHostPlan, got {type(plan)}"
    assert "cli.host_platform_flow" in plan.task_ids


def test_plan_all_returns_typed_cli_vm_plan(tmp_path: Path) -> None:
    """plan_all() must return CliVmPlan for cli scenario."""
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliVmPlan), f"Expected CliVmPlan, got {type(plans[0])}"


def test_plan_all_returns_typed_cli_host_plan(tmp_path: Path) -> None:
    """plan_all() must return CliHostPlan for cli-host scenario."""
    from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli-host"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliHostPlan), f"Expected CliHostPlan, got {type(plans[0])}"


def test_plan_and_plan_all_produce_consistent_step_ids_for_cli(tmp_path: Path) -> None:
    """plan() and plan_all(only=['cli']) must produce the same step IDs."""
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e")
    request = E2eRequest(scenario="cli", runtime="java", vm=vm)

    single_plan = runner.plan(request)
    [all_plan] = runner.plan_all(only=["cli"])

    assert single_plan.task_ids == all_plan.task_ids


def test_plan_and_plan_all_produce_consistent_step_ids_for_cli_host(tmp_path: Path) -> None:
    """plan() and plan_all(only=['cli-host']) must produce the same step IDs."""
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e")
    request = E2eRequest(scenario="cli-host", runtime="java", vm=vm)

    single_plan = runner.plan(request)
    [all_plan] = runner.plan_all(only=["cli-host"])

    assert single_plan.task_ids == all_plan.task_ids


def test_plan_raises_for_unknown_vm_scenario(tmp_path: Path) -> None:
    """plan() must raise ValueError for VM scenarios without a builder."""
    from unittest.mock import patch
    from controlplane_tool.scenario.catalog import ScenarioDefinition

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    unknown_scenario = ScenarioDefinition(
        name="unknown-vm-scenario",
        description="Unknown VM scenario for testing.",
        requires_vm=True,
        supported_runtimes=("java",),
    )
    # Bypass Literal validation to construct a request with an unregistered scenario name.
    request = E2eRequest.model_construct(
        scenario="unknown-vm-scenario",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    with patch(
        "controlplane_tool.e2e.e2e_runner.resolve_scenario",
        return_value=unknown_scenario,
    ):
        with pytest.raises(ValueError, match="Unsupported VM-backed scenario"):
            runner.plan(request)


def test_run_dispatches_new_builder_without_explicit_registration(tmp_path: Path) -> None:
    """run() must dispatch any non-E2ePlan object via plan.run() — no registration needed."""
    from unittest.mock import MagicMock, patch

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)

    fake_builder = MagicMock(spec_set=["task_ids", "run", "request", "steps", "scenario"])
    fake_builder.task_ids = ["step.one"]
    fake_request = E2eRequest(scenario="cli", runtime="java", vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"))
    fake_builder.request = fake_request

    assert not isinstance(fake_builder, E2ePlan), "Sanity check: fake builder is not E2ePlan"

    with patch.object(runner, "plan", return_value=fake_builder):
        runner.run(fake_request)

    fake_builder.run.assert_called_once()


def test_plan_returns_scenario_plan_protocol(tmp_path: Path) -> None:
    """plan() must return an object satisfying the ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="docker", runtime="java"))

    assert isinstance(plan, ScenarioPlanProtocol), f"Expected ScenarioPlanProtocol, got {type(plan)}"


def test_run_all_forwards_event_listener_to_builder_plans(tmp_path: Path) -> None:
    """run_all() must forward event_listener to plan.run() for builder plans."""
    from unittest.mock import patch
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e")
    fake_steps = [ScenarioPlanStep(summary="s", command=["echo", "hi"], step_id="s.one")]
    fake_plan = CliVmPlan(
        scenario=runner.plan(E2eRequest(scenario="cli", runtime="java", vm=vm)).scenario,
        request=E2eRequest(scenario="cli", runtime="java", vm=vm),
        steps=fake_steps,
        runner=runner,
    )

    def listener(event):
        pass

    with patch.object(runner, "plan_all", return_value=[fake_plan]):
        with patch.object(fake_plan, "run") as mock_run:
            runner.run_all(event_listener=listener)

    mock_run.assert_called_once_with(event_listener=listener)


def test_plan_docker_returns_e2e_plan(tmp_path: Path) -> None:
    """plan() for local scenario returns E2ePlan (non-VM path)."""
    from controlplane_tool.e2e.e2e_runner import E2ePlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="docker", runtime="java"))

    assert isinstance(plan, E2ePlan)


def test_e2e_plan_run_executes_host_commands_via_workflow(tmp_path: Path) -> None:
    """E2ePlan.run() executes the local steps as host CommandTasks on the shell."""
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell, manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="buildpack", runtime="java"))

    plan.run()

    # Both buildpack local steps are run on the host shell, in order.
    assert shell.commands[0][:2] == ["./gradlew", ":function-runtime:bootBuildImage"]
    assert any(cmd[:1] == ["./scripts/controlplane.sh"] for cmd in shell.commands)


def test_e2e_plan_run_stops_on_first_failed_command(tmp_path: Path) -> None:
    """E2ePlan.run() raises on the first non-zero exit and stops."""
    shell = ScriptedShell(
        return_code_map={("./gradlew", ":function-runtime:bootBuildImage",
                          "-PfunctionRuntimeImage=nanofaas/function-runtime:buildpack"): 3},
    )
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell, manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="buildpack", runtime="java"))

    with pytest.raises(Exception):
        plan.run()
