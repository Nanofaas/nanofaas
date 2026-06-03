# Decouple verification planners from controlplane-tool (inject commands via context) — Design

**Status:** approved (Approach A), pending implementation plan
**Date:** 2026-06-03

## Goal

Remove the runtime coupling where three `workflow_tasks` verification components hard-code
commands that invoke `controlplane-tool` as a subprocess. The library should no longer
**know how to invoke controlplane** — the command is injected by controlplane via the
execution context.

## The coupling (level A — invocations)

In `workflow_tasks/components/verification.py`, three planners build argv that launch
controlplane-tool / controlplane modules:
- `plan_run_k3s_curl_checks` → `("python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack")`
- `plan_loadtest_run` → `("uv", "run", "--project", "tools/controlplane", "--locked", "controlplane-tool", "loadtest", "run")`
- `plan_autoscaling_experiment` → `("uv", "run", "--project", str(repo_root/"tools/controlplane"), "--locked", "python", str(repo_root/"experiments/autoscaling.py"))`

These are strings (argv), not Python imports, so import-linter doesn't catch them — but
those components only work inside a repo that has `controlplane-tool` at `tools/controlplane`.

**Out of scope (level B — path/layout strings):** `_remote_manifest_path`'s
`tools/controlplane/runs/manifests` fallback (used by the *kept* `plan_run_k8s_junit`),
and the rsync-exclude / image-cache path strings in `vm/multipass.py` and `images.py`.
These are nanofaas repo-layout conventions — deliberate nanofaas-isms, left as-is
(consistent with prior scope decisions).

## Approach: inject the commands via the context (Approach A)

`ScenarioExecutionContext` is a frozen dataclass defined in the library
(`workflow_tasks/components/context.py`) and **constructed by controlplane** (the factory
`resolve_scenario_environment` in `scenario/components/environment.py`). The planners
already receive this context. So:

1. **Library — add three neutral command fields to `ScenarioExecutionContext`** (empty-tuple
   defaults, so the library stays controlplane-agnostic):
   ```python
   k3s_curl_verify_command: tuple[str, ...] = ()
   loadtest_run_command: tuple[str, ...] = ()
   autoscaling_command: tuple[str, ...] = ()
   ```

2. **Library — the three planners read the injected command** instead of hard-coding it.
   A small guard raises a clear error if the factory forgot to provide it (so an empty
   default never silently produces a broken empty argv):
   ```python
   def _require_command(command: tuple[str, ...], name: str) -> tuple[str, ...]:
       if not command:
           raise ValueError(f"context.{name} was not provided by the context factory")
       return command
   ```
   - `plan_run_k3s_curl_checks`: `argv=_require_command(context.k3s_curl_verify_command, "k3s_curl_verify_command")`
   - `plan_loadtest_run`: `argv=_require_command(context.loadtest_run_command, "loadtest_run_command")` (the `env` built from the existing helpers is unchanged)
   - `plan_autoscaling_experiment`: `argv=_require_command(context.autoscaling_command, "autoscaling_command")` (env unchanged)
   The `controlplane_tool_project = "tools/controlplane"` lines and the hard-coded argv are removed.

3. **Controlplane — define the commands and inject them when building the context.** Add a
   small module `controlplane_tool/scenario/components/verification_commands.py`:
   ```python
   def k3s_curl_verify_command() -> tuple[str, ...]:
       return ("python", "-m", "controlplane_tool.e2e.k3s_curl_runner", "verify-existing-stack")

   def loadtest_run_command() -> tuple[str, ...]:
       return ("uv", "run", "--project", "tools/controlplane", "--locked", "controlplane-tool", "loadtest", "run")

   def autoscaling_command(repo_root: Path) -> tuple[str, ...]:
       return ("uv", "run", "--project", str(repo_root / "tools" / "controlplane"),
               "--locked", "python", str(repo_root / "experiments" / "autoscaling.py"))
   ```
   (Exact argv preserved verbatim from the originals so command snapshots don't change —
   note `loadtest` uses the relative `tools/controlplane`, `autoscaling` uses absolute
   `repo_root/...`, matching today's behavior.)

4. **Controlplane — populate the three fields at the construction sites.** Only two src
   sites build the context directly: `scenario/components/environment.py:61` (the main
   factory — has `repo_root`) and `infra/vm/vm_cluster_workflows.py:116`. Set the three
   fields there from the command builders. The derived `two_vm_loadtest._loadgen_context`
   uses `dataclasses.replace`, so it carries the new fields automatically.

## Boundary outcome

- The library `verification.py` no longer contains any `controlplane-tool` / `controlplane_tool`
  **invocation** argv. (The `_remote_manifest_path` `tools/controlplane/runs/manifests`
  path string remains — level B, acknowledged.)
- Controlplane owns the controlplane-tool commands and injects them. This matches the
  established boundary ("library = how to build the step + env; controlplane = which
  command to run") and the `cli.py`-stays-in-controlplane precedent.

## Testing

- **Library:** update `tests/components/test_verification.py` (or wherever those 3 planners
  are tested) so the test context provides the command fields, and assert the planner argv
  equals the injected command. Add a test that an empty command raises `ValueError`.
  Coverage gate 90 holds.
- **Controlplane:** the scenario oracle/snapshot tests that pin the loadtest/k3s-curl/
  autoscaling commands (e.g. `test_*_workflow.py`, `test_two_vm_loadtest_*`,
  `test_scenario_recipes`/`test_scenario_component_library` if they assert these argv) must
  still pass UNCHANGED, because controlplane injects the same commands. If any snapshot
  asserts the command, confirm it still matches; do not change the expected argv.
- **Guard:** a library test asserting `grep`-style that `verification.py` no longer builds a
  `controlplane_tool`/`controlplane-tool` invocation — OR simply rely on the planner tests
  reading from context. (Lightweight: assert `plan_loadtest_run` of a context with empty
  `loadtest_run_command` raises.)
- `lint-imports` both projects 0 broken; both suites green; `ruff` clean.

## Success criteria

- The three planners produce argv taken from `context.*_command`; an unset command raises.
- Controlplane defines + injects the three commands; existing command snapshots unchanged.
- `workflow_tasks/components/verification.py` has no `controlplane-tool` invocation argv left.
