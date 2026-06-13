"""Snapshot oracle: the helm-stack Workflow output is pinned to literals.

The honest Workflow of Tasks (build_helm_stack_plan) must yield an exact ordered
task_id list, the matching phase summaries, and load-bearing resolved commands.
These were originally derived from the legacy recipe engine and are now frozen as
literal snapshots — the recipe engine is being deleted, but the behavior-preserving
contract of the C3.1 rewrite is preserved here.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from workflow_tasks.shell import RecordingShell
from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan


EXPECTED_TASK_IDS = [
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core.boot_jars",
    "images.build_core.control_image",
    "images.build_core.runtime_image",
    "images.build_core.push_control_image",
    "images.build_core.push_runtime_image",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "loadtest.install_k6",
    "loadtest.run",
    "experiments.autoscaling",
]

EXPECTED_SUMMARIES = [
    "Ensure VM is running",
    "Provision base VM dependencies",
    "Sync project to VM",
    "Ensure registry container",
    "Build core JVM artifacts",
    "Build control-plane image",
    "Build function-runtime image",
    "Push control-plane image",
    "Push function-runtime image",
    "Install k3s",
    "Configure k3s registry",
    "Install namespace Helm release",
    "Deploy control-plane via Helm",
    "Deploy function-runtime via Helm",
    "Install k6 for load testing",
    "Run k6 loadtest via controlplane runner",
    "Run autoscaling experiment (Python)",
]

# CommandTask task_ids (vm.ensure_running is EnsureVmRunning, not a CommandTask).
EXPECTED_COMMAND_TASK_IDS = {
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core.boot_jars",
    "images.build_core.control_image",
    "images.build_core.runtime_image",
    "images.build_core.push_control_image",
    "images.build_core.push_runtime_image",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "loadtest.install_k6",
    "loadtest.run",
    "experiments.autoscaling",
}


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="loadtest-helm-legacy",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )


def _workflow_plan(request: E2eRequest):
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return build_helm_stack_plan(runner, request)


def test_workflow_task_ids_match_snapshot() -> None:
    workflow_ids = _workflow_plan(_request()).workflow_task_ids
    # helm-stack leaves the VM running: no vm.down step.
    assert "vm.down" not in workflow_ids
    assert workflow_ids == EXPECTED_TASK_IDS


def test_phase_titles_match_snapshot() -> None:
    assert _workflow_plan(_request()).phase_titles == EXPECTED_SUMMARIES


def test_workflow_command_tasks_are_resolved_and_pinned() -> None:
    """Each honest CommandTask must carry a resolved, non-empty argv.

    Pins the set of CommandTask ids and spot-checks the load-bearing loadtest.run
    command/env (a VM-forwarded command that keeps the <multipass-ip> placeholder
    verbatim, exactly as the legacy recipe did).
    """
    from workflow_tasks import CommandTask, VmInfo

    plan = build_helm_stack_plan(
        E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), host_resolver=lambda _: "10.0.0.1"),
        _request(),
    )
    workflow = plan._assemble(
        plan._build_setup(), lambda: VmInfo(name="", host="", user="", home="")
    )

    command_tasks = {
        t.task_id: t
        for t in workflow.tasks + workflow.cleanup_tasks
        if isinstance(t, CommandTask)
    }
    assert set(command_tasks) == EXPECTED_COMMAND_TASK_IDS
    for task_id, task in command_tasks.items():
        assert list(task.spec.argv), f"empty argv for {task_id}"

    # loadtest.run runs on the VM (VmCommandTaskExecutor): the command/env are
    # forwarded verbatim, keeping <multipass-ip:...> placeholders unresolved.
    assert list(command_tasks["loadtest.run"].spec.argv) == [
        "uv", "run", "--project", "tools/controlplane", "--locked",
        "controlplane-tool", "loadtest", "run",
    ]
    assert dict(command_tasks["loadtest.run"].spec.env) == {
        "CONTROL_PLANE_RUNTIME": "java",
        "LOCAL_REGISTRY": "localhost:5000",
        "NAMESPACE": "nanofaas-e2e",
        "VM_NAME": "nanofaas-e2e",
        "CPUS": "4",
        "MEMORY": "12G",
        "DISK": "30G",
        "KEEP_VM": "true",
        "E2E_SKIP_VM_BOOTSTRAP": "true",
        "E2E_VM_LIFECYCLE": "multipass",
        "E2E_VM_USER": "ubuntu",
        "E2E_REMOTE_PROJECT_DIR": "/home/ubuntu/nanofaas",
        "E2E_KUBECONFIG_PATH": "/home/ubuntu/.kube/config",
        "E2E_VM_HOST": "<multipass-ip:nanofaas-e2e>",
        "E2E_PUBLIC_HOST": "<multipass-ip:nanofaas-e2e>",
    }

    # helm deploy carries the k8s backend / sync-queue env contract.
    deploy_argv = list(command_tasks["helm.deploy_control_plane"].spec.argv)
    assert deploy_argv[:6] == [
        "helm", "upgrade", "--install", "control-plane", "helm/nanofaas", "-n",
    ]
    assert "controlPlane.image.repository=localhost:5000/nanofaas/control-plane" in deploy_argv
    assert dict(command_tasks["helm.deploy_control_plane"].spec.env) == {
        "KUBECONFIG": "/home/ubuntu/.kube/config"
    }


def test_autoscaling_task_resolves_vm_host_env() -> None:
    """The autoscaling experiment task resolves the VM host into its env.

    Replaces the legacy ``_execute_steps`` test that asserted host resolution into
    the autoscaling env. The autoscaling experiment runs on the HOST (unlike
    ``loadtest.run`` which runs on the VM and keeps ``<multipass-ip:NAME>``
    placeholders verbatim), so the honest Workflow resolves the VM host via the
    ``host_resolver`` at assembly time: E2E_VM_HOST / E2E_PUBLIC_HOST become the
    resolved IP, and NAMESPACE is forwarded.
    """
    from workflow_tasks import CommandTask, VmInfo

    plan = build_helm_stack_plan(
        E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), host_resolver=lambda _: "10.0.0.1"),
        _request(),
    )
    workflow = plan._assemble(
        plan._build_setup(), lambda: VmInfo(name="", host="", user="", home="")
    )
    autoscaling = next(
        t
        for t in workflow.tasks + workflow.cleanup_tasks
        if isinstance(t, CommandTask) and t.task_id == "experiments.autoscaling"
    )
    env = dict(autoscaling.spec.env)
    assert env["NAMESPACE"] == "nanofaas-e2e"
    assert env["E2E_VM_HOST"] == "10.0.0.1"
    assert env["E2E_PUBLIC_HOST"] == "10.0.0.1"
