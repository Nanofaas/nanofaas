"""Equivalence oracle: the k3s-junit-curl Workflow must reproduce the legacy recipe.

The honest Workflow of Tasks (build_k3s_junit_curl_plan) must yield the exact same
ordered task_ids as the legacy recipe engine (plan_recipe_steps). This pins the
behavior-preserving contract of the C2 pilot rewrite.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, plan_recipe_steps
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan


def _request(cleanup_vm: bool = True) -> E2eRequest:
    return E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=cleanup_vm,
    )


def _recipe_ids(request: E2eRequest) -> list[str]:
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "k3s-junit-curl",
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return [s.step_id for s in steps if s.step_id]


def _workflow_plan(request: E2eRequest):
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return build_k3s_junit_curl_plan(runner, request)


def test_workflow_task_ids_match_recipe_with_cleanup() -> None:
    request = _request(cleanup_vm=True)
    recipe_ids = _recipe_ids(request)
    workflow_ids = _workflow_plan(request).workflow_task_ids

    assert workflow_ids == recipe_ids


def test_workflow_commands_match_resolved_recipe_commands() -> None:
    """Each honest CommandTask must reproduce the recipe step's *executed* command.

    The legacy plan steps keep <multipass-ip:NAME> placeholders that are resolved at
    execution time. The honest Workflow resolves them during assembly. To compare
    apples-to-apples we resolve the recipe steps through the same CommandResolver.
    """
    from workflow_tasks import CommandTask, VmInfo

    request = _request(cleanup_vm=True)
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "k3s-junit-curl",
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    recipe = {s.step_id: s for s in steps if s.step_id}

    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    resolver = CommandResolver(host_resolver=lambda _: "10.0.0.1")
    plan = build_k3s_junit_curl_plan(runner, request)
    workflow = plan._assemble(
        plan._build_setup(), lambda: VmInfo(name="", host="", user="", home="")
    )

    compared = 0
    for task in workflow.tasks + workflow.cleanup_tasks:
        if not isinstance(task, CommandTask):
            continue  # DestroyVm / CallableTask have no command
        step = recipe[task.task_id]
        cache: dict[str, str] = {}
        expected_argv = resolver._resolve_command(list(step.command), request.vm, cache, runner.vm)
        expected_env = resolver._resolve_env(dict(step.env), request.vm, cache, runner.vm)
        assert list(task.spec.argv) == expected_argv, task.task_id
        assert dict(task.spec.env) == expected_env, task.task_id
        compared += 1

    assert compared > 0


def _recipe_summaries(request: E2eRequest) -> list[str]:
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "k3s-junit-curl",
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return [s.summary for s in steps]


def test_phase_titles_match_recipe_summaries_with_cleanup() -> None:
    request = _request(cleanup_vm=True)
    plan = _workflow_plan(request)
    assert plan.phase_titles == _recipe_summaries(request)


def test_phase_titles_match_recipe_summaries_no_cleanup() -> None:
    request = _request(cleanup_vm=False)
    plan = _workflow_plan(request)
    assert plan.phase_titles == _recipe_summaries(request)


def test_workflow_task_ids_match_recipe_no_cleanup() -> None:
    request = _request(cleanup_vm=False)
    recipe_ids = _recipe_ids(request)
    workflow_ids = _workflow_plan(request).workflow_task_ids

    # The legacy recipe keeps a 'vm.down' step (an echo no-op) even with
    # --no-cleanup-vm; the honest Workflow preserves it as a no-op CallableTask so
    # the task_id lists are fully identical in both modes.
    assert "vm.down" in recipe_ids
    assert "vm.down" in workflow_ids
    assert workflow_ids == recipe_ids
