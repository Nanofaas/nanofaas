# Control Plane Tooling Milestone 7 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Retire the remaining structural duplication between the new `controlplane-tool` product and legacy script/doc entrypoints, so the repository converges on one canonical orchestration surface with only narrowly justified compatibility shims left in place.

**Architecture:** Treat `scripts/controlplane.sh` and `tools/controlplane/` as the only first-class user-facing orchestration surface. Remove or demote legacy entrypoints, migrate docs and CI to canonical commands, and add tests that fail if stale wrappers or references reappear. Keep low-level backend scripts only when they remain internal implementation details of the tool.

**Tech Stack:** Python, Typer, pytest, Bash wrapper tests, repository-wide ripgrep checks, Gradle and GitHub workflow updates.

---

## Scope Guard

**In scope**

- repository-wide cleanup of stale top-level wrappers and duplicate docs
- migration of docs, comments, and workflow references to canonical `controlplane.sh` commands
- retirement or explicit deprecation of old compatibility shims
- tests that lock the canonical UX and detect regressions in docs/reference drift

**Out of scope**

- deeper runtime/backend rewrites
- redesign of `controlplane-tool` feature semantics
- changes to product behavior beyond cleanup and canonicalization

## Milestone 7 Contract

At the end of this milestone:

- `scripts/controlplane.sh` is the canonical orchestration entrypoint
- `tools/controlplane/README.md` and the top-level docs describe one UX, not parallel ones
- the remaining `scripts/` files are either repo-ops helpers or thin documented shims
- CI and release flows use canonical tool commands wherever possible

### Task 1: Freeze the canonical surface and add drift-detection tests

**Files:**
- Create: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Create: `scripts/tests/test_legacy_wrappers_contract.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add tests that assert:

```python
def test_canonical_entrypoint_is_controlplane_wrapper() -> None:
    assert "scripts/controlplane.sh" in README_TEXT
    assert "scripts/control-plane-build.sh build" not in README_TEXT


def test_legacy_wrappers_are_documented_as_compatibility_only() -> None:
    ...
```

Add wrapper tests that enumerate the legacy scripts and enforce one of two states:

- forwards directly to `scripts/controlplane.sh`
- has been deleted and removed from docs

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py -v

python3 -m pytest scripts/tests/test_legacy_wrappers_contract.py -q
```

Expected: FAIL because stale references and wrappers still exist.

**Step 3: Write minimal implementation**

Implement the tests and any small helper utilities needed to scan docs/wrappers, but do not clean the docs/scripts yet.

**Step 4: Run tests to verify they still fail on current drift**

Run the same commands from Step 2.

Expected: FAIL for real stale references, proving the drift tests are useful.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py \
  scripts/tests/test_legacy_wrappers_contract.py
git commit -m "test: lock canonical controlplane entrypoints"
```

### Task 2: Remove or demote obsolete top-level wrappers

**Files:**
- Modify or Delete: `scripts/control-plane-build.sh`
- Modify or Delete: `scripts/controlplane-tool.sh`
- Modify or Delete: `scripts/e2e-all.sh`
- Modify or Delete: `scripts/e2e-buildpack.sh`
- Modify or Delete: `scripts/e2e-container-local.sh`
- Modify or Delete: `scripts/e2e-k3s-curl.sh`
- Modify or Delete: `scripts/e2e-k3s-helm.sh`
- Modify or Delete: `scripts/e2e-k8s-vm.sh`
- Modify or Delete: `scripts/e2e-cli.sh`
- Modify or Delete: `scripts/e2e-cli-host-platform.sh`
- Modify or Delete: `scripts/e2e-cli-deploy-host.sh`
- Modify or Delete: `scripts/e2e-loadtest.sh`
- Modify: `scripts/controlplane.sh`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`

**Step 1: Decide the end state per wrapper**

For each wrapper above, choose one of:

- delete it completely if all callers are migrated
- keep it as a 3-10 line compatibility shim with a deprecation comment

Do not keep orchestration logic in any of them.

**Step 2: Write the failing tests**

Update the contract test so it explicitly asserts the chosen end state for each wrapper.

**Step 3: Implement the cleanup**

- delete wrappers that are no longer needed
- slim the rest down to direct forwarding
- add a short compatibility notice at the top of any retained wrapper

**Step 4: Run tests to verify they pass**

Run:

```bash
python3 -m pytest scripts/tests/test_legacy_wrappers_contract.py -q
bash -n scripts/controlplane.sh
```

Expected: PASS.

**Step 5: Commit**

```bash
git add -A
git commit -m "refactor: retire legacy controlplane wrappers"
```

### Task 3: Migrate docs, examples, and workflow references to the canonical UX

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/control-plane.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `tools/controlplane/README.md`
- Modify: `.github/workflows/gitops.yml`
- Modify: `scripts/build-push-images.sh`
- Modify: `scripts/release-manager/release.py`
- Modify: `scripts/native-build.sh`
- Modify: `scripts/test-control-plane-module-combinations.sh`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Expand the canonical-entrypoint tests so they reject stale examples such as:

- `./scripts/e2e-k8s-vm.sh`
- `./scripts/e2e-cli.sh`
- `./scripts/control-plane-build.sh ...`
- `./scripts/controlplane-tool.sh ...`

unless they appear in explicitly marked compatibility sections.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_docs_links.py -v
```

Expected: FAIL until docs and workflow references are migrated.

**Step 3: Implement the cleanup**

Update docs and operational helpers so examples use:

- `scripts/controlplane.sh build ...`
- `scripts/controlplane.sh e2e ...`
- `scripts/controlplane.sh loadtest ...`
- `scripts/controlplane.sh cli-test ...`

Keep compatibility notes brief and centralized.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  README.md \
  CLAUDE.md \
  docs/control-plane.md \
  docs/testing.md \
  docs/e2e-tutorial.md \
  docs/quickstart.md \
  docs/nanofaas-cli.md \
  tools/controlplane/README.md \
  .github/workflows/gitops.yml \
  scripts/build-push-images.sh \
  scripts/release-manager/release.py \
  scripts/native-build.sh \
  scripts/test-control-plane-module-combinations.sh \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_docs_links.py
git commit -m "docs: migrate repository to canonical controlplane commands"
```

### Task 4: Retire the transitional `pipeline-run` / duplicate surfaces

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Modify: `tools/controlplane/src/controlplane_tool/pipeline.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`
- Modify: `tools/controlplane/tests/test_pipeline.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write the failing tests**

Add tests that define the end state:

- `pipeline-run` is either removed or marked as deprecated alias
- `tui` delegates to the same canonical use cases and naming
- help output does not present duplicate product surfaces for the same capability

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_tui_choices.py -v
```

Expected: FAIL until the duplicate surface is cleaned up.

**Step 3: Implement the cleanup**

Choose one clean end state:

- remove `pipeline-run` entirely and route users to `loadtest run`
- or keep it as hidden/deprecated alias only

Update the TUI copy so it reflects the final command groups instead of the transitional naming.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/main.py \
  tools/controlplane/src/controlplane_tool/pipeline.py \
  tools/controlplane/src/controlplane_tool/tui.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_tui_choices.py
git commit -m "refactor: remove duplicate tooling surfaces"
```

### Task 5: Final repository verification for Milestone 7

**Files:**
- No new files; verification only

**Step 1: Run tool and wrapper tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v
python3 -m pytest scripts/tests -q
```

Expected: PASS.

**Step 2: Run canonical help/dry-run checks**

Run:

```bash
scripts/controlplane.sh --help
scripts/controlplane.sh e2e list
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh cli-test list
```

Expected: PASS, and output reflects only the canonical surface.

**Step 3: Run repository-wide stale-reference checks**

Run:

```bash
rg -n "control-plane-build\\.sh|controlplane-tool\\.sh|e2e-k8s-vm\\.sh|e2e-cli\\.sh|pipeline-run" README.md CLAUDE.md docs .github tools/controlplane
```

Expected: either no matches, or only intentional compatibility/deprecation notes.

**Step 4: Commit**

```bash
git add -A
git commit -m "chore: finalize controlplane tooling cleanup"
```
