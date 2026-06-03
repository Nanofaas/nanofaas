from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import verification as ver
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, manifest: Path | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
        manifest_path=manifest,
        k3s_curl_verify_command=("verify", "k3s"),
        loadtest_run_command=("loadtest", "go"),
        autoscaling_command=("autoscale", "go"),
    )


def test_verify_cli_platform_status_fails_builds_platform_status_argv() -> None:
    ops = ver.plan_verify_cli_platform_status_fails(_ctx())
    argv = ops[0].argv
    assert "platform" in argv and "status" in argv
    assert argv[-1] == "nf"
    assert ops[0].execution_target == "vm"


def test_run_k8s_junit_embeds_gradle_e2e_script() -> None:
    ops = ver.plan_run_k8s_junit(_ctx())
    rendered = " ".join(ops[0].argv)
    assert "K8sE2eTest" in rendered
    assert "k8s-deployment-provider:test" in rendered


def test_run_k3s_curl_checks_uses_injected_command() -> None:
    ops = ver.plan_run_k3s_curl_checks(_ctx())
    assert ops[0].argv == ("verify", "k3s")


def test_loadtest_run_uses_injected_command() -> None:
    ops = ver.plan_loadtest_run(_ctx())
    assert ops[0].argv == ("loadtest", "go")
    assert ops[0].execution_target == "vm"


def test_autoscaling_uses_injected_command() -> None:
    ops = ver.plan_autoscaling_experiment(_ctx())
    assert ops[0].argv == ("autoscale", "go")


def test_planner_raises_when_command_not_injected() -> None:
    import dataclasses
    import pytest

    ctx = dataclasses.replace(_ctx(), loadtest_run_command=())
    with pytest.raises(ValueError):
        ver.plan_loadtest_run(ctx)
