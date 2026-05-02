# Stash Reapplication Audit Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Determine whether `stash@{0}` still contains any useful changes after the local fast-forward merge of external SSH VM support, and reapply only the non-redundant parts without overwriting `main`.

**Architecture:** Treat `stash@{0}` as an older patch source captured from dirty `main` before the merge. Do not `stash pop` again on `main`: inspect the stash in isolation, compare it against current `HEAD`, and recover only unmatched hunks or whole files via targeted checkout or manual patching. If no unique hunks remain, keep or drop the stash explicitly rather than replaying it blindly.

**Tech Stack:** Git stash, git diff/show, bash, pytest.

---

### Task 1: Freeze the current understanding of `stash@{0}`

**Files:**
- Modify: `docs/plans/2026-03-22-stash-reapplication-audit.md`
- Inspect: `stash@{0}`

**Step 1: Record the stash scope**

Run:

```bash
git stash show --stat stash@{0}
```

Expected current scope:

```text
scripts/e2e-cli-host-platform.sh
scripts/e2e-cli.sh
scripts/e2e-k3s-curl.sh
scripts/e2e-k3s-helm.sh
scripts/e2e-k8s-vm.sh
scripts/lib/e2e-k3s-common.sh
scripts/tests/test_e2e_k3s_common_external_ssh_mode.py
scripts/tests/test_e2e_runtime_contract.py
scripts/tests/test_e2e_runtime_runners.py
```

**Step 2: Confirm the stash is an early subset of the merged feature**

Run:

```bash
git stash show -p stash@{0} | rg "e2e_require_vm_access|E2E_VM_LIFECYCLE|e2e_get_host_tmp_dir|e2e_get_vm_host|Skipping VM deletion: external VM lifecycle mode"
```

Expected: the stash references only the early lifecycle and `/tmp` fixes, not the later `e2e_get_public_host`, `e2e_export_kubeconfig_to_host`, docs, or experiment updates.

**Step 3: Do not apply anything yet**

Stop after the scope is documented. Blind `stash pop` on `main` is forbidden because it already caused content conflicts on the merged files.

### Task 2: Compare the stash against current `main`

**Files:**
- Inspect: `stash@{0}`
- Inspect: current `HEAD`

**Step 1: Compare stash snapshot to merged `HEAD`**

Run:

```bash
git diff --stat HEAD stash@{0}
```

Expected: large differences because `HEAD` is a strict superset of the stash and includes later tasks (public host, kubeconfig export helper, docs, experiment rewiring).

**Step 2: Compare current `HEAD` to the stash base**

Run:

```bash
git diff --stat stash@{0}^1..HEAD
```

Expected: the full final feature delta, showing that `HEAD` moved well beyond the stash snapshot.

**Step 3: Inspect per-file uniqueness before recovery**

For each of the 9 stash files, run:

```bash
git diff HEAD stash@{0} -- <path>
```

Decision rule:
- If the only differences are that `HEAD` has more complete logic or broader tests, the stash file is redundant.
- If a hunk exists only in the stash and is still desirable, mark that hunk for targeted recovery in Task 3.

### Task 3: Recover only unique hunks, never the entire stash

**Files:**
- Modify only files that still have genuinely missing stash hunks

**Step 1: Create a temporary recovery branch**

Run:

```bash
git switch -c codex/stash-recovery-audit
```

Expected: recovery work does not happen directly on `main`.

**Step 2: Materialize the stash in a safe sandbox**

Preferred approach:

```bash
git stash branch codex/stash-sandbox stash@{0}
```

If that is inconvenient, use:

```bash
git show stash@{0}:<path>
```

to inspect single file snapshots without replaying the stash on `main`.

**Step 3: Reapply only selected hunks**

Allowed mechanisms:

```bash
git checkout stash@{0} -- <path>    # only if the whole file is still wanted
git restore --source=stash@{0} -- <path>
```

or manual patching for individual hunks.

Not allowed:

```bash
git stash pop
git stash apply
```

on `main` or on any branch that already contains `d8b014e`.

**Step 4: Prefer the merged implementation when there is overlap**

Current default assumption: the stash is redundant because `main` already contains the same early lifecycle changes plus later follow-up work. Only recover a stash hunk if it is demonstrably missing from `HEAD`.

### Task 4: Verify any recovered delta

**Files:**
- Test: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Test: `scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py`
- Test: `scripts/tests/test_e2e_runtime_contract.py`
- Test: `scripts/tests/test_e2e_runtime_runners.py`

**Step 1: Run the focused regression suite**

Run:

```bash
pytest -q scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py
```

Expected: all tests pass.

**Step 2: Run syntax checks if shell files changed**

Run:

```bash
bash -n scripts/lib/e2e-k3s-common.sh \
  scripts/e2e-all.sh \
  scripts/e2e-k8s-vm.sh \
  scripts/e2e-cli.sh \
  scripts/e2e-k3s-curl.sh \
  scripts/e2e-cli-host-platform.sh \
  scripts/e2e-k3s-helm.sh \
  experiments/e2e-loadtest.sh \
  experiments/e2e-loadtest-registry.sh \
  experiments/e2e-cold-start-metrics.sh \
  experiments/e2e-runtime-ab.sh \
  experiments/e2e-memory-ab.sh
```

Expected: no syntax errors.

### Task 5: Resolve the stash lifecycle explicitly

**Files:**
- Inspect: `stash@{0}`

**Step 1: If no unique hunks were recovered, keep the stash only as archival evidence or drop it intentionally**

Run one of:

```bash
git stash drop stash@{0}
```

or keep it and document that it is redundant.

**Step 2: If some hunks were recovered, create a small follow-up commit**

Run:

```bash
git add <recovered files>
git commit -m "Recover remaining stash-only VM E2E changes"
```

**Step 3: Never leave `main` half-restored from a failed stash pop**

Before finishing, verify:

```bash
git status --short
```

Expected:
- either clean working tree
- or only intentionally untracked planning files under `docs/plans/`
