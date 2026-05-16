from pathlib import Path

import pytest

from workflow_tasks import bind_workflow_sink, workflow_log
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, ScenarioPlan, ScenarioPlanStep
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
    assert any("controlplane_tool.e2e.k3s_curl_runner" in command for command in rendered)
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


def test_two_vm_loadtest_plan_uses_recipe_step_ids() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    step_ids = [step.step_id for step in plan.steps]

    assert step_ids[:4] == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
    ]
    assert step_ids[9:] == [
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "functions.register",
        "loadgen.ensure_running",
        "loadgen.provision_base",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    ]
    assert all(step.step_id for step in plan.steps)
    assert [step.summary for step in plan.steps[-10:]] == [
        "Build nanofaas-cli installDist in VM",
        "Register selected functions via REST API",
        "Ensure loadgen VM is running",
        "Provision loadgen base dependencies",
        "Install k6 on loadgen VM",
        "Run k6 from loadgen VM",
        "Capture Prometheus query snapshots",
        "Write two-VM loadtest report",
        "Tear down loadgen VM",
        "Teardown VM",
    ]


def test_two_vm_loadtest_plan_wires_run_k6_action() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    run_k6_step = next(step for step in plan.steps if step.step_id == "loadgen.run_k6")
    assert run_k6_step.action is not None
    assert run_k6_step.command[0] == "k6"


def test_two_vm_loadtest_plan_wires_loadgen_ensure_running_action(monkeypatch: pytest.MonkeyPatch) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    ensured: list[str] = []

    def fake_ensure_running(self: VmOrchestrator, request: VmRequest, *, dry_run: bool = False):
        ensured.append(request.name or "")

    monkeypatch.setattr(VmOrchestrator, "ensure_running", fake_ensure_running)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    loadgen_step = next(step for step in plan.steps if step.step_id == "loadgen.ensure_running")
    assert loadgen_step.action is not None
    assert loadgen_step.command[0:4] == ["multipass", "launch", "--name", "nanofaas-e2e-loadgen"]
    loadgen_step.action()
    assert ensured == ["nanofaas-e2e-loadgen"]


def test_two_vm_loadtest_plan_renders_custom_k6_options() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        k6_script=Path("/tmp/custom-load.js"),
        k6_payload=Path("/tmp/payload.json"),
        k6_vus=7,
        k6_duration="45s",
    )

    plan = runner.plan(request)

    run_k6_step = next(step for step in plan.steps if step.step_id == "loadgen.run_k6")
    assert "--vus" in run_k6_step.command
    assert "7" in run_k6_step.command
    assert "--duration" in run_k6_step.command
    assert "45s" in run_k6_step.command
    assert "-e" in run_k6_step.command
    assert "NANOFAAS_URL=http://<multipass-ip:nanofaas-e2e>:30080" in run_k6_step.command
    assert "NANOFAAS_PAYLOAD=/home/ubuntu/two-vm-loadtest/payloads/payload.json" in run_k6_step.command
    assert "/home/ubuntu/two-vm-loadtest/scripts/script.js" == run_k6_step.command[-1]


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


def test_two_vm_loadtest_exposes_control_plane_for_loadgen_vm() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    deploy_step = next(step for step in plan.steps if step.step_id == "helm.deploy_control_plane")
    assert "--set" in deploy_step.command
    assert "controlPlane.service.type=NodePort" in deploy_step.command
    assert "controlPlane.service.nodePorts.http=30080" in deploy_step.command
    assert "prometheus.create=true" in deploy_step.command
    assert "prometheus.service.type=NodePort" in deploy_step.command
    assert "prometheus.service.nodePort=30090" in deploy_step.command


def test_two_vm_loadtest_plan_wires_loadgen_cleanup_action() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    loadgen_down = next(step for step in plan.steps if step.step_id == "loadgen.down")
    assert loadgen_down.command == ["multipass", "delete", "nanofaas-e2e-loadgen"]
    assert loadgen_down.action is not None
    assert loadgen_down.always_run is True


def test_two_vm_loadtest_applies_functions_before_running_k6(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        resolved_scenario=load_scenario_file(
            Path("tools/controlplane/scenarios/two-vm-loadtest-java.toml")
        ),
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )

    plan = runner.plan(request)

    step_ids = [step.step_id for step in plan.steps]
    assert "functions.register" in step_ids
    assert "cli.build_install_dist" in step_ids
    register_index = step_ids.index("functions.register")
    k6_index = step_ids.index("loadgen.run_k6")
    assert register_index < k6_index, (
        f"functions.register (idx {register_index}) must precede loadgen.run_k6 (idx {k6_index})"
    )
    register_step = next(s for s in plan.steps if s.step_id == "functions.register")
    assert register_step.action is not None
    assert register_step.summary == "Register selected functions via REST API"


def test_two_vm_loadtest_plan_wires_prometheus_snapshot_action() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    snapshot_step = next(step for step in plan.steps if step.step_id == "metrics.prometheus_snapshot")
    assert snapshot_step.action is not None
    assert "http://<multipass-ip:nanofaas-e2e>:30090" in snapshot_step.command


def test_two_vm_loadtest_plan_wires_report_action() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan = runner.plan(request)

    report_step = next(step for step in plan.steps if step.step_id == "loadtest.write_report")
    assert report_step.action is not None
    assert report_step.command[:3] == ["python", "-m", "controlplane_tool.e2e.two_vm_loadtest_runner"]


def test_two_vm_loadtest_plan_skips_loadgen_cleanup_when_disabled() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=False,
    )

    plan = runner.plan(request)

    loadgen_down = next(step for step in plan.steps if step.step_id == "loadgen.down")
    assert loadgen_down.command == ["echo", "Skipping loadgen VM teardown (--no-cleanup-vm)"]
    assert loadgen_down.action is None


def test_e2e_all_two_vm_loadtest_plan_uses_recipe_step_ids() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(only=["two-vm-loadtest"])

    assert [plan.scenario.name for plan in plans] == ["two-vm-loadtest"]
    assert plans[0].request.vm is not None
    assert [step.step_id for step in plans[0].steps[-5:]] == [
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    ]


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

    runner.execute(plan)

    autoscaling_call = next(
        env for command, env in shell.calls if "experiments/autoscaling.py" in " ".join(command)
    )
    assert autoscaling_call["NAMESPACE"] == "nanofaas-e2e"
    assert autoscaling_call["E2E_VM_HOST"] == "10.0.0.1"
    assert autoscaling_call["E2E_PUBLIC_HOST"] == "10.0.0.1"


def test_run_all_bootstraps_vm_once_and_reuses_it() -> None:
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell, host_resolver=lambda _: "10.0.0.1")
    runner._planner._k3s_curl_runner = lambda request: type(  # type: ignore[assignment]
        "_Verifier",
        (),
        {"verify_existing_stack": staticmethod(lambda resolved: None)},
    )()

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
    runner._planner._k3s_curl_runner = lambda request: type(  # type: ignore[assignment]
        "_Verifier",
        (),
        {"verify_existing_stack": staticmethod(lambda resolved: None)},
    )()

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


def test_k3s_junit_curl_tail_steps_use_explicit_step_id_values() -> None:
    runner = E2eRunner(Path("/repo"), shell=RecordingShell())
    steps = runner._planner.k3s_junit_curl_tail_steps(  # noqa: SLF001
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert [step.step_id for step in steps] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k8s_junit",
        "cleanup.uninstall_function_runtime",
        "cleanup.uninstall_control_plane",
        "namespace.uninstall",
        "vm.down",
    ]


def test_k3s_junit_curl_tail_steps_use_explicit_step_id_values_without_cleanup() -> None:
    runner = E2eRunner(Path("/repo"), shell=RecordingShell())
    steps = runner._planner.k3s_junit_curl_tail_steps(  # noqa: SLF001
        E2eRequest(
            scenario="k3s-junit-curl",
            runtime="java",
            cleanup_vm=False,
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert [step.step_id for step in steps] == [
        "tests.run_k3s_curl_checks",
        "tests.run_k8s_junit",
        "cleanup.uninstall_function_runtime",
        "cleanup.uninstall_control_plane",
        "namespace.uninstall",
        "vm.down",
    ]


def test_execute_binds_step_context_for_nested_workflow_events(fake_sink) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = ScenarioPlan(
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
        runner.execute(plan)

    assert len(fake_sink.events) == 1
    assert fake_sink.events[0].flow_id == "docker"
    assert fake_sink.events[0].task_id == "tests.run_k3s_curl_checks"
    assert fake_sink.events[0].line == "nested progress"


def test_execute_rejects_step_without_id() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = ScenarioPlan(
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
        runner.execute(plan)


def test_execute_emits_step_progress_events() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = ScenarioPlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[
            ScenarioPlanStep(summary="First step", command=["echo", "one"], step_id="docker.first"),
            ScenarioPlanStep(summary="Second step", command=["echo", "two"], step_id="docker.second"),
        ],
    )

    events = []

    runner.execute(plan, event_listener=events.append)

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
    plan = ScenarioPlan(
        scenario=runner.plan(E2eRequest(scenario="docker", runtime="java")).scenario,
        request=E2eRequest(scenario="docker", runtime="java"),
        steps=[ScenarioPlanStep(summary="Broken step", command=["false"], step_id="docker.broken")],
    )

    events = []

    try:
        runner.execute(plan, event_listener=events.append)
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
    plan = ScenarioPlan(
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
        runner.execute(plan)

    assert cleanup_calls == ["vm.down"]


def test_execute_reports_main_and_cleanup_failures() -> None:
    shell = ScriptedShell(return_code_map={("false",): 7})
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)
    plan = ScenarioPlan(
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
        runner.execute(plan)

    message = str(excinfo.value)
    assert "Broken step" in message
    assert "Cleanup failed:" in message
    assert "cleanup failed" in message
