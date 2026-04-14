# CLI Stack E2E Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Follow `@superpowers:test-driven-development` for every code change and keep commits small.

**Goal:** Add a VM-backed CLI evaluation scenario, exposed canonically through `cli-test`, that reuses the `helm-stack` VM/bootstrap primitives, compiles the CLI in the VM, installs Helm and k3s, wires a local registry into k3s, and then exercises nanofaas install/status/uninstall plus function build/push and invocation flows.

**Architecture:** The shared value is the VM and cluster bootstrap, not the scenario tail. Extract the common VM, registry, Helm, k3s, and image-prep steps into a reusable helper so `helm-stack` and the new CLI scenario both start from the same base environment. The canonical user-facing surface for this work is `cli-test run cli-stack`; any `e2e.cli-stack` flow exists only as an internal orchestration substrate that `cli-test` can call into. Keep `helm-stack` responsible for the Helm deploy plus loadtest/autoscaling path, and introduce a separate CLI tail. Reuse `host-platform` only at the level of shared platform-workflow primitives such as install/status/uninstall command assembly and endpoint/status assertions, not by collapsing host-driven and VM-driven runners into one execution model.

**Tech Stack:** Python 3.12, `uv`, `pytest`, Typer, Pydantic, `VmOrchestrator`, Helm, k3s, Docker registry tooling.

---

## Scope and assumptions

This plan assumes a new working scenario name of `cli-stack`.
The canonical entrypoint is `scripts/controlplane.sh cli-test run cli-stack`.
If an `e2e.cli-stack` flow is added, it is an implementation detail and should not become the primary documented surface.
Only the bootstrap prefix is shared with `helm-stack`; the CLI tail has its own task list and fixed order.

Scope:

- `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- `tools/controlplane/src/controlplane_tool/models.py`
- `tools/controlplane/src/controlplane_tool/cli_e2e_commands.py`
- `tools/controlplane/src/controlplane_tool/cli_test_catalog.py`
- `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- `tools/controlplane/src/controlplane_tool/tui_app.py`
- `docs/testing.md`
- `tools/controlplane/README.md`
- `README.md`
- `tools/controlplane/tests/test_e2e_catalog.py`
- `tools/controlplane/tests/test_e2e_commands.py`
- `tools/controlplane/tests/test_scenario_flows.py`
- `tools/controlplane/tests/test_cli_test_catalog.py`
- `tools/controlplane/tests/test_cli_test_commands.py`
- `tools/controlplane/tests/test_cli_test_models.py`
- `tools/controlplane/tests/test_cli_runtime.py`
- `tools/controlplane/tests/test_flow_catalog.py`
- `tools/controlplane/tests/test_tui_choices.py`
- `tools/controlplane/tests/test_docs_links.py`
- `tools/controlplane/tests/test_canonical_entrypoints.py`

Non-goals:

- no new auth or RBAC behavior
- no rewrite of the Java CLI itself
- no change to the existing `helm-stack` output beyond extracting shared helpers
- no removal of `host-platform`; it stays as a host-driven compatibility path

## Task 1: Lock down the target behavior with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_e2e_catalog.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_cli_test_catalog.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `tools/controlplane/tests/test_cli_test_models.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/tests/test_flow_catalog.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write the failing tests**

Add tests that describe the desired shape before touching implementation:

- `test_catalog_lists_cli_stack`
- `test_cli_stack_uses_helm_stack_bootstrap_prefix`
- `test_cli_stack_tail_contains_cli_build_and_platform_lifecycle`
- `test_cli_stack_flow_routes_through_python_runner`
- `test_cli_test_catalog_exposes_cli_stack`
- `test_cli_stack_request_is_vm_backed_and_accepts_selection`
- `test_cli_test_run_cli_stack_dry_run_shows_function_build_and_platform_uninstall`
- `test_host_platform_stays_platform_only`
- `test_flow_catalog_resolves_cli_stack_task_ids`
- `test_cli_stack_appears_in_tui_choices`

The assertions should cover:

- `cli-stack` is exposed canonically under `cli-test`
- if an internal `e2e.cli-stack` flow exists, it is routed only as an implementation detail from `cli-test`
- the shared bootstrap prefix matches `helm-stack` up to the point where the scenario tails diverge
- the `cli-stack` tail uses this exact order:
- compile `nanofaas-cli` in the VM
- build and push selected function images
- install nanofaas into k3s through the CLI
- run `platform status`
- apply or register the selected functions
- run `fn list`
- run synchronous invoke checks
- run enqueue checks
- delete the selected functions
- uninstall nanofaas
- verify `platform status` fails after uninstall
- `host-platform` still rejects function selection and still reads as platform-only
- the TUI surfaces the new scenario with a label that makes the host vs cluster distinction obvious

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_catalog.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_flow_catalog.py \
  tools/controlplane/tests/test_tui_choices.py -q
```

Expected: FAIL because `cli-stack` is not wired yet and the new shared bootstrap/tail shape does not exist.

**Step 3: Commit the red tests**

```bash
git add tools/controlplane/tests/test_e2e_catalog.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_cli_test_catalog.py tools/controlplane/tests/test_cli_test_commands.py tools/controlplane/tests/test_cli_test_models.py tools/controlplane/tests/test_cli_runtime.py tools/controlplane/tests/test_flow_catalog.py tools/controlplane/tests/test_tui_choices.py
git commit -m "Add CLI stack E2E regression tests"
```

## Task 2: Extract the shared VM/bootstrap helper

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`

**Step 1: Write the minimal implementation**

Create a shared helper module that exposes only the common bootstrap prefix:

- the VM/bootstrap prefix shared by `helm-stack` and `cli-stack`
- the VM registry/image prep shared by both scenario tails

Refactor `E2eRunner` so it no longer inlines that shared bootstrap. `helm-stack` should consume the common prefix and then append its Helm deploy/readiness/loadtest/autoscaling tail. Do not add any CLI-specific assumptions to the shared helper.

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_flow_catalog.py -q
```

Expected: PASS once the shared helper module exists and the existing scenarios still behave the same.

**Step 3: Commit the helper extraction**

```bash
git add tools/controlplane/src/controlplane_tool/vm_cluster_workflows.py tools/controlplane/src/controlplane_tool/e2e_runner.py
git commit -m "Extract shared VM cluster workflows"
```

## Task 3: Add the new `cli-stack` scenario and wire the catalogs

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli_platform_workflow.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/flow_catalog.py`

**Step 1: Write the minimal implementation**

Add `cli-stack` as a first-class CLI validation scenario and make it VM-backed everywhere it needs to be:

- in `models.py`, extend `ScenarioName`, `CliTestScenarioName`, and the VM-backed scenario sets that need to know about `cli-stack`
- in `cli_test_catalog.py` and `cli_test_models.py`, expose `cli-stack` as a VM-backed CLI test that accepts function selection
- in `cli_test_runner.py`, make `cli-test run cli-stack` the canonical orchestration path
- in `flow_catalog.py`, add task-id resolution for the new CLI flow
- in `scenario_flows.py`, if needed, map an internal `cli-stack` flow to the helper-backed step list
- in `e2e_catalog.py`, `e2e_commands.py`, and `cli_e2e_commands.py`, only add the minimum internal plumbing required for `cli-test` to delegate into the shared orchestration flow

Create `cli_platform_workflow.py` with the CLI-specific tail builders so `host-platform` and `cli-stack` can share install/status/uninstall and verification primitives without sharing the same execution environment.

The `cli-stack` tail should prove the CLI end-to-end in this exact order:

- compile `nanofaas-cli` in the VM
- build and push selected function images
- use the CLI to install nanofaas into k3s
- verify the platform status
- apply or register the selected functions against the installed cluster
- list the selected functions
- invoke the selected functions synchronously
- enqueue the selected functions asynchronously
- delete the selected functions
- uninstall nanofaas and verify that the status check fails afterward

`helm-stack` should still reuse the same prefix but keep its current Helm deploy plus loadtest/autoscaling tail. Do not mirror its task order unless a shared helper genuinely justifies it.

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_catalog.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_flow_catalog.py -q
```

Expected: PASS, with `cli-stack` showing up in the catalogs and the flow plan matching the new tail.

**Step 3: Commit the scenario wiring**

```bash
git add tools/controlplane/src/controlplane_tool/cli_platform_workflow.py tools/controlplane/src/controlplane_tool/models.py tools/controlplane/src/controlplane_tool/e2e_catalog.py tools/controlplane/src/controlplane_tool/e2e_commands.py tools/controlplane/src/controlplane_tool/scenario_flows.py tools/controlplane/src/controlplane_tool/cli_e2e_commands.py tools/controlplane/src/controlplane_tool/cli_test_catalog.py tools/controlplane/src/controlplane_tool/cli_test_models.py tools/controlplane/src/controlplane_tool/cli_test_runner.py tools/controlplane/src/controlplane_tool/flow_catalog.py
git commit -m "Add cli-stack scenario wiring"
```

## Task 4: Make the host-platform path explicitly reusable

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_runtime.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`

**Step 1: Write the minimal compatibility change**

Make the host-driven `host-platform` runner and the VM-driven `cli-stack` runner both depend on the same shared platform-workflow primitives instead of carrying their own slightly different command assembly.

Keep `cli_runtime.py` as the compatibility re-export shim, but remove any remaining orchestration duplication from the concrete runners. This should make `host-platform` a thin facade over shared command builders and assertions, while `cli-stack` remains a VM-driven runner with its own execution semantics.

Add a regression test that proves:

- `CliHostPlatformRunner` still uses host execution plus kubeconfig targeting
- `CliVmRunner` and any new `cli-stack` VM runner still use VM execution for CLI validation
- both runners build from the same shared primitives where appropriate

**Step 2: Run the compatibility tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_runtime.py -q
```

Expected: PASS, with no regression in `host-platform` or the existing VM CLI flow.

**Step 3: Commit the compatibility refactor**

```bash
git add tools/controlplane/src/controlplane_tool/cli_host_runner.py tools/controlplane/src/controlplane_tool/cli_vm_runner.py tools/controlplane/src/controlplane_tool/cli_runtime.py tools/controlplane/tests/test_cli_runtime.py
git commit -m "Share host and VM CLI workflows"
```

## Task 5: Update docs, TUI labels, and entrypoint references

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`

**Step 1: Write the minimal documentation and UI updates**

Update the user-facing surfaces so the new scenario is understandable without reading code:

- add `cli-stack` to the CLI validation/TUI surfaces as the canonical deep CLI evaluation path
- document the difference between `host-platform`, `cli-stack`, and `helm-stack`
- add one example that uses `cli-stack` as the canonical CLI evaluation scenario
- update the entrypoint tables so `cli-stack` is shown under `cli-test` first, with any `e2e` mention clearly marked as internal plumbing if it remains exposed

Keep the docs honest: `helm-stack` remains the compatibility/loadtest flow, while `cli-stack` is the deeper CLI evaluation flow that reuses the same VM bootstrap.

**Step 2: Run the targeted docs/UI tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_canonical_entrypoints.py -q
```

Expected: PASS.

**Step 3: Commit the docs and UI pass**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py docs/testing.md tools/controlplane/README.md README.md tools/controlplane/tests/test_tui_choices.py tools/controlplane/tests/test_docs_links.py tools/controlplane/tests/test_canonical_entrypoints.py
git commit -m "Document cli-stack CLI evaluation flow"
```

## Task 6: Run the full verification sweep

**Files:**
- None

**Step 1: Run the full control-plane tooling test suite**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests -q
```

Expected: PASS.

**Step 2: Run any additional repo-level checks touched by the plan**

If the helper extraction or catalog changes expose a broken surface outside `tools/controlplane`, run the smallest relevant follow-up command instead of broadening the scope blindly.

**Step 3: Commit the final verification-only pass if any follow-up fix was needed**

```bash
git add -A
git commit -m "Finish cli-stack E2E workflow"
```
