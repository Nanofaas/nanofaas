from pathlib import Path

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.e2e_runner import E2eRunner
from controlplane_tool.scenario_loader import load_scenario_file
from controlplane_tool.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario_models import ScenarioSpec
from controlplane_tool.shell_backend import RecordingShell
from controlplane_tool.vm_models import VmRequest


def test_dry_run_plan_describes_vm_backed_scenario_steps() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="k8s-vm",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    assert plan.scenario.name == "k8s-vm"
    assert any("ensure vm" in step.summary.lower() for step in plan.steps)


def test_select_scenarios_applies_only_and_skip_filters() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    plans = runner.plan_all(only=["k3s-curl", "k8s-vm"], skip=["k8s-vm"])

    assert [plan.scenario.name for plan in plans] == ["k3s-curl"]


def test_e2e_all_vm_plan_bootstraps_shared_vm_once() -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())

    plans = runner.plan_all(only=["k3s-curl", "k8s-vm"])

    ensure_steps = [
        step for scenario in plans for step in scenario.steps if "Ensure VM is running" == step.summary
    ]
    assert len(ensure_steps) == 1


def test_container_local_plan_calls_backend_script() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(scenario="container-local", runtime="java")
    )

    assert any(
        "scripts/lib/e2e-container-local-backend.sh" in " ".join(step.command)
        for step in plan.steps
    )


def test_k3s_curl_plan_no_longer_routes_to_k8s_e2e_test() -> None:
    plan = E2eRunner(Path("/repo"), shell=RecordingShell()).plan(
        E2eRequest(
            scenario="k3s-curl",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert not any("K8sE2eTest" in command for command in rendered)
    assert any("e2e-k3s-curl-backend.sh" in command for command in rendered)


def test_run_all_bootstraps_vm_once_and_reuses_it() -> None:
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)

    runner.run_all(only=["k3s-curl", "k8s-vm"], runtime="java")

    launches = [command for command in shell.commands if command[:2] == ["multipass", "launch"]]
    assert len(launches) <= 1


def test_run_all_tears_down_vm_when_keep_vm_false() -> None:
    shell = RecordingShell()
    runner = E2eRunner(repo_root=Path("/repo"), shell=shell)

    runner.run_all(only=["k8s-vm"], runtime="java")

    assert any(command[:2] == ["multipass", "delete"] for command in shell.commands)


def test_plan_tracks_resolved_scenario_selection(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(
        E2eRequest(
            scenario="k8s-vm",
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
            base_scenario="k3s-curl",
            runtime="java",
            function_preset="demo-java",
        )
    )

    plan = runner.plan(
        E2eRequest(
            scenario="k3s-curl",
            runtime="java",
            function_preset="demo-java",
            resolved_scenario=resolved,
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    backend_step = plan.steps[-1]
    assert backend_step.env["NANOFAAS_SCENARIO_PATH"].endswith(".json")
    assert Path(backend_step.env["NANOFAAS_SCENARIO_PATH"]).exists()


def test_k8s_vm_plan_exports_remote_manifest_to_test_command(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(
        E2eRequest(
            scenario="k8s-vm",
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
