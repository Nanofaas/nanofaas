"""Equivalence oracle: the helm-stack Workflow must reproduce the legacy recipe.

The honest Workflow of Tasks (build_helm_stack_plan) must yield the exact same
ordered task_ids as the legacy recipe engine (plan_recipe_steps), and reproduce
each step's *executed* command. This pins the behavior-preserving contract of the
C3.1 rewrite.
"""
from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner, plan_recipe_steps
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.core.shell_backend import RecordingShell
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="helm-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )


def _recipe_ids(request: E2eRequest) -> list[str]:
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "helm-stack",
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
    return build_helm_stack_plan(runner, request)


def test_workflow_task_ids_match_recipe() -> None:
    request = _request()
    recipe_ids = _recipe_ids(request)
    workflow_ids = _workflow_plan(request).workflow_task_ids

    # helm-stack leaves the VM running: no vm.down step.
    assert "vm.down" not in recipe_ids
    assert workflow_ids == recipe_ids


def _recipe_summaries(request: E2eRequest) -> list[str]:
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "helm-stack",
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )
    return [s.summary for s in steps]


def test_phase_titles_match_recipe_summaries() -> None:
    request = _request()
    plan = _workflow_plan(request)
    assert plan.phase_titles == _recipe_summaries(request)


def test_workflow_commands_match_resolved_recipe_commands() -> None:
    """Each honest CommandTask must reproduce the recipe step's *executed* command.

    The legacy plan steps keep <multipass-ip:NAME> placeholders that are resolved at
    execution time. The honest Workflow resolves them during assembly. To compare
    apples-to-apples we resolve the recipe steps through the same CommandResolver.
    """
    from workflow_tasks import CommandTask, VmCommandTaskExecutor, VmInfo

    request = _request()
    steps = plan_recipe_steps(
        Path("/repo"),
        request,
        "helm-stack",
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
    plan = build_helm_stack_plan(runner, request)
    workflow = plan._assemble(
        plan._build_setup(), lambda: VmInfo(name="", host="", user="", home="")
    )

    compared = 0
    for task in workflow.tasks + workflow.cleanup_tasks:
        if not isinstance(task, CommandTask):
            continue
        step = recipe[task.task_id]
        if isinstance(task.executor, VmCommandTaskExecutor):
            # Legacy vm steps run via on_remote_exec, which forwards the command/env
            # to the VM verbatim (no host-side <multipass-ip:NAME> resolution). The
            # honest Workflow does the same, so compare the raw recipe command/env.
            expected_argv = list(step.command)
            expected_env = dict(step.env)
        else:
            # Legacy host steps resolve <multipass-ip:NAME> placeholders at exec time.
            cache: dict[str, str] = {}
            expected_argv = resolver._resolve_command(list(step.command), request.vm, cache, runner.vm)
            expected_env = resolver._resolve_env(dict(step.env), request.vm, cache, runner.vm)
        assert list(task.spec.argv) == expected_argv, task.task_id
        assert dict(task.spec.env) == expected_env, task.task_id
        compared += 1

    assert compared > 0
