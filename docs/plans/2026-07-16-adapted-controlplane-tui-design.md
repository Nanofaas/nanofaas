# Adapted Control-Plane TUI Design

## Objective

Restore the menu-driven, branded control-plane TUI and its live workflow dashboard without restoring product operations that no longer exist. The result keeps the current YAML scenarios and workflow engine as the only execution model.

## Historical baseline

The reusable baseline is commit `9af3d5bf`, immediately before commit `9b7fd328` deleted the transition-era TUI modules. The useful components are:

- `WorkflowEventAggregator`, which already consumes the current `WorkflowEvent` contract;
- `WorkflowDashboard`, `TuiWorkflowSink`, and `WorkflowKeyListener`;
- `tui-toolkit` brand, theme, chrome, and full-screen described pickers;
- the historical aggregator and dashboard tests.

The old 2,000-line `NanofaasTUI`, scenario adapters, saved profiles, build menus, VM/registry management, catalog, and Prefect-era integration are not restored.

## Navigation

```text
Main
├── Validation
│   ├── Container       -> scenarios-v2/validate-container.yaml
│   └── Kubernetes      -> scenarios-v2/validate-k8s.yaml
├── CLI
│   └── Validate CLI    -> scenarios-v2/cli.yaml
├── Load Testing
│   └── Run load test   -> scenarios-v2/loadtest.yaml
├── Tools
│   ├── Inspect scenario
│   └── Doctor
└── Exit
```

Every submenu includes Back. `Esc` returns to the previous menu and `Ctrl+C` exits navigation cleanly. A workflow selection continues with environment, action (`plan` or `run`), provisioning when the environment is non-local, and cleanup policy.

## Visual invariant

In an interactive TTY, every screen contains exactly one NANOFAAS ASCII header at the top, at the same position and height. Only breadcrumb, title, prompt, and body change.

The historical bug came from three independent owners of the logo: a startup `header()` print, picker header construction, and dashboard frame construction. The restored design removes the standalone startup print and makes the active full-screen surface the sole owner of its header. Menu transitions replace the previous full screen instead of appending output. Static views and live dashboards use the same brand data and clear the previous surface before rendering.

The implementation may redraw the header during a transition; the invariant is visual rather than a requirement for a single long-lived terminal application.

## Workflow execution

The menu resolves a scenario and environment using the existing `_scenario`, `_environment`, and `_workflow` helpers. `plan` renders the workflow task list inside the branded frame. `run` binds the restored `TuiWorkflowSink`, starts Rich `Live`, and executes the current `Workflow.run()` synchronously.

For non-local environments, the TUI can enter the existing `provision_environment` context before building and running the workflow. The same keep/cleanup decision is applied to provisioning and `workflow.keep_infrastructure`. Load-test endpoints continue to use `resolve_loadtest_urls`.

The TUI does not modify `WorkflowEvent`, `Workflow`, or `ConsoleProgressSink`. GitNexus reports `WorkflowEvent` as HIGH risk, so it remains a stable consumed contract.

## Dashboard and errors

The dashboard preserves the historical Summary, Execution Phases, Nested Verification Work, Raw Command Output, and Error Detail panels. Planned workflow tasks are visible before execution. Events update task state and duration; `log.line` events stream stdout and stderr into a bounded buffer.

An action failure is rendered while the sink is still active, the final dashboard remains visible, and control returns to the previous menu after acknowledgement. Keyboard interruption during a running workflow propagates through normal cleanup before returning to navigation.

## Testing

Historical tests are restored where their production contracts still exist:

- event aggregation and nested task routing;
- dashboard rendering, duration, log toggling, error state, and bounded logs;
- picker chrome, brand, and Back behavior.

New tests cover:

- menu hierarchy and YAML routing;
- plan/run dispatch;
- environment, provisioning, and cleanup selections;
- exactly one ASCII logo per visible screen;
- stable header position across main menu, submenu, static plan, and live dashboard;
- no accumulated logo after navigating forward and back;
- ordinary non-interactive CLI behavior remaining unchanged.

## Non-goals

- Build and image-publishing menus;
- standalone VM or registry management;
- function catalog and saved profiles;
- old scenario aliases and transition adapters;
- a Textual rewrite or new UI framework;
- pixel-perfect preservation of obsolete menu content.
