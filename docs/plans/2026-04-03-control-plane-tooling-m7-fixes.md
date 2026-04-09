# Control Plane Tooling Milestone 7 Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close the remaining Milestone 7 drift gaps so every retained legacy wrapper is explicitly governed as a compatibility shim and the strict canonical docs/workflows no longer advertise wrapper commands as primary entrypoints.

**Architecture:** Keep the current M7 direction and tighten the enforcement layer instead of doing another broad refactor. Expand the canonical-entrypoint tests to blacklist the remaining top-level wrapper commands that are supposed to be compatibility-only. Add `scripts/e2e.sh` to the wrapper inventory contract and make the script itself conform to the same banner/shape as the other shims. Then update the few strict docs that still leak wrapper examples so the repository consistently presents `scripts/controlplane.sh` as the only primary orchestration surface, with compatibility notes centralized in `docs/testing.md`.

**Tech Stack:** Python, pytest, Bash wrapper scripts, repository docs (`README.md`, `CLAUDE.md`, `docs/*.md`), existing `tools/controlplane/tests/test_canonical_entrypoints.py`, and `scripts/tests/test_legacy_wrappers_contract.py`.

---

## Review Findings To Fix

1. The canonical-entrypoint drift test still misses `e2e.sh`, `e2e-buildpack.sh`, `e2e-k3s-curl.sh`, and `e2e-k3s-helm.sh`, so strict files can still advertise those wrappers as primary commands.
2. `scripts/e2e.sh` is still outside the explicit legacy-wrapper contract: no compatibility banner and no inventory test coverage.

## Fix Strategy

1. Lock both regressions with failing tests first.
2. Expand the strict canonical blacklist to include the remaining legacy wrapper names that should never appear in primary docs/workflows.
3. Add `scripts/e2e.sh` to the wrapper inventory test and make the script match the compatibility-shim template already used by the other wrappers.
4. Rewrite the remaining strict doc references to canonical `scripts/controlplane.sh ...` commands, keeping compatibility mentions only in `docs/testing.md` and other intentionally compatibility-focused sections.
5. Re-run the M7 verification suite plus one grep-based audit over the strict files.

### Task 1: Lock the M7 drift regressions with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`

**Step 1: Write the failing tests**

Expand the strict blacklist in `test_canonical_entrypoints.py` to cover the remaining wrappers that should not appear in strict canonical files:

```python
DOCKER_E2E_WRAPPER = "e2e" + ".sh"
BUILDPACK_E2E_WRAPPER = "e2e-buildpack" + ".sh"
K3S_CURL_WRAPPER = "e2e-k3s-curl" + ".sh"
HELM_STACK_WRAPPER = "e2e-k3s-helm" + ".sh"
```

Include them in `STALE_TOKENS`.

In `test_legacy_wrappers_contract.py`, add:

```python
SHIM_TARGETS["e2e.sh"] = 'exec "$(dirname "$0")/controlplane.sh" e2e run docker "$@"'
```

Keep `e2e-loadtest.sh` as the intentional exception.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py -v

python3 -m pytest \
  scripts/tests/test_legacy_wrappers_contract.py -q
```

Expected:
- canonical-entrypoint test fails because strict files still contain `e2e-buildpack.sh`, `e2e-k3s-curl.sh`, or `e2e-k3s-helm.sh`
- wrapper-contract test fails because `scripts/e2e.sh` is not yet in the documented shim shape

**Step 3: Commit**

```bash
git add \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  scripts/tests/test_legacy_wrappers_contract.py
git commit -m "test: lock remaining m7 wrapper drift"
```

### Task 2: Bring `scripts/e2e.sh` under the wrapper contract

**Files:**
- Modify: `scripts/e2e.sh`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`

**Step 1: Write the failing test if still needed**

If Task 1 did not already fail on `e2e.sh`, add an explicit assertion:

```python
def test_docker_e2e_wrapper_is_documented_as_compatibility_only() -> None:
    script = (SCRIPTS_DIR / "e2e.sh").read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert 'exec "$(dirname "$0")/controlplane.sh" e2e run docker "$@"' in script
```

**Step 2: Make the script match the shim template**

Change `scripts/e2e.sh` to the same structure as the other retained wrappers:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Compatibility wrapper. Prefer `scripts/controlplane.sh e2e run docker ...`.
exec "$(dirname "$0")/controlplane.sh" e2e run docker "$@"
```

No additional logic. No Gradle. No comments beyond the single compatibility note.

**Step 3: Run tests**

Run:

```bash
python3 -m pytest scripts/tests/test_legacy_wrappers_contract.py -q
bash -n scripts/e2e.sh
```

Expected: PASS.

**Step 4: Commit**

```bash
git add scripts/e2e.sh scripts/tests/test_legacy_wrappers_contract.py
git commit -m "refactor: normalize docker e2e compatibility wrapper"
```

### Task 3: Clean the remaining strict canonical docs

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`

**Step 1: Replace wrapper examples with canonical commands**

Use `scripts/controlplane.sh ...` in the strict files:

- in `CLAUDE.md`, replace:
  - `./scripts/e2e.sh` -> `./scripts/controlplane.sh e2e run docker`
  - `./scripts/e2e-buildpack.sh` -> `./scripts/controlplane.sh e2e run buildpack`

- in `docs/quickstart.md`, replace:
  - `./scripts/e2e.sh` -> `./scripts/controlplane.sh e2e run docker`
  - `./scripts/e2e-buildpack.sh` -> `./scripts/controlplane.sh e2e run buildpack`

- in `docs/e2e-tutorial.md`, replace strict primary examples:
  - `./scripts/e2e-k3s-helm.sh` -> `./scripts/controlplane.sh e2e run helm-stack`
  - `./scripts/e2e-k3s-curl.sh` -> `./scripts/controlplane.sh e2e run k3s-curl`

Do not remove the loadtest legacy-wrapper discussion in `docs/e2e-tutorial.md`; that exception is intentional and already modeled by M7.

**Step 2: Keep compatibility language out of strict primary examples**

If a file still needs to mention a wrapper, phrase it as a compatibility note rather than the main command path. For the strict files above, prefer removing the wrapper mention entirely unless it is needed for contrast.

**Step 3: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py -v
```

Expected: PASS.

**Step 4: Run a direct grep audit**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
root = Path(".")
files = [
    root / "README.md",
    root / "CLAUDE.md",
    root / "docs" / "control-plane.md",
    root / "docs" / "quickstart.md",
    root / "docs" / "e2e-tutorial.md",
    root / "tools" / "controlplane" / "README.md",
    root / ".github" / "workflows" / "gitops.yml",
]
tokens = [
    "e2e.sh",
    "e2e-buildpack.sh",
    "e2e-k3s-curl.sh",
    "e2e-k3s-helm.sh",
    "e2e-k8s-vm.sh",
    "e2e-cli.sh",
    "e2e-cli-host-platform.sh",
    "e2e-cli-deploy-host.sh",
    "control-plane-build.sh",
    "controlplane-tool.sh",
    "pipeline-run",
]
for path in files:
    text = path.read_text(encoding="utf-8")
    hits = [token for token in tokens if token in text]
    if hits:
        print(path)
        print(hits)
        raise SystemExit(1)
PY
```

Expected: no output, exit `0`.

**Step 5: Commit**

```bash
git add \
  CLAUDE.md \
  docs/quickstart.md \
  docs/e2e-tutorial.md \
  tools/controlplane/tests/test_canonical_entrypoints.py
git commit -m "docs: finish canonical controlplane command cleanup"
```

### Task 4: Final M7 verification

**Files:**
- Verify only

**Step 1: Run the M7 focused Python test suite**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_cli_run_behavior.py -q
```

Expected: PASS.

**Step 2: Run the M7 focused shell/runtime suite**

Run:

```bash
python3 -m pytest \
  scripts/tests/test_legacy_wrappers_contract.py \
  scripts/tests/test_build_push_images_native_args.py \
  scripts/tests/test_control_plane_module_matrix_wrapper.py \
  scripts/tests/test_gitops_workflow_control_plane_build.py \
  scripts/tests/test_native_build_wrapper.py \
  scripts/tests/test_release_manager_native_args.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: PASS.

**Step 3: Sanity-check the canonical CLI help**

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool --help
```

Expected:
- no `pipeline-run`
- canonical command groups still present: `cli-test`, `vm`, `e2e`, `functions`, `loadtest`

**Step 4: Commit any final test/doc touch-ups**

If the verification pass forced tiny wording/test adjustments:

```bash
git add -A
git commit -m "test: verify m7 canonical wrapper cleanup"
```
