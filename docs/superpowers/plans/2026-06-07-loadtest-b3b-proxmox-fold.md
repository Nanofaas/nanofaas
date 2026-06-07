# Phase B3b — Fold proxmox into the unified flow (characterization-first) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route the proxmox loadtest scenario through the unified `run_loadtest_flow` driver (from B3a), retiring proxmox's bespoke `_run_prelude_workflow`/`_run_tail_tasks`/`_tail_tasks`/`_skeleton`/`_ActionTask` machinery — WITHOUT changing observable behavior, guarded by characterization tests written FIRST (proxmox cannot be validated on real hardware).

**Architecture:** Phase 1 writes characterization tests that pin proxmox's CURRENT behavior the argv golden doesn't cover: the full `ScenarioStepEvent` emission sequence, failure-cleanup (VM teardown on exception), and in-prelude registration + port-publish. Phase 2 generalizes the driver/adapter so they can reproduce that behavior (adapter-supplied recipe + special_handler + context_selector + register strategy + extra_steps + an event-emission strategy + a failure-cleanup hook + `extra_step_titles`), keeping two-vm byte-identical. Phase 3 adds `ProxmoxLoadtestAdapter` and routes proxmox through the driver. Phase 4 verifies + sweeps the retired machinery.

**Tech Stack:** Python 3.12, `uv`, pytest. `controlplane_tool.scenario.loadtest_flow` / `loadtest_adapter` (from B3a), `ProxmoxConnectivity` (from B1b), proxmox test fakes.

**Decisions (locked):**
- User chose **characterization-tests-first** for this unvalidatable fold (brainstorm risk question, 2026-06-07).
- Two-vm (multipass, B3a) must stay **byte-identical** — its `argv`/`task_ids`/`phase_titles` goldens AND its current event behavior (driver ignores `event_listener`; progress flows via the global `WorkflowEvent`/`workflow_step` bus) must not change. The new ScenarioStepEvent emission is **opt-in per adapter** (proxmox opts in; multipass does not).

**Spec:** `docs/superpowers/specs/2026-06-07-loadtest-b3-final-collapse-design.md`.

**Validation gate:** proxmox CANNOT be validated on real hardware (no Proxmox). The characterization tests + argv golden are the guarantee. The PR MUST flag proxmox as argv+characterization-guarded but real-VM-unvalidated. (multipass two-vm must still pass its existing real-VM gate — unaffected here.)

---

## Reference: proxmox's behaviors to preserve (the contract)

From `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`:

1. **Prelude recipe differs** — `_PROXMOX_LOADTEST_PRELUDE_COMPONENTS` includes `vm.ensure_running` (run separately as task_id `vm.ensure_running`), and `cli.build_install_dist` + `cli.fn_apply_selected`. Registration happens IN the prelude: `special_handler` substitutes `cli.fn_apply_selected` → a `CallableTask("functions.register", ...)` that publishes the control-plane NAT port (`publish_port(service="CONTROL_PLANE_HTTP")`) and calls `RegisterFunctions(...).run()`. `context_selector` gives `cli.*` components a `CliComponentContext`. The prelude uses `ProxmoxConnectivity` (B1b) for the host-op rewrites.
2. **Event emission** — `run(event_listener)` emits `ScenarioStepEvent(step_index, total_steps, step, status in {running,success,failed}, error)`: prelude tasks via `_run_prelude_workflow` (indices 1..len(prelude)); tail tasks via `_run_tail_tasks` (offset = len(prelude)). `total_steps = len(self.task_ids)`. On task failure, emits `failed` then raises `RuntimeError("Scenario '...' failed at step '<title>': <exc>")`.
3. **Failure-cleanup** — on ANY prelude/tail exception, `_cleanup_proxmox_requests` tears down loadgen + stack VMs (if `cleanup_vm`); wraps cleanup errors into the raised message.
4. **Tail (lazy)** — ensure_stack→ensure_loadgen→publish_ports(prometheus)→install_k6→run_k6→fetch→prometheus→report, with NAT endpoints resolved lazily; cleanup destroys loadgen then stack. (B2 already routed the body construction through `build_loadgen_body_tasks`.)
5. **task_ids ordering** (`test_proxmox_vm_loadtest_plan_task_ids_include_platform_prefix`): `functions.register` < `vm.stack.publish_ports` < `loadgen.install_k6`. Note proxmox's static plan DOES include `vm.stack.publish_ports` (a `_SkeletonStep`) — so the adapter's `extra_step_ids(BEFORE_LOADGEN)` returns `["vm.stack.publish_ports"]` and `extra_step_titles` returns `["Publish Proxmox NAT ports"]`.

Existing tests already partially characterize this (`test_proxmox_vm_loadtest_cleans_up_vms_and_nat_when_prelude_fails`, `test_proxmox_vm_loadtest_tail_events_start_after_prelude`, the task_ids ordering test, `test_proxmox_prelude_argv.py`). Phase 1 EXTENDS them into a complete contract.

---

## File Structure

- **Modify** `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py` — add the comprehensive characterization tests (Phase 1).
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py` — generalize the driver: optional ScenarioStepEvent emission strategy, failure-cleanup hook, adapter-supplied recipe/special_handler/context_selector + register strategy, `extra_step_titles` in `phase_titles`.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py` — add the new optional adapter members (with no-op defaults preserving multipass), and `ProxmoxLoadtestAdapter`.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` — route through the driver; retire the bespoke machinery.

---

## Phase 1 — Characterization (NO production changes)

### Task 1: Pin proxmox's full ScenarioStepEvent sequence (success path)

**Files:**
- Test: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`

Extend the existing `test_proxmox_vm_loadtest_tail_events_start_after_prelude` fixture pattern into a test that captures the COMPLETE event sequence and asserts the exact ordered list of `(step_index, total_steps, step_id, status)` for a successful run.

- [ ] **Step 1: Write the characterization test**

Add `test_proxmox_event_sequence_is_pinned`. Reuse the existing fakes (`FakeProxmoxVmOrchestrator`, `FakeTwoVmLoadtestRunner`, `FakeEnsureVmRunning`, `FakeTask`, `fake_build_prelude_tasks` returning a single `prelude.noop` CallableTask, `fake_build_loadgen_body_tasks`) — copy them from `test_proxmox_vm_loadtest_tail_events_start_after_prelude` (lines ~217-320). Capture events and assert the full tuple list:

```python
def test_proxmox_event_sequence_is_pinned(monkeypatch, tmp_path) -> None:
    # ... identical fixture setup to test_proxmox_vm_loadtest_tail_events_start_after_prelude,
    # with cleanup_vm=False ...
    events = []
    plan.run(event_listener=events.append)

    seq = [(e.step_index, e.step.step_id, e.status) for e in events]
    # total_steps is constant across all events:
    assert {e.total_steps for e in events} == {len(plan.task_ids)}
    # Each step emits running then success, in order; prelude (index 1) then tail (offset=1).
    assert seq == [
        (1, "prelude.noop", "running"), (1, "prelude.noop", "success"),
        (2, "vm.stack.ensure_running", "running"), (2, "vm.stack.ensure_running", "success"),
        (3, "vm.loadgen.ensure_running", "running"), (3, "vm.loadgen.ensure_running", "success"),
        (4, "vm.stack.publish_ports", "running"), (4, "vm.stack.publish_ports", "success"),
        (5, "loadgen.install_k6", "running"), (5, "loadgen.install_k6", "success"),
        (6, "loadgen.run_k6", "running"), (6, "loadgen.run_k6", "success"),
        (7, "loadgen.fetch_results", "running"), (7, "loadgen.fetch_results", "success"),
        (8, "metrics.prometheus_snapshot", "running"), (8, "metrics.prometheus_snapshot", "success"),
        (9, "loadtest.write_report", "running"), (9, "loadtest.write_report", "success"),
    ]
```

IMPORTANT: This asserts the CURRENT behavior. Run it against the UNCHANGED proxmox code first — if the real emitted sequence differs (e.g. different step_ids/indices because the synthetic prelude is one task, or publish_ports index differs), ADJUST the expected list to match what the CURRENT code emits (this is characterization — pin reality, not the plan author's guess). Document the exact captured sequence in the test.

- [ ] **Step 2: Run against unchanged code; confirm it PASSES (pins reality)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py::test_proxmox_event_sequence_is_pinned -q`
Expected: PASS (it characterizes current behavior). If it fails, the expected list is wrong — fix the EXPECTED list to match the actual emission, not the code.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
git commit -m "test(proxmox): characterize the full ScenarioStepEvent sequence"
```

### Task 2: Pin failure-cleanup on BOTH prelude and tail failure

**Files:**
- Test: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`

`test_proxmox_vm_loadtest_cleans_up_vms_and_nat_when_prelude_fails` already pins prelude-failure cleanup. Add a tail-failure variant + assert the wrapped error message format.

- [ ] **Step 1: Write the test**

Add `test_proxmox_tail_failure_tears_down_vms`: reuse the fixture, but make a tail task raise (e.g. monkeypatch `fake_build_loadgen_body_tasks` so the `loadgen.run_k6` FakeTask's `.run()` raises `RuntimeError("boom")`), set `cleanup_vm=True`, record `teardown` calls on the fake orchestrator, and assert: (a) `run()` raises, (b) teardown was called for BOTH loadgen and stack requests, (c) the raised message matches the `Scenario '...' failed at step '...'` format (read the current `_run_tail_tasks`/`_run_prelude_workflow` to get the exact format and pin it).

```python
def test_proxmox_tail_failure_tears_down_vms(monkeypatch, tmp_path) -> None:
    # ... fixture as above but cleanup_vm=True; FakeProxmoxVmOrchestrator records teardown calls ...
    # make the run_k6 body task raise
    ...
    with pytest.raises(RuntimeError) as exc:
        plan.run(event_listener=lambda e: None)
    assert "failed at step" in str(exc.value)
    assert teardown_calls == ["proxmox-loadgen", "proxmox-stack"]  # order per _cleanup_proxmox_requests
```

Read the current code to get exact teardown order and message; pin what the code actually does.

- [ ] **Step 2: Run against unchanged code; confirm PASS**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py -q -k "tail_failure or cleans_up"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
git commit -m "test(proxmox): characterize failure-cleanup on tail failure"
```

---

## Phase 2 — Generalize the driver/adapter (two-vm stays byte-identical)

### Task 3: Adapter capabilities for events, failure-cleanup, recipe/registration (no-op defaults)

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Test: `tools/controlplane/tests/test_loadtest_adapter.py`

Add OPTIONAL members to the `LoadtestConnectivityAdapter` Protocol and implement no-op defaults on `MultipassLoadtestAdapter` so two-vm behavior is unchanged:
- `prelude_recipe(self)` — the recipe for the provision prelude. Multipass returns the `two-vm-loadtest-stack` recipe it builds today (move the recipe construction here from `loadtest_flow`/two-vm). Proxmox returns its filtered `proxmox-vm-loadtest` recipe.
- `prelude_special_handler(self, ctx)` → `SpecialHandler | None` (multipass: `None`).
- `prelude_context_selector(self, ctx)` → `Callable | None` (multipass: `None`).
- `register_functions(self, ctx) -> None` — multipass: the inline RegisterFunctions (moved from the driver's `_register_functions`); proxmox: **no-op** (registration is in the prelude). This replaces the driver's hardcoded register call.
- `emits_step_events(self) -> bool` — multipass `False` (keeps ignoring `event_listener`); proxmox `True`.
- `cleanup_on_failure(self, error: Exception) -> list[str]` — multipass: `[]` (no-op); proxmox: teardown VMs, return error strings.
- `extra_step_titles(self, phase) -> list[str]` — the B3a follow-up; multipass `[]`.
- `ensure_stack_task_id(self)` / `ensure_stack_title(self)` — multipass: `("vm.stack.ensure_running", "Ensure stack VM running")`. Proxmox runs the stack ensure as task_id `vm.ensure_running` silently in the prelude phase but the DISPLAYED/event id is `vm.stack.ensure_running` — capture proxmox's exact naming here (verify against the characterization test from Task 1).

- [ ] **Step 1: Write failing tests** asserting `MultipassLoadtestAdapter` no-op defaults: `emits_step_events() is False`, `cleanup_on_failure(Exception()) == []`, `prelude_special_handler(ctx) is None`, `prelude_context_selector(ctx) is None`, `extra_step_titles(phase) == []`, and `register_functions` is callable. (Construct the adapter with `SimpleNamespace` fakes as in the existing adapter tests.)

- [ ] **Step 2: Run → fail.** `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_adapter.py -q`

- [ ] **Step 3: Implement** the Protocol additions + `MultipassLoadtestAdapter` no-op defaults. Move the `two-vm-loadtest-stack` `ScenarioRecipe` construction into `MultipassLoadtestAdapter.prelude_recipe()`, and move the inline-RegisterFunctions logic (currently the driver's `_register_functions`, which reads `setup.context.local_registry` + `selected_functions` + `function_image`) into `MultipassLoadtestAdapter.register_functions(ctx)` — it needs `setup`/`request`; store them on the adapter (the adapter already holds `runner`/`request`; pass `setup` via the driver or have the adapter rebuild it). KEEP the exact behavior. Verify `function_image`/`selected_functions` import paths.

- [ ] **Step 4: Run → pass.** ruff clean.

- [ ] **Step 5: Commit** `feat(loadtest): adapter capabilities for events/cleanup/recipe/register (no-op multipass defaults)`.

### Task 4: Generalize `run_loadtest_flow` + static plan to use the adapter capabilities

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Test: `tools/controlplane/tests/test_loadtest_flow.py`, and the two-vm goldens (must stay green)

Rework the driver so it:
- Builds the prelude from `adapter.prelude_recipe()` with `adapter.prelude_special_handler(ctx)` + `adapter.prelude_context_selector(ctx)` passed to `build_command_tasks`.
- Calls `adapter.register_functions(ctx)` instead of the hardcoded `_register_functions` (multipass: inline REST; proxmox: no-op).
- Emits `ScenarioStepEvent`s when `adapter.emits_step_events()` is True, wrapping each executed task with running/success/failed + sequential `step_index` and `total_steps = len(task_ids)`, matching the format pinned in Task 1/2. When False (multipass), uses the existing native `workflow_step` path unchanged (two-vm byte-identical).
- Wraps the whole flow in try/except calling `adapter.cleanup_on_failure(exc)` and re-raising with the wrapped message (matching Task 2's pinned format) — a no-op for multipass (`cleanup_on_failure` returns `[]`, so the wrapper just re-raises the original).
- `loadtest_flow_phase_titles` injects `adapter.extra_step_titles(phase)` at the same points `loadtest_flow_task_ids` injects `extra_step_ids` (fixes the B3a divergence).

- [ ] **Step 1: Write tests** — (a) a driver test with a fake adapter where `emits_step_events()` is True asserting the running/success/index/total_steps wrapping; (b) a driver test where `cleanup_on_failure` is invoked on a raised task and the message is wrapped; (c) extend the multipass static-plan test to confirm `extra_step_titles` injection aligns titles with ids when the fake adapter returns a publish step.

- [ ] **Step 2: Run → fail.**

- [ ] **Step 3: Implement** the generalization. CRITICAL: when `emits_step_events()` is False, the code path must be EXACTLY B3a's (native `workflow_step`, `event_listener` unused) so two-vm stays byte-identical. Structure the event emission as a wrapper applied only when the adapter opts in.

- [ ] **Step 4: Run the new tests AND the two-vm goldens** — `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py -q`. Expected: PASS, two-vm unchanged. ruff clean.

- [ ] **Step 5: Commit** `feat(loadtest): driver emits ScenarioStepEvents + failure-cleanup + adapter recipe/register (opt-in; multipass unchanged)`.

---

## Phase 3 — ProxmoxLoadtestAdapter + route proxmox

### Task 5: `ProxmoxLoadtestAdapter`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Test: `tools/controlplane/tests/test_loadtest_adapter.py`

Implement `ProxmoxLoadtestAdapter` supplying proxmox's pieces (transcribe from `proxmox_vm_loadtest.py`): `connectivity = ProxmoxConnectivity(...)` (resolved endpoint), `prelude_recipe()` = the filtered `proxmox-vm-loadtest` recipe, `prelude_special_handler`/`prelude_context_selector` = the `cli.fn_apply_selected`→register substitution + `CliComponentContext` selector, `register_functions` = no-op (in prelude), `emits_step_events()` = True, `cleanup_on_failure(exc)` = teardown loadgen+stack (the `_cleanup_proxmox_requests` logic), `extra_steps(BEFORE_LOADGEN, ctx)` = publish prometheus NAT port (+ set `ctx.prometheus_url`), `extra_step_ids(BEFORE_LOADGEN)` = `["vm.stack.publish_ports"]`, `extra_step_titles(BEFORE_LOADGEN)` = `["Publish Proxmox NAT ports"]`, `loadgen_install_endpoint(ctx)` from `ssh_endpoint` (WITH port), `control_plane_url`/`prometheus_url` from the published NAT host/ports, `title_suffix = " (Proxmox)"`.

- [ ] Tests for the adapter's resolution (endpoint with port, title_suffix, extra_step_ids/titles, emits_step_events True, cleanup_on_failure calls teardown). TDD: write, fail, implement, pass, ruff, commit `feat(loadtest): add ProxmoxLoadtestAdapter`.

### Task 6: Route proxmox through the driver; retire the bespoke machinery

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
- Tests: the Phase-1 characterization tests + `test_proxmox_prelude_argv.py` + `test_proxmox_vm_loadtest_plan.py` (all stay green)

- [ ] **Step 1: baseline** — run all proxmox tests; confirm green.
- [ ] **Step 2:** replace `run()` with delegation to `run_loadtest_flow(runner, request, setup, recipe=adapter.prelude_recipe(), adapter=ProxmoxLoadtestAdapter(...), event_listener=event_listener)`; replace `task_ids`/`phase_titles` with delegation to `loadtest_flow_task_ids`/`loadtest_flow_phase_titles`.
- [ ] **Step 3:** DELETE the retired machinery: `_run_prelude_workflow`, `_run_tail_tasks`, `_tail_tasks`, `_tail_step`, `_emit_tail_event`, `_run_tail_task`, `_skeleton`, `_ActionTask`, `_SkeletonStep`, `_cleanup_proxmox_requests` (logic moved to the adapter), `_register_functions_action` (moved to the adapter's special_handler). KEEP `_build_prelude_tasks` IF `test_proxmox_prelude_argv.py` calls it directly (check — it likely does via `prelude_tasks`); if the adapter now owns prelude building, route the argv golden through the adapter and keep a thin shim, OR keep `_build_prelude_tasks` and have the adapter delegate to it. Decide to keep `test_proxmox_prelude_argv.py` UNCHANGED and green — if that requires keeping `_build_prelude_tasks`/`prelude_tasks`, keep them and have the adapter's `prelude_special_handler`/`context_selector`/recipe mirror them (or call them).
- [ ] **Step 4:** run characterization tests (Task 1/2) + argv golden + plan tests. ALL must pass unchanged. If the event sequence differs, the driver/adapter is wrong — fix it, do NOT edit the characterization assertions. (Source-inspection guards like `test_proxmox_loadgen_install_uses_runplaybook_not_bash` may need the same retarget as B2/B3a — authorized, preserve the `InstallK6(`-absent invariant.)
- [ ] **Step 5:** ruff clean; commit `refactor(proxmox): route run() through run_loadtest_flow; retire bespoke machinery`.

---

## Phase 4 — Verify + sweep

### Task 7: Full-suite verification

- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → PASS.
- [ ] `git diff main --stat -- tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py` → EMPTY (two-vm + azure untouched by B3b).
- [ ] Confirm proxmox file shrank substantially (retired machinery gone): `wc -l tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`.
- [ ] ruff clean on all touched files.
- [ ] Commit any cleanup.

---

## Real-VM Validation (post-merge — NOT possible for proxmox)

- [ ] Proxmox: NO real hardware — ships argv-golden + characterization-guarded, **flagged unvalidated in the PR**.
- [ ] multipass two-vm: unaffected by B3b, but re-run its real-VM gate opportunistically since the shared driver changed (Phase 2).

---

## Self-Review Notes

- **Spec coverage:** retire proxmox machinery (§ spec B3b) → Tasks 5-6; characterization-first (user decision) → Tasks 1-2; driver generalization (events/cleanup/recipe/register/extra_step_titles) → Tasks 3-4. The B3a `extra_step_titles` follow-up is fixed in Task 4.
- **two-vm byte-identity** is the hard invariant for Phase 2: the `emits_step_events()=False` path must be exactly B3a's. Tasks 4 + 7 gate it via the unchanged two-vm goldens + empty-diff check.
- **Characterization-pins-reality:** Tasks 1-2 assert CURRENT behavior — if an expected list mismatches, fix the EXPECTATION to the captured reality (then it guards the fold), never bend the code in Phase 1.
- **argv golden untouched:** `test_proxmox_prelude_argv.py` must stay green with an empty diff; Task 6 keeps `_build_prelude_tasks`/`prelude_tasks` if the golden depends on them.
- **Type consistency:** the adapter members added in Task 3 (`prelude_recipe`/`prelude_special_handler`/`prelude_context_selector`/`register_functions`/`emits_step_events`/`cleanup_on_failure`/`extra_step_titles`/`ensure_stack_task_id`) are implemented by both `MultipassLoadtestAdapter` (Task 3) and `ProxmoxLoadtestAdapter` (Task 5) and consumed by the driver (Task 4) with identical signatures.
