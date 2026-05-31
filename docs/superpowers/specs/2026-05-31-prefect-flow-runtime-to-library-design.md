# Sub-3 — Prefect flow-runtime to library (Design)

**Status:** approved (design), pending implementation plan
**Date:** 2026-05-31
**Roadmap:** sub-project 3 of the workflow_tasks component-library effort
(`docs/superpowers/specs/2026-05-28-workflow-tasks-component-library-design.md`),
revalidated after the single-engine convergence (PR #85) and shim removal (PR #86).

## Goal

Move the **generic flow-execution runtime** out of `controlplane_tool/orchestation/`
into the `workflow_tasks` library, so `controlplane_tool` keeps only the
*assembly* of specific flows. This advances the "library owns *how* a thing is
done; controlplane owns *which* things" boundary.

## Boundary

### Moves to the library (zero controlplane coupling)

- `orchestation/prefect_runtime.py` → `workflow_tasks/orchestration/runtime.py`
  - `run_local_flow(flow_id, flow_fn, *args, **kwargs) -> FlowRunResult`
  - private helpers `_run_without_prefect`, `_quiet_prefect_runtime`,
    `_now_utc`, `_generated_flow_run_id`, `_prefect_backend_name`
- `orchestation/prefect_models.py` → `workflow_tasks/orchestration/models.py`
  - `FlowRunResult` (generic dataclass, `completed`/`failed` constructors)
  - `LocalFlowDefinition` (generic: `flow_id`, `task_ids`, `run`)

These two files import nothing from `controlplane_tool` (runtime imports only
`prefect_models`; models import only stdlib). They are the generic "run a flow,
optionally under Prefect, else fall back to a plain call" runtime.

**Library home:** a new `workflow_tasks/orchestration/` subpackage with
`runtime.py` + `models.py` and an `__init__.py` re-exporting the public names
(`run_local_flow`, `FlowRunResult`, `LocalFlowDefinition`). This is a distinct,
well-named concept, kept separate from `workflow_tasks/workflow/` (the honest
Task/Workflow engine) and `workflow_tasks/integrations/prefect.py` (Prefect
event normalization).

### Stays in controlplane (assembly that *uses* the runtime)

- `orchestation/flow_catalog.py` — resolves *specific* flow definitions
  (`infra.pipeline`, `building.building`, scenario flows). Product assembly.
- `orchestation/infra_flows.py` — builds the concrete infra/pipeline flows.
- `orchestation/pipeline.py` — `PipelineRunner`/`execute_pipeline` (`Profile`,
  `RunResult`).
- `orchestation/adapters.py` — `ShellCommandAdapter` facade over controlplane ops.
- `orchestation/prefect_deployments.py` — `build_prefect_deployment`,
  `run_deployment_flow`, `_KNOWN_DEPLOYMENTS` (hardcodes `building.building`,
  calls `resolve_flow_definition`). It *cannot* move (it would make the library
  depend on controlplane), so it stays and imports `run_local_flow` from the
  library.

The `orchestation/` package itself stays (it is the controlplane assembly layer);
only the two generic runtime files leave it.

## Transition

**No shim** — consistent with sub-4 (PR #86), which removed all temporary
re-export shims. Repoint every importer directly to
`workflow_tasks.orchestration` and delete the two source files. Importer volume:
`run_local_flow` ~17 files, `LocalFlowDefinition` ~6, `FlowRunResult` ~7 (overlapping).

## Testing

- The runtime's own tests move to the library:
  `tools/controlplane/tests/test_prefect_runtime.py` →
  `tools/workflow-tasks/tests/orchestration/test_runtime.py` (repointed to
  `workflow_tasks.orchestration`). Any model-only tests move alongside.
- Controlplane tests that consume the runtime (`test_pipeline.py`,
  `test_prefect_deployments.py`, `test_flow_catalog.py`, TUI/scenario flow tests)
  stay in controlplane and are repointed to import from `workflow_tasks.orchestration`.
- Safety net: full controlplane suite green, full workflow-tasks suite green,
  `lint-imports` 0 broken (the `workflow_tasks must not depend on controlplane`
  contract proves the move is clean), `ruff` clean.
- The library's coverage gate (90) must still pass; the moved runtime carries its
  tests so coverage holds.

## Constraints / invariants

- Dependency direction unchanged: `controlplane_tool → workflow_tasks`; the
  library must not import controlplane (import-linter + `test_package_boundaries`).
- No behavior change: `run_local_flow` keeps its Prefect-optional semantics
  (Prefect when `PREFECT_API_URL` set, quiet-local Prefect otherwise, plain call
  if Prefect not importable). Same `FlowRunResult` shape/`orchestrator_backend`
  values.
- Java/control-plane untouched; Python tooling only.

## Out of scope

- `flow_catalog`/`infra_flows`/`pipeline`/`adapters`/`prefect_deployments`
  (controlplane assembly — stay).
- The loadtest/scenario flow *builders* (`loadtest_flows.py`, `scenario_flows.py`)
  — they build controlplane-specific flows and only *use* the runtime.
- Bash elimination (sub-5/6).

## Success criteria

- `workflow_tasks/orchestration/{runtime,models}.py` exist with the moved code;
  `run_local_flow`/`FlowRunResult`/`LocalFlowDefinition` import from there.
- `controlplane_tool/orchestation/prefect_runtime.py` + `prefect_models.py` are
  deleted; no references remain.
- Both suites green, import-linter 0 broken, ruff clean.
