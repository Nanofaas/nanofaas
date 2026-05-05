from pathlib import Path

import pytest
import yaml

from controlplane_tool.building.tasks import CommandExecutionResult
from controlplane_tool.workspace.paths import resolve_workspace_path
from controlplane_tool.orchestation.prefect_deployments import build_prefect_deployment, run_deployment_flow


def test_prefect_deployment_spec_is_optional_for_local_runs() -> None:
    assert build_prefect_deployment("building.building", enabled=False) is None


def test_prefect_deployment_spec_is_consistent_with_entrypoint_and_parameters() -> None:
    deployment = build_prefect_deployment("building.building", enabled=True)

    assert deployment is not None
    assert deployment.flow_id == "building.building"
    assert deployment.name == "building-building"
    assert deployment.entrypoint == "controlplane_tool.orchestation.prefect_deployments:run_deployment_flow"
    assert deployment.parameters == {
        "flow_name": "building.building",
        "profile": "core",
        "modules": None,
        "extra_gradle_args": [],
        "dry_run": False,
    }
    assert deployment.task_ids == ["building.building"]


def test_run_deployment_flow_resolves_catalog_definition(monkeypatch) -> None:
    import controlplane_tool.orchestation.prefect_deployments as deployments

    called: dict[str, object] = {}

    class _Flow:
        flow_id = "building.building"
        run = staticmethod(lambda: "ok")

    monkeypatch.setattr(
        deployments,
        "resolve_flow_definition",
        lambda flow_name, **kwargs: called.update({"flow_name": flow_name, "kwargs": kwargs}) or _Flow(),
    )
    monkeypatch.setattr(
        deployments,
        "run_local_flow",
        lambda flow_id, flow: called.update({"run_flow_id": flow_id}) or type(
            "_Result",
            (),
            {"status": "completed", "result": flow(), "error": None},
        )(),
    )

    result = run_deployment_flow(
        flow_name="building.building",
        profile="core",
        modules=None,
        extra_gradle_args=[],
        dry_run=False,
    )

    assert result == "ok"
    assert called["flow_name"] == "building.building"
    assert called["run_flow_id"] == "building.building"


def test_prefect_deployment_parameters_are_isolated_per_call() -> None:
    first = build_prefect_deployment("building.building", enabled=True)
    second = build_prefect_deployment("building.building", enabled=True)

    assert first is not None
    assert second is not None

    first.parameters["extra_gradle_args"].append("--scan")

    assert second.parameters["extra_gradle_args"] == []


def test_run_deployment_flow_raises_on_nonzero_command_result(monkeypatch) -> None:
    import controlplane_tool.orchestation.prefect_deployments as deployments

    class _Flow:
        flow_id = "building.building"
        run = staticmethod(lambda: None)

    monkeypatch.setattr(deployments, "resolve_flow_definition", lambda flow_name, **kwargs: _Flow())
    monkeypatch.setattr(
        deployments,
        "run_local_flow",
        lambda flow_id, flow: type(
            "_Result",
            (),
            {
                "status": "completed",
                "result": CommandExecutionResult(
                    command=["./gradlew", "building"],
                    return_code=17,
                    dry_run=False,
                ),
                "error": None,
            },
        )(),
    )

    with pytest.raises(RuntimeError, match="exit code 17"):
        run_deployment_flow(
            flow_name="building.building",
            profile="core",
            modules=None,
            extra_gradle_args=[],
            dry_run=False,
        )


def test_prefect_yaml_includes_supported_flow_example() -> None:
    prefect_yaml = resolve_workspace_path(Path("tools/controlplane/prefect.yaml"))

    payload = yaml.safe_load(prefect_yaml.read_text(encoding="utf-8"))

    assert payload["deployments"][0]["name"] == "building-building"
    assert payload["deployments"][0]["entrypoint"] == "controlplane_tool.orchestation.prefect_deployments:run_deployment_flow"
    assert payload["deployments"][0]["parameters"]["flow_name"] == "building.building"
