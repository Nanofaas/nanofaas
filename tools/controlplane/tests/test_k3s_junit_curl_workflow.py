"""Snapshot oracle: the k3s-junit-curl Workflow output is pinned to literals.

The honest Workflow of Tasks (build_k3s_junit_curl_plan) must yield an exact,
ordered task_id list, the matching phase summaries, and load-bearing resolved
commands. These were originally derived from the legacy recipe engine and are now
frozen as literal snapshots — the recipe engine is being deleted, but the
behavior-preserving contract of the C2 pilot rewrite is preserved here as the
source of truth.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from workflow_tasks.shell import RecordingShell
from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
from workflow_tasks.infra.ansible import bundled_ansible_root

_ANSIBLE_ROOT = bundled_ansible_root()
_ANSIBLE_CFG = str(_ANSIBLE_ROOT / "ansible.cfg")


def _playbook(name: str) -> str:
    return str(_ANSIBLE_ROOT / "playbooks" / name)


# --- Literal snapshots (captured from the legacy recipe; now the source of truth) ---
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
    "tests.run_k3s_curl_checks",
    "tests.run_k8s_junit",
    "cleanup.uninstall_function_runtime",
    "cleanup.uninstall_control_plane",
    "namespace.uninstall",
    "vm.down",
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
    "Run k3s-junit-curl verification",
    "Run K8sE2eTest in VM",
    "Uninstall function-runtime Helm release",
    "Uninstall control-plane Helm release",
    "Uninstall namespace Helm release",
    "Teardown VM",
]

# CommandTask task_ids (vm.ensure_running -> EnsureVmRunning, vm.down -> no-op
# CallableTask, tests.run_k3s_curl_checks -> CallableTask are NOT CommandTasks).
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
    "tests.run_k8s_junit",
    "cleanup.uninstall_function_runtime",
    "cleanup.uninstall_control_plane",
    "namespace.uninstall",
}


def _request(cleanup_vm: bool = True) -> E2eRequest:
    return E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=cleanup_vm,
    )


def _workflow_plan(request: E2eRequest):
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return build_k3s_junit_curl_plan(runner, request)


def test_workflow_task_ids_match_snapshot_with_cleanup() -> None:
    workflow_ids = _workflow_plan(_request(cleanup_vm=True)).workflow_task_ids
    assert workflow_ids == EXPECTED_TASK_IDS


def test_workflow_task_ids_match_snapshot_no_cleanup() -> None:
    workflow_ids = _workflow_plan(_request(cleanup_vm=False)).workflow_task_ids
    # The legacy recipe kept a 'vm.down' step (an echo no-op) even with
    # --no-cleanup-vm; the honest Workflow preserves it as a no-op CallableTask so
    # the task_id lists are fully identical in both modes.
    assert "vm.down" in workflow_ids
    assert workflow_ids == EXPECTED_TASK_IDS


def test_phase_titles_match_snapshot_with_cleanup() -> None:
    assert _workflow_plan(_request(cleanup_vm=True)).phase_titles == EXPECTED_SUMMARIES


def test_phase_titles_match_snapshot_no_cleanup() -> None:
    assert _workflow_plan(_request(cleanup_vm=False)).phase_titles == EXPECTED_SUMMARIES


def test_workflow_command_tasks_are_resolved_and_pinned() -> None:
    """Each honest CommandTask must carry a resolved, non-empty argv.

    Pins the set of CommandTask ids and spot-checks load-bearing commands as
    literals (avoiding machine-specific paths such as the ansible --private-key).
    """
    from workflow_tasks import CommandTask, VmInfo

    request = _request(cleanup_vm=True)
    plan = build_k3s_junit_curl_plan(
        E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), host_resolver=lambda _: "10.0.0.1"),
        request,
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

    # --- Load-bearing literal spot-checks (portable; no machine paths) ---
    assert list(command_tasks["helm.deploy_control_plane"].spec.argv) == [
        "helm", "upgrade", "--install", "control-plane", "helm/nanofaas",
        "-n", "nanofaas-e2e", "--wait", "--timeout", "5m",
        "--set", "namespace.create=false",
        "--set", "namespace.name=nanofaas-e2e",
        "--set", "controlPlane.image.repository=localhost:5000/nanofaas/control-plane",
        "--set", "controlPlane.image.tag=e2e",
        "--set", "controlPlane.image.pullPolicy=Always",
        "--set", "demos.enabled=false",
        "--set", "prometheus.create=false",
        "--set", "controlPlane.extraEnv[0].name=NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND",
        "--set", "controlPlane.extraEnv[0].value=k8s",
        "--set", "controlPlane.extraEnv[1].name=NANOFAAS_K8S_CALLBACK_URL",
        "--set", "controlPlane.extraEnv[1].value=http://control-plane.nanofaas-e2e.svc.cluster.local:8080/v1/internal/executions",
        "--set", "controlPlane.extraEnv[2].name=SYNC_QUEUE_ENABLED",
        "--set", "controlPlane.extraEnv[2].value=true",
        "--set", "controlPlane.extraEnv[3].name=NANOFAAS_SYNC_QUEUE_ENABLED",
        "--set", "controlPlane.extraEnv[3].value=true",
        "--set", "controlPlane.extraEnv[4].name=SYNC_QUEUE_ADMISSION_ENABLED",
        "--set", "controlPlane.extraEnv[4].value=false",
        "--set", "controlPlane.extraEnv[5].name=SYNC_QUEUE_MAX_DEPTH",
        "--set", "controlPlane.extraEnv[5].value=1",
        "--set", "controlPlane.extraEnv[6].name=NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY",
        "--set", "controlPlane.extraEnv[6].value=1",
        "--set", "controlPlane.extraEnv[7].name=SYNC_QUEUE_MAX_ESTIMATED_WAIT",
        "--set", "controlPlane.extraEnv[7].value=2s",
        "--set", "controlPlane.extraEnv[8].name=SYNC_QUEUE_MAX_QUEUE_WAIT",
        "--set", "controlPlane.extraEnv[8].value=5s",
        "--set", "controlPlane.extraEnv[9].name=SYNC_QUEUE_RETRY_AFTER_SECONDS",
        "--set", "controlPlane.extraEnv[9].value=2",
        "--set", "controlPlane.extraEnv[10].name=SYNC_QUEUE_THROUGHPUT_WINDOW",
        "--set", "controlPlane.extraEnv[10].value=10s",
        "--set", "controlPlane.extraEnv[11].name=SYNC_QUEUE_PER_FUNCTION_MIN_SAMPLES",
        "--set", "controlPlane.extraEnv[11].value=1",
    ]
    assert dict(command_tasks["helm.deploy_control_plane"].spec.env) == {
        "KUBECONFIG": "/home/ubuntu/.kube/config"
    }

    assert list(command_tasks["helm.deploy_function_runtime"].spec.argv) == [
        "helm", "upgrade", "--install", "function-runtime", "helm/nanofaas-runtime",
        "-n", "nanofaas-e2e", "--wait", "--timeout", "3m",
        "--set", "functionRuntime.image.repository=localhost:5000/nanofaas/function-runtime",
        "--set", "functionRuntime.image.tag=e2e",
        "--set", "functionRuntime.image.pullPolicy=Always",
    ]

    # ansible provision: -i resolves to host (10.0.0.1,), env carries ANSIBLE_CONFIG.
    provision_argv = list(command_tasks["vm.provision_base"].spec.argv)
    assert provision_argv[0] == "ansible-playbook"
    assert provision_argv[provision_argv.index("-i") + 1] == "10.0.0.1,"
    assert provision_argv[-1] == _playbook("provision-base.yml")
    assert dict(command_tasks["vm.provision_base"].spec.env) == {
        "ANSIBLE_CONFIG": _ANSIBLE_CFG
    }
