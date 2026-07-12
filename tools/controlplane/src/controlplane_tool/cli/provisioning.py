from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from workflow_tasks import VmRequest, workflow_step
from workflow_tasks.vm.orchestrator import VmOrchestrator

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.config.environment import RoleTarget


def _request(environment: EnvironmentConfig, target: RoleTarget) -> VmRequest:
    if environment.provider not in {"multipass", "external"}:
        raise ValueError(f"--provision does not support {environment.provider} environments")
    lifecycle = "multipass" if environment.provider == "multipass" else "external"
    return VmRequest(
        lifecycle=lifecycle,
        name=target.name,
        host=target.host,
        user=target.user,
        home=target.home,
        cpus=target.cpus,
        memory=target.memory,
        disk=target.disk,
    )


def _run(task_id: str, title: str, operation: Callable[[], Any]) -> None:
    with workflow_step(task_id=task_id, title=title):
        result = operation()
        if result.return_code != 0:
            detail = result.stderr or result.stdout or "command failed"
            raise RuntimeError(detail.strip())


def provision_environment(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    repo_root: Path,
    orchestrator_factory: Callable[[Path], Any] = VmOrchestrator,
) -> None:
    if environment.provider == "local":
        raise ValueError("--provision requires a non-local environment")

    orchestrator = orchestrator_factory(repo_root)
    stack = _request(environment, environment.target("stack"))
    _run("provision.stack.ensure", "Ensure stack VM is running", lambda: orchestrator.ensure_running(stack))
    _run(
        "provision.stack.base",
        "Install stack dependencies",
        lambda: orchestrator.install_dependencies(stack, install_helm=True),
    )
    if scenario.backend == "k8s" or scenario.workflow == "loadtest":
        _run("provision.stack.k3s", "Install k3s", lambda: orchestrator.install_k3s(stack))
        _run(
            "provision.stack.registry",
            "Configure container registry",
            lambda: orchestrator.setup_registry(stack),
        )
    if scenario.workflow == "loadtest" and "loadgen" not in environment.roles:
        _run(
            "provision.stack.k6",
            "Install k6 on stack VM",
            lambda: orchestrator.ansible.run_playbook("install-k6.yml", stack),
        )
    _run("provision.stack.sync", "Sync project to stack VM", lambda: orchestrator.sync_project(stack))

    if scenario.workflow == "loadtest" and "loadgen" in environment.roles:
        loadgen = _request(environment, environment.target("loadgen"))
        _run(
            "provision.loadgen.ensure",
            "Ensure load-generator VM is running",
            lambda: orchestrator.ensure_running(loadgen),
        )
        _run(
            "provision.loadgen.k6",
            "Install k6 on load-generator VM",
            lambda: orchestrator.ansible.run_playbook("install-k6.yml", loadgen),
        )
        _run(
            "provision.loadgen.sync",
            "Sync project to load-generator VM",
            lambda: orchestrator.sync_project(loadgen),
        )
