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


def test_run_k3s_curl_checks_runs_controlplane_runner() -> None:
    ops = ver.plan_run_k3s_curl_checks(_ctx())
    rendered = " ".join(ops[0].argv)
    assert "controlplane_tool.e2e.k3s_curl_runner" in rendered
