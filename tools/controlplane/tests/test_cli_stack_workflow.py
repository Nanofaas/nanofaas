"""Snapshot oracle: the cli-stack Workflow output is pinned to literals.

The honest Workflow of Tasks (build_cli_stack_plan) must yield an exact ordered
task_id list, the matching phase summaries, and load-bearing resolved commands.
These were originally derived from the legacy recipe engine and are now frozen as
literal snapshots — the recipe engine is being deleted, but the behavior-preserving
contract of the C3.2 rewrite is preserved here.

cli-stack adds two wrinkles over k3s/helm:
- cli.* planners need a CliComponentContext, all other planners the neutral
  ScenarioExecutionContext (the build_command_tasks context_selector).
- cleanup.verify_cli_platform_status_fails is a CallableTask (expect-failure),
  so it has no CommandTask spec; we only assert its task_id is present in order.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan


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
    "cli.build_install_dist",
    "cli.platform_install",
    "cli.platform_status",
    "cli.fn_apply_selected.echo-test",
    "cli.fn_list_selected",
    "cli.fn_invoke_selected.echo-test",
    "cli.fn_enqueue_selected.echo-test",
    "cli.fn_delete_selected.echo-test",
    "cleanup.uninstall_control_plane",
    "namespace.uninstall",
    "cleanup.verify_cli_platform_status_fails",
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
    "Build nanofaas-cli installDist in VM",
    "Install nanofaas into k3s through the CLI",
    "Run platform status",
    "Apply selected function 'echo-test'",
    "List selected functions",
    "Invoke selected function 'echo-test'",
    "Enqueue selected function 'echo-test'",
    "Delete selected function 'echo-test'",
    "Uninstall control-plane Helm release",
    "Uninstall namespace Helm release",
    "Verify cli-stack status fails",
    "Teardown VM",
]

# CommandTask task_ids: excludes vm.ensure_running (EnsureVmRunning),
# vm.down (no-op CallableTask) and cleanup.verify_cli_platform_status_fails
# (expect-failure CallableTask).
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
    "cli.build_install_dist",
    "cli.platform_install",
    "cli.platform_status",
    "cli.fn_apply_selected.echo-test",
    "cli.fn_list_selected",
    "cli.fn_invoke_selected.echo-test",
    "cli.fn_enqueue_selected.echo-test",
    "cli.fn_delete_selected.echo-test",
    "cleanup.uninstall_control_plane",
    "namespace.uninstall",
}


def _request(cleanup_vm: bool = True) -> E2eRequest:
    return E2eRequest(
        scenario="cli-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        namespace="nanofaas-cli-stack-e2e",
        cleanup_vm=cleanup_vm,
    )


def _workflow_plan(request: E2eRequest):
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return build_cli_stack_plan(runner, request)


def test_workflow_task_ids_match_snapshot_with_cleanup() -> None:
    workflow_ids = _workflow_plan(_request(cleanup_vm=True)).workflow_task_ids
    assert "vm.down" in workflow_ids
    assert "cleanup.verify_cli_platform_status_fails" in workflow_ids
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

    Pins the set of CommandTask ids and spot-checks load-bearing CLI commands as
    literals. cleanup.verify_cli_platform_status_fails is a CallableTask (no spec)
    and is excluded from the CommandTask set.
    """
    from workflow_tasks import CommandTask, VmInfo

    plan = build_cli_stack_plan(
        E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), host_resolver=lambda _: "10.0.0.1"),
        _request(cleanup_vm=True),
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

    _CLI = "/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas-cli"

    assert list(command_tasks["cli.platform_install"].spec.argv) == [
        _CLI, "platform", "install",
        "--release", "nanofaas-cli-stack-e2e",
        "-n", "nanofaas-cli-stack-e2e",
        "--chart", "/home/ubuntu/nanofaas/helm/nanofaas",
        "--control-plane-repository", "localhost:5000/nanofaas/control-plane",
        "--control-plane-tag", "e2e",
        "--control-plane-pull-policy", "Always",
        "--demos-enabled=false",
    ]
    assert dict(command_tasks["cli.platform_install"].spec.env) == {
        "KUBECONFIG": "/home/ubuntu/.kube/config"
    }

    assert list(command_tasks["cli.fn_invoke_selected.echo-test"].spec.argv) == [
        _CLI, "invoke", "echo-test", "-d",
        '{"input": {"message": "hello-from-cli-stack"}}',
    ]
    assert dict(command_tasks["cli.fn_invoke_selected.echo-test"].spec.env) == {
        "KUBECONFIG": "/home/ubuntu/.kube/config",
        "NANOFAAS_NAMESPACE": "nanofaas-cli-stack-e2e",
    }
