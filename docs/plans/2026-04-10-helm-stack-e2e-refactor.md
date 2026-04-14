# Helm Stack E2E Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Follow `@superpowers:test-driven-development` for every code change and keep commits small.

**Goal:** Allineare `helm-stack` a `k3s-junit-curl`, condividendo il prelude VM/k3s/Helm e lasciando alla coda solo loadtest e autoscaling.

**Architecture:** `E2eRunner` deve essere la singola fonte di verita` per i flow VM-backed. La parte comune della pipeline vive in un helper riusabile da `k3s-junit-curl` e `helm-stack`; la divergenza arriva solo dopo il deploy e il readiness check. `scenario_flows.py` deve limitarsi a costruire il request object e a collegare il flow giusto, senza reimplementare bootstrap, Helm o orchestration.

**Tech Stack:** Python 3.12, `uv`, `pytest`, Pydantic, `controlplane_tool` helpers.

---

## Scope

This plan targets the control-plane tooling E2E path:

- `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- `tools/controlplane/src/controlplane_tool/e2e_models.py`
- `tools/controlplane/src/controlplane_tool/helm_stack_runner.py`
- `tools/controlplane/src/controlplane_tool/k3s_runtime.py`
- `tools/controlplane/tests/test_e2e_runner.py`
- `tools/controlplane/tests/test_scenario_flows.py`
- `tools/controlplane/tests/test_k3s_runtime.py`
- `tools/controlplane/tests/test_flow_catalog.py`

Non-goals:

- no reintroduction of shell-based orchestration
- no change to scenario names
- no rewrite of unrelated VM orchestration
- no behavior change for `k3s-junit-curl` beyond extracting shared helpers

### Task 1: Lock down the desired `helm-stack` shape in tests

**Files:**
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`

**Step 1: Write the failing tests**

Add tests that describe the target behavior before touching implementation:

- `test_helm_stack_plan_shares_k3s_junit_curl_prelude`
- `test_helm_stack_plan_adds_structured_loadtest_tail`
- `test_helm_stack_flow_shares_k3s_junit_curl_prefix`
- `test_helm_stack_flow_routes_through_python_e2e_runner`

The assertions should cover:

- the first 13 `ScenarioPlanStep.summary` values are shared with `k3s-junit-curl`
- the tail for `helm-stack` is exactly:
  - `Run loadtest via Python runner`
  - `Run autoscaling experiment (Python)`
- `build_scenario_flow("helm-stack")` exposes the shared task IDs in the same order
- the flow runs through `E2eRunner`, not `HelmStackRunner`

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_scenario_flows.py -q
```

Expected: FAIL with mismatches in `helm-stack` step summaries, task IDs, or flow routing.

**Step 3: Commit the red tests**

```bash
git add tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_scenario_flows.py
git commit -m "Add helm-stack E2E regression tests"
```

### Task 2: Extract the shared VM prelude and split the scenario tails

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`

**Step 1: Write the minimal implementation**

Refactor the VM-backed plan builder so that:

- `_k3s_vm_prelude_steps(request)` returns the shared start of the pipeline:
  - ensure VM is running
  - provision base VM dependencies
  - sync the project to the VM
  - ensure registry container
  - build control-plane and runtime images
  - build selected function images
  - install k3s
  - configure k3s registry
  - ensure the E2E namespace exists
  - deploy control-plane via Helm
  - deploy function-runtime via Helm
  - wait for control-plane deployment
  - wait for function-runtime deployment
- `_k3s_junit_curl_tail_steps(request)` owns verification and cleanup:
  - `Run k3s-junit-curl verification`
  - `Run K8sE2eTest in VM`
  - uninstall releases
  - delete namespace
  - teardown VM
- `_helm_stack_tail_steps(request)` owns only the helm-stack-specific tail:
  - `Run loadtest via Python runner`
  - `Run autoscaling experiment (Python)`
- `_vm_backed_steps(request)` dispatches:
  - `k3s-junit-curl` => shared prelude + junit/curl tail
  - `helm-stack` => shared prelude + helm-stack tail
  - other VM-backed scenarios keep their existing bootstrap path

Keep the existing CLI paths unchanged, except for fixing any broken env wiring while you are there.

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_runner.py -q
```

Expected: PASS, with the `helm-stack` plan now matching the shared prelude and structured tail.

**Step 3: Commit the runner refactor**

```bash
git add tools/controlplane/src/controlplane_tool/e2e_runner.py
git commit -m "Share helm-stack and k3s E2E prelude"
```

### Task 3: Route `helm-stack` through `E2eRunner` in the flow layer

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`

**Step 1: Write the minimal implementation**

Update the `helm-stack` branch so that it:

- builds an `E2eRequest`
- reads the VM from `vm_request_from_env()`
- preserves `namespace`, `local_registry`, and `runtime`
- carries the `noninteractive` flag into the request
- disables VM cleanup for this flow if that is the intended compatibility behavior
- calls `E2eRunner(repo_root).run(...)`

Update `_SCENARIO_TASK_IDS_MAP["helm-stack"]` to match the shared prelude plus the two-step tail, in this order:

- `vm.ensure_running`
- `vm.provision_base`
- `repo.sync_to_vm`
- `registry.ensure_container`
- `images.build_core`
- `images.build_selected_functions`
- `k3s.install`
- `k3s.configure_registry`
- `k8s.ensure_namespace`
- `helm.deploy_control_plane`
- `helm.deploy_function_runtime`
- `k8s.wait_control_plane_ready`
- `k8s.wait_function_runtime_ready`
- `loadtest.run`
- `experiments.autoscaling`

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_flows.py -q
```

Expected: PASS, with the flow wiring going through `E2eRunner`.

**Step 3: Commit the flow refactor**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_flows.py
git commit -m "Route helm-stack flow through E2eRunner"
```

### Task 4: Preserve compatibility knobs and make the request model explicit

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Inspect: `tools/controlplane/src/controlplane_tool/helm_stack_runner.py`
- Inspect: `tools/controlplane/src/controlplane_tool/k3s_runtime.py`

**Step 1: Write the minimal compatibility change**

If the flow now needs a way to carry the old `noninteractive` behavior, add it to `E2eRequest` as an explicit field, then pass it through to the helm-stack tail env so the same flag still reaches the Python runner path.

Keep `HelmStackRunner` importable for backwards compatibility, but stop using it as the primary flow entrypoint.

Add a regression test that proves `build_scenario_flow("helm-stack", noninteractive=False)` preserves the request value rather than hardcoding the old default.

**Step 2: Run the compatibility tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_models.py tools/controlplane/tests/test_k3s_runtime.py -q
```

Expected: PASS, with no regression in the existing runtime compatibility checks.

**Step 3: Commit the compatibility work**

```bash
git add tools/controlplane/src/controlplane_tool/e2e_models.py tools/controlplane/tests/test_scenario_flows.py
git commit -m "Preserve helm-stack compatibility flags"
```

### Task 5: Run the full control-plane test sweep and align any catalog text

**Files:**
- Inspect: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Inspect: `tools/controlplane/src/controlplane_tool/flow_catalog.py`
- Inspect: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify only if a label or description still refers to the old split behavior

**Step 1: Verify there are no stale labels or task lists**

Check whether any user-facing description still implies that `helm-stack` is a separate shell-driven workflow. If so, update only the wording, not the scenario names.

Also check the `Execution Phases` presentation for `helm-stack`: the current view is not informative because it only exposes the two tail tasks. The UI/catalog path should make the shared prelude visible so the phase breakdown matches the real pipeline shape, instead of collapsing the scenario into "loadtest + autoscaling".

**Step 2: Run the full package test suite**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest tools/controlplane/tests -q
```

Expected: PASS.

**Step 3: Commit the final sweep**

```bash
git add tools/controlplane/src/controlplane_tool/e2e_catalog.py tools/controlplane/src/controlplane_tool/flow_catalog.py tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests
git commit -m "Finish helm-stack E2E refactor"
```
