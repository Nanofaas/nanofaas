# Task Extraction Readiness

The internal task model can be extracted to a sibling uv project when:

- `src/controlplane_tool/tasks/` contains only logic modules: `models.py`, `rendering.py`, `executors.py`, and `adapters.py`.
- `src/controlplane_tool/tasks/` is covered by basedpyright.
- `HostCommandTaskExecutor` depends on a local runner `Protocol`, not `ShellBackend` or `SubprocessShell`.
- Shell adapters live outside the task package, currently in `controlplane_tool.core.task_shell_adapter`.
- Task-to-workflow/TUI event conversion lives outside the task package, currently in `controlplane_tool.workflow.task_events`.
- Scenario component planners convert through `CommandTaskSpec`.
- VM prelude script rendering uses task rendering.
- Host and VM execution use task executor interfaces.
- No task core module imports `shellcraft`, `tui_toolkit`, `typer`, `questionary`, `prefect`, `multipass`, or TUI app modules.
- Import-linter includes the `tasks_are_logic_only` contract.
- A source-scan regression test forbids direct imports of runtime, orchestration, CLI, app, shell, and TUI dependencies from the task package.

The first external project should be NanoFaaS-specific, not generic:

```text
tools/nanofaas-tasks/
  pyproject.toml
  src/nanofaas_tasks/
  tests/
```

The external project should start as a direct move of stabilized code, not a redesign.
