from pathlib import Path
from datetime import UTC, datetime

from typer.testing import CliRunner

from controlplane_tool.orchestation.prefect_models import FlowRunResult
from controlplane_tool.cli.e2e_commands import _resolve_run_request
from controlplane_tool.app.main import app


def test_e2e_list_prints_known_scenarios() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "list"])
    assert result.exit_code == 0
    assert "k3s-junit-curl" in result.stdout
    assert "cli-stack" in result.stdout


def test_e2e_run_dry_run_prints_planned_steps() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "k3s-junit-curl", "--dry-run"])
    assert result.exit_code == 0
    assert "scenario" in result.stdout.lower()
    assert "step" in result.stdout.lower()


def test_e2e_run_dry_run_renders_resolved_functions() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "k3s-junit-curl", "--function-preset", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "word-stats-java" in result.stdout
    assert "json-transform-java" in result.stdout


def test_e2e_run_dry_run_accepts_demo_javascript_preset() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "k3s-junit-curl", "--function-preset", "demo-javascript", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "word-stats-javascript" in result.stdout


def test_e2e_run_dry_run_shows_catalog_flow_tasks(monkeypatch) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands

    monkeypatch.setattr(
        e2e_commands,
        "resolve_flow_task_ids",
        lambda flow_name, **kwargs: ["catalog.task_id"],
    )

    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "k3s-junit-curl", "--function-preset", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "catalog.task_id" in result.stdout


def test_helm_stack_default_selection_uses_supported_loadtest_functions() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "helm-stack", "--dry-run"])

    assert result.exit_code == 0
    assert "word-stats-go" not in result.stdout
    assert "json-transform-go" not in result.stdout


def test_helm_stack_rejects_unsupported_go_selection_before_backend() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "helm-stack", "--functions", "word-stats-go", "--dry-run"],
    )

    assert result.exit_code == 2
    rendered = result.stdout + result.stderr
    assert "helm-stack" in rendered
    assert "go" in rendered


def test_helm_stack_rejects_javascript_selection_before_backend() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "helm-stack", "--functions", "word-stats-javascript", "--dry-run"],
    )

    assert result.exit_code == 2
    assert "javascript" in (result.stdout + result.stderr)


def test_e2e_run_accepts_scenario_file_without_positional_scenario() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "e2e",
            "run",
            "--scenario-file",
            "tools/controlplane/scenarios/k8s-demo-java.toml",
            "--dry-run",
        ],
    )
    assert result.exit_code == 0
    assert "k3s-junit-curl" in result.stdout
    assert "scenario source" in result.stdout.lower()


def test_cli_function_override_preserves_scenario_file_load_targets() -> None:
    request = _resolve_run_request(
        scenario=None,
        runtime=None,
        lifecycle="multipass",
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="30G",
        cleanup_vm=True,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv="word-stats-java",
        scenario_file=Path("tools/controlplane/scenarios/k8s-demo-java.toml"),
        saved_profile=None,
    )

    assert request.resolved_scenario is not None
    assert request.resolved_scenario.load.targets == ["word-stats-java"]
    assert "word-stats-java" in request.resolved_scenario.payloads


def test_e2e_all_dry_run_honors_only_filter() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "all", "--only", "k3s-junit-curl", "--dry-run"])
    assert result.exit_code == 0
    assert "k3s-junit-curl" in result.stdout
    assert "docker" not in result.stdout


def test_e2e_group_lists_expected_commands() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "--help"])
    assert result.exit_code == 0
    assert "list" in result.stdout
    assert "run" in result.stdout
    assert "all" in result.stdout


def test_container_local_dry_run_no_longer_uses_placeholder_echo() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "container-local", "--dry-run"])
    assert result.exit_code == 0
    assert "echo container-local verification workflow" not in result.stdout


def test_container_local_rejects_multi_function_saved_profile() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "container-local", "--saved-profile", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 2
    rendered = result.stdout + result.stderr
    assert "container-local" in rendered
    assert "exactly one selected function" in rendered


def test_container_local_accepts_single_explicit_function() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "container-local", "--functions", "word-stats-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Resolved Functions: word-stats-java" in result.stdout


def test_e2e_explicit_functions_override_saved_profile_defaults(monkeypatch) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands
    from controlplane_tool.core.models import (
        ControlPlaneConfig,
        Profile,
        ScenarioSelectionConfig,
    )

    monkeypatch.setattr(
        e2e_commands,
        "load_profile",
        lambda name: Profile(
            name=name,
                control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
                scenario=ScenarioSelectionConfig(
                base_scenario="k3s-junit-curl",
                function_preset="demo-java",
            ),
        ),
    )

    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "e2e",
            "run",
            "k3s-junit-curl",
            "--saved-profile",
            "demo-java",
            "--functions",
            "word-stats-go",
            "--dry-run",
        ],
    )
    assert result.exit_code == 0
    assert "word-stats-go" in result.stdout
    assert "json-transform-java" not in result.stdout


def test_e2e_request_applies_cli_override_to_saved_profile_scenario_file(
    monkeypatch,
    tmp_path: Path,
) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands
    from controlplane_tool.core.models import (
        ControlPlaneConfig,
        Profile,
        ScenarioSelectionConfig,
    )

    scenario_file = tmp_path / "scenario.toml"
    scenario_file.write_text(
        """
name = "custom"
base_scenario = "k3s-junit-curl"
runtime = "java"
function_preset = "demo-java"
namespace = "from-file"
local_registry = "registry:5000"
""",
        encoding="utf-8",
    )

    monkeypatch.setattr(
        e2e_commands,
        "load_profile",
        lambda name: Profile(
            name=name,
            control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
            scenario=ScenarioSelectionConfig(scenario_file=str(scenario_file)),
        ),
    )

    request = _resolve_run_request(
        scenario=None,
        runtime="rust",
        lifecycle="external",
        name=None,
        host="127.0.0.1",
        user="ubuntu",
        home=None,
        cpus=2,
        memory="2G",
        disk="10G",
        cleanup_vm=True,
        namespace="override",
        local_registry="localhost:5001",
        function_preset="demo-java",
        functions_csv=None,
        scenario_file=None,
        saved_profile="saved",
    )

    assert request.resolved_scenario is not None
    assert request.resolved_scenario.runtime == "rust"
    assert request.resolved_scenario.namespace == "override"
    assert request.resolved_scenario.local_registry == "localhost:5001"
    assert request.resolved_scenario.function_preset == "demo-java"


def test_e2e_run_executes_prefect_flow(monkeypatch) -> None:
    runner = CliRunner()
    called: dict[str, str] = {}

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        now = datetime.now(UTC)
        return FlowRunResult.completed(
            flow_id=flow_id,
            flow_run_id="flow-run-1",
            orchestrator_backend="none",
            started_at=now,
            finished_at=now,
            result=None,
        )

    monkeypatch.setattr("controlplane_tool.cli.e2e_commands.run_local_flow", fake_run_local_flow)

    result = runner.invoke(app, ["e2e", "run", "k3s-junit-curl"])

    assert result.exit_code == 0
    assert called["flow_id"] == "e2e.k3s_junit_curl"


def test_e2e_all_executes_shared_catalog_flow(monkeypatch) -> None:
    import controlplane_tool.cli.e2e_commands as e2e_commands

    runner = CliRunner()
    called: dict[str, str] = {}

    def fake_resolve_flow_definition(flow_name, **kwargs):  # noqa: ANN001
        called["flow_name"] = flow_name
        return type(
            "_Flow",
            (),
            {
                "flow_id": "e2e.all",
                "task_ids": ["vm.ensure_running", "tests.run_k3s_curl_checks"],
                "run": staticmethod(lambda: ["ok"]),
            },
        )()

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        now = datetime.now(UTC)
        return FlowRunResult.completed(
            flow_id=flow_id,
            flow_run_id="flow-run-1",
            orchestrator_backend="none",
            started_at=now,
            finished_at=now,
            result=flow(),
        )

    monkeypatch.setattr(e2e_commands, "resolve_flow_definition", fake_resolve_flow_definition)
    monkeypatch.setattr(e2e_commands, "run_local_flow", fake_run_local_flow)

    result = runner.invoke(app, ["e2e", "all", "--only", "k3s-junit-curl"])

    assert result.exit_code == 0
    assert called["flow_name"] == "e2e.all"
    assert called["flow_id"] == "e2e.all"


def test_e2e_run_exposes_cleanup_vm_switches() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "--help"])

    assert result.exit_code == 0
    assert "--cleanup-vm" in result.stdout
    assert "--no-cleanup-vm" in result.stdout
