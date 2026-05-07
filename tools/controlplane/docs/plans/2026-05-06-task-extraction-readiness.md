# Task Extraction Readiness

The internal task model can be extracted to a sibling uv project when:

- `src/controlplane_tool/tasks/` is covered by basedpyright.
- Scenario component planners convert through `CommandTaskSpec`.
- VM prelude script rendering uses task rendering.
- Host and VM execution use task executor interfaces.
- TUI workflow rendering consumes task workflow bridge events.
- No task core module imports `typer`, `questionary`, `prefect`, `multipass`, or TUI app modules.
- Import-linter includes a contract that task core does not depend on CLI/TUI/app entrypoints.

The first external project should be NanoFaaS-specific, not generic:

```text
tools/nanofaas-tasks/
  pyproject.toml
  src/nanofaas_tasks/
  tests/
```

The external project should start as a direct move of stabilized code, not a redesign.
