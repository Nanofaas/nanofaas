# Control Plane Tooling Milestone 2 Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the three regressions introduced by milestone 2: broken K8s E2E module selection, missing `uv` on freshly provisioned VMs, and non-reproducible wrapper dependency resolution.

**Architecture:** Keep the public `k8s` profile semantics unchanged: it remains the minimal Kubernetes provider profile, not an implicit “all E2E modules” preset. E2E runners that need queue/autoscaler behavior must pass explicit modules. Treat `uv` as part of the control-plane tooling runtime: provision it where the wrapper is used, fail fast with a clear message when it is missing, and execute the wrapper in locked mode against a committed `uv.lock`.

**Tech Stack:** Bash, Python, Typer, uv, Ansible, pytest, JUnit 5, Gradle.

---

## Scope

**In scope**

- `scripts/control-plane-build.sh`
- `scripts/controlplane-tool.sh`
- `tools/controlplane/uv.lock`
- `scripts/e2e-k8s-vm.sh`
- `scripts/lib/e2e-k3s-common.sh`
- `scripts/ansible/playbooks/provision-base.yml`
- targeted shell/Python/Java tests and docs touched by these fixes

**Out of scope**

- changing the meaning of `--profile k8s`
- redesigning VM orchestration
- broader milestone 2 refactors not implicated by the findings

## Fix Strategy

1. **Do not widen `profile=k8s`.** Existing docs and tests already rely on `k8s` meaning “Kubernetes provider only”. K8s E2E paths should instead pass `--modules all` or an explicit CSV selector.
2. **Make wrapper execution deterministic.** Commit `tools/controlplane/uv.lock` and run wrappers with `uv run --locked`.
3. **Make wrapper execution available on clean VMs.** Provision `uv` in the base VM playbook and add a shell preflight so failures are explicit rather than `command not found`.

### Task 1: Lock the control-plane tooling runtime

**Files:**
- Modify: `scripts/control-plane-build.sh`
- Modify: `scripts/controlplane-tool.sh`
- Create: `tools/controlplane/uv.lock`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`

**Step 1: Write the failing test**

Add wrapper assertions before changing the scripts:

```python
from pathlib import Path


def test_control_plane_build_wrapper_uses_locked_uv_run() -> None:
    script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script


def test_pipeline_wrapper_uses_locked_uv_run() -> None:
    script = Path("scripts/controlplane-tool.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script


def test_tooling_lockfile_exists() -> None:
    assert Path("tools/controlplane/uv.lock").exists()
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_wrapper_docs.py tools/controlplane/tests/test_cli_smoke.py -v`

Expected: FAIL because the wrappers do not use `--locked` and `uv.lock` is not tracked yet.

**Step 3: Write minimal implementation**

- Change wrappers to use:

```bash
exec uv run --project tools/controlplane --locked controlplane-tool "$@"
```

- Keep the rest of the wrapper behavior unchanged.
- Add `tools/controlplane/uv.lock` to git from the already generated local lockfile.

**Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_wrapper_docs.py tools/controlplane/tests/test_cli_smoke.py -v`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/control-plane-build.sh scripts/controlplane-tool.sh tools/controlplane/uv.lock tools/controlplane/tests/test_wrapper_docs.py tools/controlplane/tests/test_cli_smoke.py
git commit -m "fix: lock controlplane wrapper dependencies"
```

### Task 2: Restore K8s E2E module coverage with explicit selectors

**Files:**
- Modify: `scripts/e2e-k8s-vm.sh`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_k3s_helm_control_plane_native.py`

**Step 1: Write the failing test**

Tighten the current shell tests so the K8s E2E paths must pass explicit modules:

```python
def test_k8s_vm_runner_passes_explicit_modules_to_wrapper() -> None:
    script = read_script("e2e-k8s-vm.sh")
    assert "./scripts/control-plane-build.sh test --profile k8s --modules" in script


def test_build_core_jars_passes_explicit_modules_to_wrapper() -> None:
    out = run_shell(
        f"{source}; "
        "e2e_require_vm_exec(){ return 0; }; "
        "vm_exec(){ printf '%s\\n' \"$*\"; }; "
        "CONTROL_PLANE_MODULES=all; "
        "e2e_build_core_jars /tmp/repo false"
    )
    assert "./scripts/control-plane-build.sh jar --profile k8s --modules all" in out
```

Also update the native K3s Helm test to keep requiring the existing `--modules '${CONTROL_PLANE_MODULES}'` behavior.

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Expected: FAIL because `e2e-k8s-vm.sh` and `e2e_build_core_jars()` still rely on bare `--profile k8s`.

**Step 3: Write minimal implementation**

- Thread an explicit module selector through the K8s E2E paths.
- Do **not** change `PROFILE_TO_MODULES_SELECTOR["k8s"]`.
- In shell, prefer one helper instead of repeating defaults:

```bash
e2e_get_control_plane_modules() {
    echo "${CONTROL_PLANE_MODULES:-all}"
}
```

- Use it in the affected call sites:

```bash
./scripts/control-plane-build.sh jar --profile k8s --modules "$(e2e_get_control_plane_modules)" -- --no-daemon --rerun-tasks
./scripts/control-plane-build.sh test --profile k8s --modules "${CONTROL_PLANE_MODULES:-all}" -- -PrunE2e --tests ...
```

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e-k8s-vm.sh scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py
git commit -m "fix: restore explicit module selection for k8s e2e"
```

### Task 3: Provision `uv` for wrapper execution on clean VMs

**Files:**
- Modify: `scripts/ansible/playbooks/provision-base.yml`
- Modify: `scripts/tests/test_e2e_ansible_provisioning.py`
- Modify: `scripts/control-plane-build.sh`
- Create: `scripts/tests/test_control_plane_build_wrapper_runtime.py`

**Step 1: Write the failing test**

First, lock the new provisioning contract in tests:

```python
def test_base_playbook_installs_uv_for_controlplane_wrapper() -> None:
    base = Path("scripts/ansible/playbooks/provision-base.yml").read_text(encoding="utf-8")
    assert "Install uv" in base
    assert "UV_INSTALL_DIR" in base or "command -v uv" in base


def test_control_plane_build_wrapper_fails_fast_when_uv_is_missing() -> None:
    script = Path("scripts/control-plane-build.sh").read_text(encoding="utf-8")
    assert "command -v uv" in script
    assert "uv not found" in script.lower()
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_control_plane_build_wrapper_runtime.py -q`

Expected: FAIL because the base playbook does not install `uv` and the wrapper has no explicit preflight.

**Step 3: Write minimal implementation**

- Add an idempotent `uv` installation step to `scripts/ansible/playbooks/provision-base.yml`.
- Prefer the official installer with a fixed install directory such as `/usr/local/bin` so the command is immediately available to non-login shells.
- Add a tiny preflight at the top of `scripts/control-plane-build.sh`:

```bash
if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install uv or provision the VM with scripts/ansible/playbooks/provision-base.yml" >&2
  exit 1
fi
```

- Do not add dynamic auto-install logic in the wrapper.

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_control_plane_build_wrapper_runtime.py -q`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/ansible/playbooks/provision-base.yml scripts/tests/test_e2e_ansible_provisioning.py scripts/control-plane-build.sh scripts/tests/test_control_plane_build_wrapper_runtime.py
git commit -m "fix: provision uv for wrapper-based vm builds"
```

### Task 4: Re-run focused verification on the repaired paths

**Files:**
- No code changes required unless verification reveals fallout.

**Step 1: Run focused Python/shell checks**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_wrapper_docs.py tools/controlplane/tests/test_cli_smoke.py -v
python3 -m pytest scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_control_plane_build_wrapper_runtime.py -q
bash -n scripts/control-plane-build.sh scripts/controlplane-tool.sh scripts/e2e-k8s-vm.sh scripts/lib/e2e-k3s-common.sh
ansible-playbook --syntax-check scripts/ansible/playbooks/provision-base.yml
```

Expected: PASS.

**Step 2: Run behavior-level dry-runs**

Run:

```bash
scripts/control-plane-build.sh test --profile k8s --modules all --dry-run -- -PrunE2e --tests it.unimib.datai.nanofaas.controlplane.e2e.K8sE2eTest
scripts/control-plane-build.sh jar --profile k8s --modules all --dry-run -- --no-daemon --rerun-tasks
```

Expected:

- output includes `-PcontrolPlaneModules=all`
- the command remains wrapper-driven

**Step 3: Run the targeted Java helper test**

Run: `./gradlew :control-plane:test --tests '*BuildpackE2eCommandTest'`

Expected: PASS.

**Step 4: Commit any fallout**

```bash
git add -A
git commit -m "test: verify milestone 2 regression fixes"
```

Only create this final commit if verification required follow-up edits.
