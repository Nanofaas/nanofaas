from pathlib import Path

import pytest

import controlplane_tool.cli_validation.cli_stack_runner as cli_stack_runner_mod
import controlplane_tool.cli_validation.cli_test_runner as cli_test_runner_mod
from controlplane_tool.cli_validation.cli_test_catalog import resolve_cli_test_scenario
from controlplane_tool.cli_validation.cli_test_models import CliTestRequest
from controlplane_tool.cli_validation.cli_test_runner import CliTestPlan, CliTestRunner
from controlplane_tool.e2e.e2e_runner import ScenarioPlanStep
from controlplane_tool.scenario.scenario_models import ResolvedFunction, ResolvedScenario
from controlplane_tool.scenario.scenario_loader import load_scenario_file
from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.infra.vm.vm_models import VmRequest


def test_cli_test_runner_unit_scenario_calls_gradle_cli_tests() -> None:
    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(scenario="unit")
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert any(":nanofaas-cli:test" in command for command in rendered)
    assert not any("e2e-cli-backend.sh" in command for command in rendered)


def test_cli_vm_runner_no_longer_uses_shell_backend_script(tmp_path: Path) -> None:
    """M10: vm scenario must not route to the deleted e2e-cli-backend.sh."""
    plan = CliTestRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        manifest_root=tmp_path,
    ).plan(
        CliTestRequest(
            scenario="vm",
            function_preset="demo-java",
            resolved_scenario=load_scenario_file(
                Path("tools/controlplane/scenarios/k8s-demo-java.toml")
            ),
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert any(":nanofaas-cli:installDist" in command for command in rendered)
    assert not any("e2e-cli-backend.sh" in command for command in rendered)


def test_cli_host_platform_runner_no_longer_uses_shell_backend_script() -> None:
    """M10: host-platform scenario must not route to the deleted e2e-cli-host-backend.sh."""
    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(
            scenario="host-platform",
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    rendered = [" ".join(step.command) for step in plan.steps]
    assert not any("e2e-cli-host-backend.sh" in command for command in rendered)


def test_cli_stack_runner_defaults_to_managed_vm_without_host_env(monkeypatch) -> None:
    monkeypatch.setattr(
        cli_stack_runner_mod,
        "vm_request_from_env",
        lambda: (_ for _ in ()).throw(AssertionError("vm_request_from_env should not be used")),
        raising=False,
    )

    runner = cli_stack_runner_mod.CliStackRunner(repo_root=Path("/repo"))

    assert runner.vm_request.lifecycle == "multipass"


def test_cli_stack_runner_plan_steps_delegate_to_shared_recipe_planner(monkeypatch) -> None:
    sentinel_steps = [
        ScenarioPlanStep(summary="recipe step", command=["echo", "recipe-step"])
    ]
    monkeypatch.setattr(
        cli_stack_runner_mod,
        "plan_recipe_steps",
        lambda *args, **kwargs: sentinel_steps,
        raising=False,
    )

    runner = cli_stack_runner_mod.CliStackRunner(
        repo_root=Path("/repo"),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    assert runner.plan_steps() == sentinel_steps


def test_cli_test_runner_cli_stack_plan_promotes_missing_vm_to_managed_context(monkeypatch) -> None:
    sentinel_steps = [
        ScenarioPlanStep(summary="recipe step", command=["echo", "recipe-step"])
    ]
    captured: dict[str, object] = {}

    class _Runner:
        def __init__(self, *args, **kwargs):  # noqa: ANN002,ANN003
            captured["namespace"] = kwargs.get("namespace")
            self.vm_request = kwargs.get("vm_request") or VmRequest(
                lifecycle="multipass",
                name="nanofaas-e2e",
            )

        def plan_steps(self, resolved_scenario=None):  # noqa: ANN001
            return sentinel_steps

    monkeypatch.setattr(cli_test_runner_mod, "CliStackRunner", _Runner)

    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(scenario="cli-stack")
    )

    assert plan.steps == sentinel_steps
    assert plan.request.vm is not None
    assert plan.request.vm.lifecycle == "multipass"
    assert captured["namespace"] is None


def test_cli_test_runner_host_platform_plan_omits_resolved_functions_for_saved_profile_defaults() -> None:
    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(
            scenario="host-platform",
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    assert plan.request.resolved_scenario is None


def test_cli_test_runner_e2e_request_inverts_keep_vm_to_cleanup_vm() -> None:
    runner = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell())
    scenario = resolve_cli_test_scenario("vm")

    cleanup_request = runner._as_e2e_request(
        CliTestRequest(
            scenario="vm",
            vm=VmRequest(lifecycle="multipass"),
            keep_vm=False,
        ),
        scenario,
    )
    keep_request = runner._as_e2e_request(
        CliTestRequest(
            scenario="vm",
            vm=VmRequest(lifecycle="multipass"),
            keep_vm=True,
        ),
        scenario,
    )

    assert cleanup_request.cleanup_vm is True
    assert keep_request.cleanup_vm is False


def test_cli_test_runner_cli_stack_plan_prefers_resolved_scenario_namespace() -> None:
    resolved_scenario = ResolvedScenario(
        name="cli-stack-selection",
        base_scenario="cli-stack",
        runtime="java",
        namespace="scenario-namespace",
        functions=[
            ResolvedFunction(
                key="word-stats-javascript",
                family="word-stats",
                runtime="javascript",
                description="Resolved function",
                image="localhost:5000/nanofaas/word-stats-javascript:e2e",
            )
        ],
        function_keys=["word-stats-javascript"],
    )

    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(
            scenario="cli-stack",
            resolved_scenario=resolved_scenario,
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
    assert all("scenario-namespace" in command for command in rendered)


def test_cli_test_runner_resolves_multipass_ip_placeholders_before_execution(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    shell = RecordingShell()
    runner = CliTestRunner(repo_root=Path("/repo"), shell=shell)
    request = CliTestRequest(
        scenario="cli-stack",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    plan = CliTestPlan(
        scenario=resolve_cli_test_scenario("cli-stack"),
        request=request,
        steps=[
            ScenarioPlanStep(
                summary="Sync project",
                command=[
                    "rsync",
                    "/repo/",
                    "ubuntu@<multipass-ip:nanofaas-e2e>:/home/ubuntu/nanofaas/",
                ],
            )
        ],
    )
    monkeypatch.setattr(
        runner.e2e_runner.vm,
        "resolve_multipass_ipv4",
        lambda vm_request: "10.0.0.20",
    )

    runner._execute_steps(plan)

    assert shell.commands == [
        ["rsync", "/repo/", "ubuntu@10.0.0.20:/home/ubuntu/nanofaas/"]
    ]
