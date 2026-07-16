# CLI Control-Plane Preflight Implementation Plan

> **Execution:** use subagent-driven development with test-driven implementation and review checkpoints.

**Goal:** Block local CLI workflows before any task starts when their control plane is unavailable or unhealthy.

**Architecture:** A minimal shared preflight function owns the HTTP health check. Both CLI and TUI invoke it before execution; planning remains network-free.

**Tech stack:** Python 3.12, Typer, urllib from the standard library, pytest.

---

### Task 1: Add the shared preflight contract

**Files:**

- Create: `tools/controlplane/src/controlplane_tool/cli/preflight.py`
- Create: `tools/controlplane/tests/cli/test_preflight.py`

1. Write failing tests for no-op scenarios, a healthy Actuator response, unreachable endpoint, malformed JSON, and non-`UP` status.
2. Run `uv run pytest tests/cli/test_preflight.py -q` from `tools/controlplane` and confirm the tests fail for the missing implementation.
3. Implement the smallest standard-library health check and actionable `PreflightError` needed to pass.
4. Re-run the focused tests and confirm they pass.

### Task 2: Wire the CLI execution path

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Modify: `tools/controlplane/tests/cli/test_command_surface.py`

1. Add failing tests proving a failed local CLI preflight prevents workflow construction/run, `plan` remains offline, and a custom control-plane URL is used consistently.
2. Run the focused command-surface tests and confirm the new assertions fail.
3. Invoke the shared preflight before constructing the executable workflow and pass the effective endpoint to `build_cli_plan`.
4. Re-run the focused tests and confirm they pass.

### Task 3: Wire the TUI execution path

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Modify: `tools/controlplane/tests/test_tui_app.py`

1. Add a failing test proving a failed preflight shows a preflight error and does not open the live workflow controller or execute the workflow.
2. Run `uv run pytest tests/test_tui_app.py -q` and confirm the new test fails.
3. Invoke the shared preflight only on the TUI run path, before live execution, and render a static error on failure.
4. Re-run the focused TUI tests and confirm they pass.

### Task 4: Verify scope and quality

1. Run the complete controlplane tool test suite.
2. Run the repository's controlplane quality command.
3. Run GitNexus change detection and confirm only the preflight and its two callers are affected.
4. Inspect `git diff --check` and the final diff.
5. Commit the implementation with a short imperative message.
