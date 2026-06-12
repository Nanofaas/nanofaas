# Lean Loadtest Function Default Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The 4 loadtest scenarios (`helm-stack`, `two-vm-loadtest`, `azure-vm-loadtest`, `proxmox-vm-loadtest`) stop building all 8 function images by default and build only the 2 they actually exercise — measured saving ~17 minutes per run (the 2026-06-12 azure run spent ~25 min on function image builds, 6 of 8 images unused by k6).

**Architecture:** One-line behavior change: `_default_selection_for` in `cli/e2e_commands.py` switches the built-in default for those scenarios from the `demo-loadtest` preset (8 functions) to `demo-java` (2: `word-stats-java`, `json-transform-java`). The shared image planner (`function_image_specs`) already builds exactly `resolved_scenario.functions`, so nothing else changes. All explicit selection paths keep working: `--function-preset demo-loadtest`, `--functions` CSV, scenario files (the sample manifests still declare `demo-loadtest` — deliberate opt-in to the full matrix), saved profiles. The k6 target is unaffected: `two_vm_target_function` resolves `function_keys[0]` = `word-stats-java` with either preset (and the FU2 fix guarantees target ∈ selection).

**Tech Stack:** Python (`controlplane_tool`), pytest via `uv`.

---

## Conventions

- Repo root `/Users/micheleciavotta/Downloads/mcFaas`; feature branch (e.g. `perf/lean-loadtest-default`).
- Tests: `uv run --project tools/controlplane pytest <paths> -q`; full suite before commit (this suite is NOT in CI — local green is the only gate).
- Out of scope (explicit): the D-lever (gradle daemon / BuildKit / prune) and the sample manifests `tools/controlplane/scenarios/*-loadtest-java.toml` (they pin `demo-loadtest` explicitly — that's documented opt-in, see `test_scenario_loader.py:57` which must NOT change). The `demo-loadtest` preset itself stays in the catalog (`test_function_catalog.py:104` must NOT change).

## Known pinned expectations (verified by grep, 2026-06-12)

| Site | Today | After |
|---|---|---|
| `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py:287-288` (`_default_selection_for`) | `function_preset="demo-loadtest"` for the 4 scenarios | `"demo-java"` + comment |
| `tools/controlplane/tests/test_e2e_commands.py:108` (two-vm default request) | asserts `"demo-loadtest"` | asserts `"demo-java"` |
| `tools/controlplane/tests/test_tui_choices.py:2588` (helm-stack TUI default) and `:2631` | assert `"demo-loadtest"` | assert `"demo-java"` |
| `README.md:95`, `tools/controlplane/README.md:227`, `docs/testing.md:393` | describe `demo-loadtest` as helm-stack default | describe `demo-java` as lean default + how to opt back in |
| NOT touched: `test_scenario_loader.py:57` (manifest), `test_function_catalog.py:104` (preset exists), `test_tui_choices.py:1851` (profile fixture name), `README.md:49` / `tools/controlplane/README.md:50` / `docs/testing.md:372` (`show-preset demo-loadtest` examples) | — | — |

---

### Task 1: lean default + aligned tests

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py:287-288`
- Modify: `tools/controlplane/tests/test_e2e_commands.py:108`, `tools/controlplane/tests/test_tui_choices.py:2588,2631`
- Test (new): add to `tools/controlplane/tests/test_e2e_commands.py`

- [ ] **Step 1: Write the failing test** — add to `tools/controlplane/tests/test_e2e_commands.py` (it already imports `_resolve_run_request`; match the file's existing call style — copy the kwargs shape from the test at line ~95):

```python
import pytest


@pytest.mark.parametrize(
    "scenario",
    ["helm-stack", "two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"],
)
def test_loadtest_scenarios_default_to_lean_function_selection(scenario: str) -> None:
    # Default selection drives image builds (function_image_specs builds exactly
    # resolved_scenario.functions): the demo-loadtest preset meant 8 images
    # (~25 min in the VM) for a k6 run that exercises one function.
    request = _resolve_run_request(
        scenario=scenario,
        runtime="java",
        lifecycle="multipass" if scenario in {"helm-stack", "two-vm-loadtest"} else scenario.split("-")[0],
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="12G",
        disk="30G",
        cleanup_vm=True,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv=None,
        scenario_file=None,
        saved_profile=None,
        azure_resource_group="rg" if scenario == "azure-vm-loadtest" else None,
        azure_location="westeurope" if scenario == "azure-vm-loadtest" else None,
    )

    assert request.function_preset == "demo-java"
    assert [fn.key for fn in request.resolved_scenario.functions] == [
        "word-stats-java",
        "json-transform-java",
    ]
```

ADAPT plumbing to reality before running: (a) the `lifecycle` literal for azure/proxmox is `"azure"`/`"proxmox"` — if the expression above is awkward, use an explicit mapping dict; (b) `_resolve_run_request` may require extra proxmox kwargs — if the proxmox case demands credentials to resolve, pass dummy `proxmox_host="h", proxmox_node="n", proxmox_user="u", proxmox_password="p", proxmox_template_id=1`; (c) if the function objects expose the key under a different attribute, mirror the existing assertions in this file. The ASSERTIONS (preset `demo-java`, exactly those 2 function keys, for all 4 scenarios) are the requirement.

- [ ] **Step 2: Run to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py -q`
Expected: the 4 new parametrized cases FAIL (`'demo-loadtest' == 'demo-java'`); pre-existing tests still pass.

- [ ] **Step 3: Implement** — in `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`, `_default_selection_for`, replace:

```python
    if scenario in {"helm-stack", "two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"}:
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-loadtest")
```

with:

```python
    if scenario in {"helm-stack", "two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"}:
        # Lean default: 2 images instead of demo-loadtest's 8 (~17 min of in-VM
        # builds saved per run; k6 exercises word-stats-java either way). Pass
        # --function-preset demo-loadtest (or a scenario file) for the full matrix.
        return ScenarioSelectionConfig(base_scenario=scenario, function_preset="demo-java")
```

- [ ] **Step 4: Align the 3 pinned default-assertions** (intended behavior change, NOT weakening):
- `tools/controlplane/tests/test_e2e_commands.py:108`: `assert request.function_preset == "demo-loadtest"` → `== "demo-java"`
- `tools/controlplane/tests/test_tui_choices.py:2588` and `:2631`: same substitution.
Do NOT touch `test_scenario_loader.py:57` (scenario-file manifest, explicit opt-in), `test_function_catalog.py:104` (preset existence), `test_tui_choices.py:1851` (fixture name).

- [ ] **Step 5: Run the new test + the two touched files, then the FULL suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_commands.py tools/controlplane/tests/test_tui_choices.py -q` → PASS
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → 100% green. If OTHER tests fail on `demo-loadtest` expectations the grep missed, inspect each: default-assertions get the same `demo-java` substitution; explicit-selection tests must not change.

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane
git commit -m "perf(e2e): loadtest scenarios default to demo-java (2 images instead of 8)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: docs — default claims + opt-in pointer

**Files:**
- Modify: `README.md:95`, `tools/controlplane/README.md:227`, `docs/testing.md:393`

- [ ] **Step 1: Update the three "default" claims** (the `show-preset demo-loadtest` command examples elsewhere in these files stay — the preset still exists):

`README.md:95` — replace the clause "`helm-stack` defaults to the backend-safe `demo-loadtest` preset, and unsupported Go selections are rejected before the compatibility backend runs." with:

```
`helm-stack` and the VM loadtest scenarios default to the lean `demo-java` preset (2 function images instead of 8 — pass `--function-preset demo-loadtest` or a scenario file for the full matrix), and unsupported Go selections are rejected before the compatibility backend runs.
```

`tools/controlplane/README.md:227` — replace the bullet with:

```
- `helm-stack` and the VM loadtest scenarios default to the lean `demo-java` preset (2 images; use `--function-preset demo-loadtest` for the full 8-function matrix, which still excludes Go because the Helm/loadtest compatibility backend does not exercise Go).
```

`docs/testing.md:393` — replace the bullet with:

```
- `helm-stack` and loadtest built-in defaults resolve the lean `demo-java` preset (2 function images); pass `--function-preset demo-loadtest` for the full matrix — both presets exclude unsupported Go functions.
```

- [ ] **Step 2: Sanity & commit**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` (docs-only change, but this repo has bitten us before — cheap insurance) → green.

```bash
git add README.md tools/controlplane/README.md docs/testing.md
git commit -m "docs: lean demo-java default for loadtest scenarios (opt-in to demo-loadtest)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests -q` — green
- [ ] Dry-run sanity (no VM): `scripts/controlplane.sh e2e run two-vm-loadtest --dry-run` → the plan must list build/push/prune steps for ONLY `word-stats-java` and `json-transform-java` (6 image steps, not 24)
- [ ] Explicit opt-in still works: `scripts/controlplane.sh e2e run two-vm-loadtest --function-preset demo-loadtest --dry-run` → 8 functions back
- [ ] Real saving measured at the next azure/multipass loadtest run (expected: step list shrinks from 48 to ~30, ~17 min less)
