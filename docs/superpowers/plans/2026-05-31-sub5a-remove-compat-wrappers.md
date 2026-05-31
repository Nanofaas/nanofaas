# Sub-5/6-a — Remove bash compatibility-wrapper scripts (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the 9 bash compatibility-wrapper scripts and repoint every live consumer (a Gradle task, two experiment scripts, and the docs) to the single canonical launcher `scripts/controlplane.sh`.

**Architecture:** Repoint-then-delete. First update the consumers (Gradle `commandLine`, two band-C experiment scripts, all live docs) so nothing breaks, then delete the wrappers, then grep-verify no live reference remains. No Python/tests change — verification is grep + `bash -n` + Gradle task resolution.

**Tech Stack:** bash, Gradle, Markdown docs.

**Baseline:** branch `refactor/wt-sub5a-remove-compat-wrappers` (already created). Spec: `docs/superpowers/specs/2026-05-31-remove-compat-wrapper-scripts-design.md`. The launchers `scripts/controlplane.sh` and `scripts/fn-init.sh` STAY. Band-B (`e2e-loadtest.sh`, `build-push-images.sh`, `native-build.sh`, `k6/run-all.sh`, `experiments/run.sh`) and band-C (the big experiment scripts) are NOT deleted here — keep all their references intact.

**The 9 wrappers to delete and their `controlplane.sh` equivalents:**

| Wrapper (delete) | Replacement |
|---|---|
| `scripts/e2e.sh` | `scripts/controlplane.sh e2e run docker` |
| `scripts/e2e-all.sh` | `scripts/controlplane.sh e2e all` |
| `scripts/e2e-k3s-junit-curl.sh` | `scripts/controlplane.sh e2e run k3s-junit-curl` |
| `scripts/e2e-k3s-helm.sh` | `scripts/controlplane.sh e2e run helm-stack` |
| `scripts/e2e-container-local.sh` | `scripts/controlplane.sh e2e run container-local` |
| `scripts/e2e-buildpack.sh` | `scripts/controlplane.sh e2e run buildpack` |
| `scripts/control-plane-build.sh` | `scripts/controlplane.sh` |
| `scripts/control-plane-building.sh` | `scripts/controlplane.sh` |
| `scripts/controlplane-tool.sh` | `scripts/controlplane.sh tui` |

---

### Task 1: Repoint the code/build consumers

**Files:**
- Modify: `build.gradle:70`
- Modify: `experiments/e2e-memory-ab.sh:205`
- Modify: `experiments/e2e-runtime-ab.sh:128`

- [ ] **Step 1: Repoint the Gradle `k8sE2eVm` task**

In `build.gradle`, the task `k8sE2eVm` (registered at line 68) has at line 70:
```gradle
    commandLine 'bash', 'scripts/e2e-k3s-junit-curl.sh'
```
Change it to:
```gradle
    commandLine 'bash', 'scripts/controlplane.sh', 'e2e', 'run', 'k3s-junit-curl'
```

- [ ] **Step 2: Repoint the two band-C experiment scripts**

In `experiments/e2e-memory-ab.sh`, line 205 is:
```bash
        bash "${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh"
```
Change it to:
```bash
        bash "${PROJECT_ROOT}/scripts/controlplane.sh" e2e run helm-stack
```

In `experiments/e2e-runtime-ab.sh`, line 128 is identical:
```bash
        bash "${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh"
```
Change it to:
```bash
        bash "${PROJECT_ROOT}/scripts/controlplane.sh" e2e run helm-stack
```

- [ ] **Step 3: Verify the edited scripts are syntactically valid**

Run: `bash -n experiments/e2e-memory-ab.sh && bash -n experiments/e2e-runtime-ab.sh && echo OK`
Expected: `OK`

- [ ] **Step 4: Verify the Gradle task still resolves with the new commandLine**

Run: `./gradlew help --task k8sE2eVm 2>&1 | tail -20`
Expected: prints the task help (`Detailed task information for k8sE2eVm`) with no configuration error. (Do NOT actually run the E2E task — it needs a VM.)

- [ ] **Step 5: Commit**

```bash
git add build.gradle experiments/e2e-memory-ab.sh experiments/e2e-runtime-ab.sh
git commit -m "build: point k8sE2eVm + AB experiments at scripts/controlplane.sh"
```

---

### Task 2: Update the live docs

**Files:**
- Modify: `GEMINI.md`
- Modify: `AGENTS.md`
- Modify: `docs/testing.md`
- Modify: `tooling/controlplane_tui/README.md`

- [ ] **Step 1: Substitute every deleted-wrapper path in the live docs**

Run this from the repo root. It rewrites each wrapper path to its `controlplane.sh` equivalent across the four live doc files. The dots are escaped so `scripts/e2e.sh` does not match `scripts/e2e-loadtest.sh` (band B, kept) and `control-plane-build.sh` does not match `control-plane-building.sh`:

```bash
for f in GEMINI.md AGENTS.md docs/testing.md tooling/controlplane_tui/README.md; do
  perl -i -pe '
    s{scripts/e2e-k3s-junit-curl\.sh}{scripts/controlplane.sh e2e run k3s-junit-curl}g;
    s{scripts/e2e-k3s-helm\.sh}{scripts/controlplane.sh e2e run helm-stack}g;
    s{scripts/e2e-container-local\.sh}{scripts/controlplane.sh e2e run container-local}g;
    s{scripts/e2e-buildpack\.sh}{scripts/controlplane.sh e2e run buildpack}g;
    s{scripts/e2e-all\.sh}{scripts/controlplane.sh e2e all}g;
    s{scripts/e2e\.sh}{scripts/controlplane.sh e2e run docker}g;
    s{scripts/control-plane-building\.sh}{scripts/controlplane.sh}g;
    s{scripts/control-plane-build\.sh}{scripts/controlplane.sh}g;
    s{scripts/controlplane-tool\.sh}{scripts/controlplane.sh tui}g;
  ' "$f"
done
```

- [ ] **Step 2: Remove the now self-referential "wrapper over" line in `docs/testing.md`**

After Step 1, `docs/testing.md` contains a line that reads (the `e2e-k3s-helm.sh` was rewritten):
```
`scripts/controlplane.sh e2e run helm-stack` is a wrapper over `scripts/controlplane.sh e2e run helm-stack`.
```
Delete that entire line (it described the now-deleted wrapper). Leave the adjacent line about `scripts/e2e-loadtest.sh` (band B — kept) untouched.

Run to confirm the self-referential line is gone:
`grep -n "is a wrapper over .scripts/controlplane.sh e2e run helm-stack" docs/testing.md`
Expected: EMPTY.

- [ ] **Step 3: Verify no deleted-wrapper reference remains in the live docs**

Run:
```bash
grep -rn "scripts/e2e\.sh\|scripts/e2e-all\.sh\|scripts/e2e-k3s-junit-curl\.sh\|scripts/e2e-k3s-helm\.sh\|scripts/e2e-container-local\.sh\|scripts/e2e-buildpack\.sh\|scripts/control-plane-build\.sh\|scripts/control-plane-building\.sh\|scripts/controlplane-tool\.sh" \
  GEMINI.md AGENTS.md docs/testing.md tooling/controlplane_tui/README.md CLAUDE.md README.md
```
Expected: EMPTY. (`e2e-loadtest.sh` references must still be present — they are band B; confirm with `grep -c "e2e-loadtest.sh" docs/testing.md` returns a non-zero count.)

- [ ] **Step 4: Commit**

```bash
git add GEMINI.md AGENTS.md docs/testing.md tooling/controlplane_tui/README.md
git commit -m "docs: reference scripts/controlplane.sh instead of compat wrappers"
```

---

### Task 3: Delete the wrapper scripts and final verification

**Files:**
- Delete: `scripts/e2e.sh`, `scripts/e2e-all.sh`, `scripts/e2e-k3s-junit-curl.sh`, `scripts/e2e-k3s-helm.sh`, `scripts/e2e-container-local.sh`, `scripts/e2e-buildpack.sh`, `scripts/control-plane-build.sh`, `scripts/control-plane-building.sh`, `scripts/controlplane-tool.sh`

- [ ] **Step 1: Delete the 9 wrappers**

```bash
rm scripts/e2e.sh scripts/e2e-all.sh scripts/e2e-k3s-junit-curl.sh \
   scripts/e2e-k3s-helm.sh scripts/e2e-container-local.sh scripts/e2e-buildpack.sh \
   scripts/control-plane-build.sh scripts/control-plane-building.sh \
   scripts/controlplane-tool.sh
```

- [ ] **Step 2: Confirm the launchers survive**

Run: `ls scripts/controlplane.sh scripts/fn-init.sh`
Expected: both listed (no error).

- [ ] **Step 3: Final grep — no live reference to any deleted wrapper anywhere**

Run (excludes the frozen experiment snapshots, node_modules, .git):
```bash
grep -rn "e2e\.sh\|e2e-all\.sh\|e2e-k3s-junit-curl\.sh\|e2e-k3s-helm\.sh\|e2e-container-local\.sh\|e2e-buildpack\.sh\|control-plane-build\.sh\|control-plane-building\.sh\|controlplane-tool\.sh" . \
  --include="*.md" --include="*.sh" --include="*.gradle" --include="*.yml" --include="*.yaml" \
| grep -v "experiments/control-plane-staging/versions/" | grep -v "node_modules\|\.git/"
```
Expected: EMPTY. (If a frozen snapshot under `experiments/control-plane-staging/versions/` still mentions a wrapper, that is fine and intentionally untouched — the filter above excludes it.)

- [ ] **Step 4: Sanity-check the launcher still works**

Run: `bash scripts/controlplane.sh --help 2>&1 | head -5`
Expected: the controlplane-tool help output (no "No such file" / launcher error).

- [ ] **Step 5: Commit**

```bash
git add -A scripts/
git commit -m "chore: delete bash compatibility-wrapper scripts (use scripts/controlplane.sh)"
```

---

## Self-Review

- **Spec coverage:** delete-9-wrappers = Task 3; repoint build.gradle = Task 1 Step 1; repoint band-C experiment scripts = Task 1 Step 2; update docs (GEMINI/AGENTS/testing.md/TUI README) = Task 2; the "wrapper over" prose line = Task 2 Step 2; keep launchers + band-B `e2e-loadtest.sh` = explicit in Task 2 Step 3 / Task 3 Step 2; don't touch frozen snapshots = Task 3 Step 3 filter; verification (grep + `bash -n` + gradle resolve + launcher --help) = Task 1 Step 3-4, Task 2 Step 3, Task 3 Step 3-4. ✓ No gaps.
- **Placeholder scan:** none — exact old/new strings, exact rm/grep/perl commands, exact expected outputs.
- **Consistency:** the same 9 wrapper→replacement mappings are used identically in the delete list, the doc substitution (Task 2 Step 1), and the verification greps. `e2e-loadtest.sh` is consistently preserved (band B). ✓
