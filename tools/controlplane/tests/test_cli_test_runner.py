from pathlib import Path

from controlplane_tool.cli_test_models import CliTestRequest
from controlplane_tool.cli_test_runner import CliTestRunner
from controlplane_tool.scenario_loader import load_scenario_file
from controlplane_tool.shell_backend import RecordingShell
from controlplane_tool.vm_models import VmRequest


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


def test_cli_test_runner_host_platform_plan_omits_resolved_functions_for_saved_profile_defaults() -> None:
    plan = CliTestRunner(repo_root=Path("/repo"), shell=RecordingShell()).plan(
        CliTestRequest(
            scenario="host-platform",
            vm=VmRequest(lifecycle="multipass"),
        )
    )

    assert plan.request.resolved_scenario is None
