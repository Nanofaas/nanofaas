"""Equivalence oracle: the cli Workflow must reproduce the legacy planner steps.

The honest Workflow of Tasks (build_cli_vm_plan) must yield the exact same
ordered task_ids as the legacy planner (ScenarioPlanner.vm_backed_steps), and
reproduce each command step's *executed* command. This pins the
behavior-preserving contract of the C3.3 rewrite.

cli differs from k3s/helm/cli-stack: its steps come from
``ScenarioPlanner.vm_backed_steps`` (plain host ScenarioPlanSteps), not a recipe.
There is no recipe, no vm.down/teardown, and no special verify steps. The
``include_bootstrap`` flag (used by E2eRunner chaining) is preserved: when False,
the bootstrap steps (including vm.ensure_running) are omitted and the Workflow
contains only the scenario step.
"""
from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="cli",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def _runner() -> E2eRunner:
    return E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _: "10.0.0.1",
    )


def test_workflow_task_ids_match_planner_with_bootstrap() -> None:
    request = _request()
    runner = _runner()

    legacy_ids = [
        s.step_id
        for s in runner._planner.vm_backed_steps(request, include_bootstrap=True)
        if s.step_id
    ]
    workflow_ids = build_cli_vm_plan(runner, request).workflow_task_ids

    assert "vm.ensure_running" in legacy_ids
    assert "vm.ensure_running" in workflow_ids
    assert "cli.vm_e2e_flow" in workflow_ids
    assert workflow_ids == legacy_ids


def test_workflow_task_ids_match_planner_without_bootstrap() -> None:
    request = _request()
    runner = _runner()

    legacy_ids = [
        s.step_id for s in runner._planner.vm_scenario_steps(request) if s.step_id
    ]
    workflow_ids = build_cli_vm_plan(
        runner, request, include_bootstrap=False
    ).workflow_task_ids

    assert legacy_ids == ["cli.vm_e2e_flow"]
    assert "vm.ensure_running" not in workflow_ids
    assert workflow_ids == legacy_ids


def test_phase_titles_match_planner_summaries_with_bootstrap() -> None:
    request = _request()
    runner = _runner()
    legacy_summaries = [
        s.summary
        for s in runner._planner.vm_backed_steps(request, include_bootstrap=True)
    ]
    plan = build_cli_vm_plan(runner, request, include_bootstrap=True)
    assert plan.phase_titles == legacy_summaries


def test_phase_titles_match_planner_summaries_without_bootstrap() -> None:
    request = _request()
    runner = _runner()
    legacy_summaries = [
        s.summary
        for s in runner._planner.vm_backed_steps(request, include_bootstrap=False)
    ]
    plan = build_cli_vm_plan(runner, request, include_bootstrap=False)
    assert plan.phase_titles == legacy_summaries


def test_workflow_commands_match_resolved_planner_commands() -> None:
    """Each honest CommandTask must reproduce the planner step's *executed* command.

    cli steps are all host steps; the legacy planner keeps <multipass-ip:NAME>
    placeholders that are resolved at execution time. The honest Workflow resolves
    host commands during assembly, so we resolve the planner host steps through the
    same CommandResolver to compare apples-to-apples. vm.ensure_running becomes an
    EnsureVmRunning task (no command spec) and is excluded from this comparison.
    """
    from workflow_tasks import CommandTask

    request = _request()
    runner = _runner()

    legacy = {
        s.step_id: s
        for s in runner._planner.vm_backed_steps(request, include_bootstrap=True)
        if s.step_id
    }

    resolver = CommandResolver(host_resolver=lambda _: "10.0.0.1")
    plan = build_cli_vm_plan(runner, request)
    workflow = plan._assemble(include_bootstrap=True)

    compared = 0
    for task in workflow.tasks + workflow.cleanup_tasks:
        if not isinstance(task, CommandTask):
            continue
        assert task.task_id != "vm.ensure_running"
        step = legacy[task.task_id]
        cache: dict[str, str] = {}
        expected_argv = resolver._resolve_command(
            list(step.command), request.vm, cache, runner.vm
        )
        expected_env = resolver._resolve_env(
            dict(step.env), request.vm, cache, runner.vm
        )
        assert list(task.spec.argv) == expected_argv, task.task_id
        assert dict(task.spec.env) == expected_env, task.task_id
        compared += 1

    assert compared > 0
