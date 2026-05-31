# Sub-5/6-b1-ii — Repoint release.py at the `images` command (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `release.py::build_and_push_arm64`'s duplicated 120-line OCI build matrix with a single call to `scripts/controlplane.sh images`, and make `bash-*` the one canonical name for the bash demo images (fix the CI pipeline to match).

**Architecture:** `build_and_push_arm64` becomes a thin wrapper that invokes `controlplane.sh images --arch arm64 --arch-suffix --tag v{version}` (which now owns the 16-target matrix) and keeps its two post-build smoke tests. The now-unused native-arg helpers are deleted. `.github/workflows/gitops.yml` renames the two `exec-*` bash-demo image tags to `bash-*`. An obsolete `scripts/tests/` test is removed.

**Tech Stack:** Python (release.py — a standalone `uv` wizard), GitHub Actions YAML, bash.

**Commands:** `uv run --project tools/controlplane pytest <path>` for the controlplane suite. Branch: `refactor/wt-sub5b1ii-release-repoint` (already created; it also carries a `.coverage` gitignore hygiene commit). Spec: `docs/superpowers/specs/2026-05-31-release-repoint-images-design.md`.

**Verified facts:**
- `build_and_push_arm64(version)` is `scripts/release-manager/release.py` lines 228–351; called once at line 538.
- After the rewrite, `run_with_disk_retry` (and the `prune_docker_build_caches` it calls) stays used; `resolve_native_image_build_args` (lines 121–127) + `resolve_native_active_processors` (lines 107–119) become unused → delete. `smoke_test_service_image`, `run_command`, `REGISTRY`/`GH_OWNER`/`GH_REPO` stay.
- The `images` command produces `ghcr.io/miciav/nanofaas/{name}:v{version}-arm64` for `--arch arm64 --arch-suffix --tag v{version}`, matching the smoke refs.

---

### Task 1: Rewrite `build_and_push_arm64` + drop unused helpers

**Files:**
- Modify: `scripts/release-manager/release.py`

- [ ] **Step 1: Replace the body of `build_and_push_arm64`**

Replace the ENTIRE function (lines 228–351, from `def build_and_push_arm64(version):` through the final `console.print("[green]✓ Local ARM64 images pushed to GHCR.[/green]")`) with:

```python
def build_and_push_arm64(version):
    """Local ARM64 builds for Mac M-series users — delegates to controlplane-tool images."""
    console.print("\n[bold]Starting local ARM64 builds...[/bold]")
    tag = f"v{version}"
    base_image = f"{REGISTRY}/{GH_OWNER}/{GH_REPO}"

    run_with_disk_retry(f"./scripts/controlplane.sh images --arch arm64 --arch-suffix --tag {tag}")

    # Post-build smoke tests for the two service images (images are already built+pushed;
    # the local images still exist, so the smoke run validates them).
    smoke_test_service_image(
        f"{base_image}/control-plane:{tag}-arm64",
        "control-plane",
        allowed_error_patterns=[
            "Error creating bean with name 'kubernetesClient'",
            "io.fabric8.kubernetes.client.KubernetesClientException",
        ],
    )
    smoke_test_service_image(f"{base_image}/function-runtime:{tag}-arm64", "function-runtime")

    console.print("[green]✓ Local ARM64 images pushed to GHCR.[/green]")
```

- [ ] **Step 2: Delete the now-unused native-arg helpers**

Delete `resolve_native_active_processors` (lines 107–119) and `resolve_native_image_build_args` (lines 121–127) — the whole two function definitions:

```python
def resolve_native_active_processors():
    value = os.getenv("NATIVE_ACTIVE_PROCESSORS", "").strip()
    if value:
        try:
            parsed = int(value)
            if parsed >= 1:
                return str(parsed)
        except ValueError:
            pass
    detected = os.cpu_count() or 4
    if detected < 1:
        detected = 4
    return str(detected)

def resolve_native_image_build_args():
    explicit = os.getenv("NATIVE_IMAGE_BUILD_ARGS", "").strip()
    if explicit:
        return explicit
    xmx = os.getenv("NATIVE_IMAGE_XMX", "8g").strip() or "8g"
    active_processors = resolve_native_active_processors()
    return f"-H:+AddAllCharsets -J-Xmx{xmx} -J-XX:ActiveProcessorCount={active_processors}"
```

- [ ] **Step 3: Compile + lint the file**

Run: `python -m py_compile scripts/release-manager/release.py && echo OK`
Expected: `OK`.

Run: `uv run --project tools/controlplane ruff check scripts/release-manager/release.py 2>&1 | tail -5`
Expected: clean. If ruff flags `shlex` (F401) or `os` as unused now, remove the offending `import shlex` line (do NOT remove `import os` unless ruff confirms it is unused — `os` is likely used elsewhere). Re-run ruff until clean.

- [ ] **Step 4: Confirm the repoint + no leftover inline matrix**

Run: `grep -n "controlplane.sh images --arch arm64" scripts/release-manager/release.py`
Expected: one match (inside `build_and_push_arm64`).

Run: `grep -nc "bootBuildImage\|docker build --platform" scripts/release-manager/release.py`
Expected: `0` (the per-target matrix is gone).

- [ ] **Step 5: Commit**

```bash
git add scripts/release-manager/release.py
git commit -m "refactor(release): build_and_push_arm64 delegates to controlplane-tool images"
```

---

### Task 2: Align CI image naming `exec-*` → `bash-*`

**Files:**
- Modify: `.github/workflows/gitops.yml`

- [ ] **Step 1: Rename the two Exec demo image tags**

In `.github/workflows/gitops.yml`, the step "Build and push Exec Word Stats" tags:
```yaml
          tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/exec-word-stats:${{ github.ref_name }}, ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/exec-word-stats:latest
```
Change BOTH occurrences of `exec-word-stats` on that line to `bash-word-stats`.

The step "Build and push Exec JSON Transform" tags:
```yaml
          tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/exec-json-transform:${{ github.ref_name }}, ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/exec-json-transform:latest
```
Change BOTH occurrences of `exec-json-transform` to `bash-json-transform`.

Leave the `context`, `file: examples/bash/.../Dockerfile`, and step `name:` unchanged.

- [ ] **Step 2: Verify no live `exec-*` demo image reference remains**

Run:
```bash
grep -rn "exec-word-stats\|exec-json-transform\|/exec-" . \
  --include="*.yml" --include="*.yaml" --include="*.py" --include="*.toml" --include="*.json" \
  | grep -v "docs/plans/\|docs/superpowers/\|experiments/control-plane-staging/versions/\|node_modules\|\.git/"
```
Expected: EMPTY. (The `function-sdk-python/tests/test_runtime.py` match for `exec-cb` / `exec-123` is a callback id, NOT an image name — if it appears it is fine; confirm it is `exec-cb`/`exec-123`, not `exec-word-stats`/`exec-json-transform`.)

Run: `grep -c "bash-word-stats\|bash-json-transform" .github/workflows/gitops.yml`
Expected: non-zero (the tags are now `bash-*`).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/gitops.yml
git commit -m "ci: name bash demo images bash-* (align with helm/examples and images command)"
```

---

### Task 3: Remove the obsolete release-manager test + final verification

**Files:**
- Delete: `scripts/tests/test_release_manager_native_args.py`

- [ ] **Step 1: Delete the obsolete test**

`scripts/tests/test_release_manager_native_args.py` asserts release.py contains
`resolve_native_image_build_args` and `./scripts/controlplane.sh image --profile all --`
— both removed in Task 1. The native-args behavior now lives in
`controlplane-tool images` (covered by `tools/controlplane/tests/test_image_matrix.py`).

```bash
git rm scripts/tests/test_release_manager_native_args.py
```

- [ ] **Step 2: Check the sibling release-manager test is unaffected**

Run: `grep -n "resolve_native_image_build_args\|exec-\|build_and_push_arm64\|image --profile all" scripts/tests/test_release_manager_javascript_sdk.py`
Expected: no matches (it tests the JS-SDK/npm flow, untouched). If it DOES reference the removed arm64 build logic, update those assertions to match the new `build_and_push_arm64` (which contains `controlplane.sh images --arch arm64`) or remove the obsolete assertion; do NOT weaken a still-valid JS-SDK assertion.

- [ ] **Step 3: Final verification**

Run: `python -m py_compile scripts/release-manager/release.py && echo OK` → `OK`.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3` → 0 failures (no controlplane code changed; `test_function_catalog.py` already expects `bash-*`).
Run the grep from Task 2 Step 2 again → EMPTY (no live `exec-*` demo image refs).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: remove obsolete release-manager native-args gate (logic moved to images command)"
```

---

## Self-Review

- **Spec coverage:** repoint build_and_push_arm64 → Task 1 Step 1; keep 2 smoke tests → Task 1 Step 1; drop unused native-arg helpers → Task 1 Step 2; CI `exec-*`→`bash-*` → Task 2; delete obsolete test → Task 3 Step 1; verify no live `exec-*` + controlplane green → Task 2 Step 2 + Task 3 Step 3. Accepted simplifications (bare control-plane task, post-build smoke) are inherent to calling the `images` command. ✓
- **Placeholder scan:** none — exact old/new code for the function + helper deletions, exact YAML tag edits, exact grep/compile commands with expected output. The `shlex`-import and JS-SDK-test checks are concrete grep-to-confirm conditionals, not vague work.
- **Type consistency:** `build_and_push_arm64(version)` signature unchanged (still called as `build_and_push_arm64(new_v)` at line 538); `tag = f"v{version}"` and the `-arm64` suffix match what `images --arch arm64 --arch-suffix --tag v{version}` produces, so the smoke refs resolve. ✓
