# nanolab Launcher Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the local dev launcher for the ops tool from `nanofaas/scripts/controlplane.sh` into `miciav/nanolab` as `nanolab.sh`, carry forward the handful of nanofaas-side regression tests that are not already covered by nanolab's own suite, and update the active nanofaas docs that reference the old script.

**Architecture:** Two independent repositories are touched. `miciav/nanolab` (a uv workspace, already published and CI-green) gains a root-level launcher script plus its test, and three ansible-playbook content assertions that existed only in nanofaas. `nanofaas` then deletes the old script and its dedicated test, trims the now-redundant/moved assertions out of two other test files, and repoints ~10 active docs at the new two-checkout invocation pattern. `tools/{controlplane,workflow-tasks,tui-toolkit}` and `experiments/**` in nanofaas are explicitly not touched.

**Tech Stack:** Bash, Python 3.12, uv workspaces, pytest, Markdown docs.

## Global Constraints

- Source design: `docs/superpowers/specs/2026-07-25-nanolab-launcher-migration-design.md`.
- `scripts/controlplane.sh` is deleted from nanofaas, not kept as a compatibility shim.
- `nanolab.sh` is pass-through only — it does not read, validate, or default `NANOFAAS_ROOT`; that stays inside `nanolab.workspace.paths.default_tool_paths()`.
- `tools/{controlplane,workflow-tasks,tui-toolkit}` in nanofaas are **not** deleted or modified.
- `experiments/**` in nanofaas is **not** modified in this plan.
- Historical docs under `docs/plans/`, `docs/superpowers/plans/`, and `docs/superpowers/specs/` are **not** edited.
- nanolab repo path in this environment: `/Users/micheleciavotta/Downloads/mcFaas/.worktrees/nanolab` (a standalone git repo, remote `origin` = `https://github.com/miciav/nanolab.git`, already pushed and CI-green through commit `fc4b6b3`).
- nanofaas repo path in this environment: `/Users/micheleciavotta/Downloads/mcFaas` (remote `origin` = `https://github.com/miciav/nanofaas.git`).
- Test coverage that is unique to nanofaas today moves to nanolab before nanofaas deletes it. Coverage already duplicated in nanolab, or unrelated to controlplane-tool/nanolab, is left alone.

---

### Task 1: Add the nanolab.sh launcher and its test

**Repository:** `miciav/nanolab` (`/Users/micheleciavotta/Downloads/mcFaas/.worktrees/nanolab`)

**Files:**

- Create: `nanolab.sh`
- Create: `packages/nanolab/tests/test_launcher_script.py`
- Modify: `README.md`

**Interfaces:**

- Produces: `nanolab.sh` at the nanolab repo root — `./nanolab.sh <args>` runs `uv run --locked --package nanolab nanolab <args>` from wherever it is invoked, after checking `uv` is on `PATH`. No other task in this plan depends on its internals beyond this contract.

**Step 1: Write the failing test**

Create `packages/nanolab/tests/test_launcher_script.py`:

```python
from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]


def test_launcher_script_exists_at_repo_root() -> None:
    script = REPO_ROOT / "nanolab.sh"
    assert script.is_file()


def test_launcher_script_fails_fast_when_uv_is_missing() -> None:
    script = (REPO_ROOT / "nanolab.sh").read_text(encoding="utf-8")
    assert "command -v uv" in script
    assert "uv not found" in script.lower()


def test_launcher_script_runs_the_nanolab_package() -> None:
    script = (REPO_ROOT / "nanolab.sh").read_text(encoding="utf-8")
    assert "uv run --locked --package nanolab nanolab" in script
```

**Step 2: Run test to verify it fails**

Run (from the nanolab repo root):

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests/test_launcher_script.py -q
```

Expected: FAIL — `nanolab.sh` does not exist yet (`test_launcher_script_exists_at_repo_root` and the two content tests all fail, the content tests with `FileNotFoundError`).

**Step 3: Create the launcher script**

Create `nanolab.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${ROOT_DIR}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install uv (https://github.com/astral-sh/uv) and retry." >&2
  exit 1
fi

exec uv run --locked --package nanolab nanolab "$@"
```

Make it executable:

```bash
chmod +x nanolab.sh
```

**Step 4: Run test to verify it passes**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests/test_launcher_script.py -q
```

Expected: PASS (3 passed).

**Step 5: Smoke-test the script for real**

```bash
export NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/mcFaas
./nanolab.sh --help
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml \
  --environment packages/nanolab/environments/local.yaml
```

Expected: both exit 0 — `--help` prints the `nanolab` CLI usage (same output
as `uv run --package nanolab nanolab --help`), and `plan` prints the
6-step plan for `validate-container.yaml` (same as the Task 7 smoke test in
the extraction plan).

**Step 6: Document it in the README**

In `README.md`, after the existing example block (the one ending `nanolab run packages/nanolab/scenarios-v2/validate-container.yaml`), add:

```markdown

Or use the bundled launcher, which checks for `uv` and forwards all
arguments — equivalent to `uv run --package nanolab nanolab ...`:

```bash
export NANOFAAS_ROOT=/path/to/nanofaas
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
```
```

(That is: insert the new prose paragraph and fenced `bash` block immediately after the existing fenced block that currently ends the "For example:" section, before the `## CI gate` heading.)

**Step 7: Run the full nanolab test suite**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests -q
```

Expected: all tests pass, count increased by 3 versus the pre-task baseline (651 → 654).

**Step 8: Commit**

```bash
git add nanolab.sh packages/nanolab/tests/test_launcher_script.py README.md
git commit -m "Add the nanolab.sh launcher script"
```

---

### Task 2: Carry forward the unique ansible-content regression tests

**Repository:** `miciav/nanolab` (`/Users/micheleciavotta/Downloads/mcFaas/.worktrees/nanolab`)

**Files:**

- Modify: `packages/workflow-tasks/tests/infra/test_ansible.py`

**Interfaces:**

- Consumes: `workflow_tasks.infra.ansible.bundled_ansible_root()` (already used elsewhere in this file).
- Produces: nothing further tasks depend on — this task only adds regression coverage that currently exists solely in nanofaas's `scripts/tests/test_e2e_ansible_provisioning.py`, so Task 3 can safely delete it there.

**Step 1: Confirm the assertions already hold against the bundled playbooks**

Run (from the nanolab repo root):

```bash
grep -n 'https://api.github.com/repos/k3s-io/k3s/releases/latest\|k3s_version_override\|tag_name' packages/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/provision-k3s.yml
grep -n '("v" ~ helm_version)\|Install uv\|UV_INSTALL_DIR' packages/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/provision-base.yml
grep -n 'docker port {{ registry_container_name }} 5000/tcp' packages/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/ensure-registry.yml
```

Expected: every pattern matches at least once. This is carried-forward regression coverage for content that already exists (not new behavior), so there is no red/green cycle — go straight to adding the tests and confirming they pass.

**Step 2: Add the three test functions**

In `packages/workflow-tasks/tests/infra/test_ansible.py`, append at the end of the file:

```python


def test_provision_k3s_resolves_the_release_dynamically() -> None:
    playbook = (
        bundled_ansible_root() / "playbooks" / "provision-k3s.yml"
    ).read_text(encoding="utf-8")

    assert "https://api.github.com/repos/k3s-io/k3s/releases/latest" in playbook
    assert "k3s_version_override" in playbook
    assert "tag_name" in playbook


def test_provision_base_and_ensure_registry_preserve_idempotence_guards() -> None:
    base = (
        bundled_ansible_root() / "playbooks" / "provision-base.yml"
    ).read_text(encoding="utf-8")
    registry = (
        bundled_ansible_root() / "playbooks" / "ensure-registry.yml"
    ).read_text(encoding="utf-8")

    assert '("v" ~ helm_version)' in base
    assert "docker port {{ registry_container_name }} 5000/tcp" in registry


def test_provision_base_installs_uv() -> None:
    base = (
        bundled_ansible_root() / "playbooks" / "provision-base.yml"
    ).read_text(encoding="utf-8")

    assert "Install uv" in base
    assert "UV_INSTALL_DIR" in base or "command -v uv" in base
```

**Step 3: Run the new tests**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/workflow-tasks/pyproject.toml packages/workflow-tasks/tests/infra/test_ansible.py -q
```

Expected: PASS, 3 more tests than before this step.

**Step 4: Run the full nanolab workspace gate**

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests -q
uv run --locked --all-packages --all-groups pytest -c packages/workflow-tasks/pyproject.toml packages/workflow-tasks/tests -q
uv run --locked --all-packages --all-groups pytest -c packages/tui-toolkit/pyproject.toml packages/tui-toolkit/tests -q
uv run --locked --all-packages --all-groups ruff check packages
uv run --locked --all-packages --all-groups basedpyright --project packages/nanolab
uv run --locked --all-packages --all-groups basedpyright --project packages/workflow-tasks
uv run --locked --all-packages --all-groups basedpyright --project packages/tui-toolkit
uv run --locked --all-packages --all-groups lint-imports --config packages/nanolab/.importlinter --no-cache
uv run --locked --all-packages --all-groups lint-imports --config packages/workflow-tasks/.importlinter --no-cache
uv run --locked --all-packages --all-groups lint-imports --config packages/tui-toolkit/.importlinter --no-cache
```

Expected: everything passes (this mirrors the plan's own CI gate — see `.github/workflows/ci.yml`).

**Step 5: Commit, push, and verify CI**

```bash
git add packages/workflow-tasks/tests/infra/test_ansible.py
git commit -m "Carry forward ansible-content regression coverage from nanofaas"
git push origin main
gh run list --repo miciav/nanolab --limit 3
```

Watch the new run (`gh run watch <run-id> --repo miciav/nanolab --exit-status`).

Expected: the `CI` workflow passes.

---

### Task 3: Delete the old launcher and its tests, trim redundant coverage

**Repository:** `nanofaas` (`/Users/micheleciavotta/Downloads/mcFaas`)

**Files:**

- Delete: `scripts/controlplane.sh`
- Delete: `scripts/tests/test_control_plane_build_wrapper_runtime.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_ansible_provisioning.py`

**Interfaces:**

- Consumes: nothing from Tasks 1–2 directly (nanofaas and nanolab are separate repos/checkouts) — this task only requires that Tasks 1–2 have already landed the equivalent coverage in nanolab, which is a precondition, not a code dependency.

**Step 1: Record the pre-change baseline**

```bash
uv run --project tools/controlplane pytest scripts/tests -q
```

Expected: `37 passed`.

**Step 2: Delete the launcher and its dedicated test**

```bash
git rm scripts/controlplane.sh scripts/tests/test_control_plane_build_wrapper_runtime.py
```

**Step 3: Trim `test_e2e_runtime_contract.py`**

Remove the ansible-existence test and the two now-dead helper functions it was the only user of. Change:

```python
def _tool_src() -> Path:
    return REPO_ROOT / "tools" / "controlplane" / "src"


def _ensure_tool_src_on_path() -> None:
    src = str(_tool_src())
    if src not in sys.path:
        sys.path.insert(0, src)


def test_ansible_playbooks_exist_for_vm_provisioning() -> None:
    """Ansible playbooks are still the authoritative provisioning source."""
    ansible_dir = (
        REPO_ROOT
        / "tools"
        / "workflow-tasks"
        / "src"
        / "workflow_tasks"
        / "infra"
        / "ansible_assets"
    )
    assert (ansible_dir / "ansible.cfg").exists()
    assert (ansible_dir / "playbooks" / "provision-base.yml").exists()
    assert (ansible_dir / "playbooks" / "provision-k3s.yml").exists()
    assert (ansible_dir / "playbooks" / "ensure-registry.yml").exists()
    assert (ansible_dir / "playbooks" / "configure-k3s-registry.yml").exists()


def test_helm_control_plane_template_quotes_extra_env_values() -> None:
```

to:

```python
def test_helm_control_plane_template_quotes_extra_env_values() -> None:
```

Also remove the now-unused `import sys` at the top of the file (check it isn't used elsewhere in the file first — it is not: it was only used by `_ensure_tool_src_on_path`).

**Step 4: Trim `test_e2e_ansible_provisioning.py`**

Remove four test functions, keeping `test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python`. Change the file from:

```python
def test_ansible_layout_exists_for_vm_provisioning():
    assert (ANSIBLE_DIR / "ansible.cfg").exists()
    assert (ANSIBLE_DIR / "requirements.txt").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-base.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "install-k6.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "ensure-registry.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "configure-k3s-registry.yml").exists()


# M11: e2e-k3s-common.sh deleted. Ansible bootstrap/inventory helpers are now
# owned by AnsibleAdapter in tools/controlplane/src/controlplane_tool/ansible_adapter.py.
def test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python() -> None:
    assert not (REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )


def test_k3s_provisioning_resolves_latest_release_dynamically():
    playbook = (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").read_text(encoding="utf-8")

    assert "https://api.github.com/repos/k3s-io/k3s/releases/latest" in playbook
    assert "k3s_version_override" in playbook
    assert "tag_name" in playbook


def test_ansible_playbooks_preserve_idempotence_guards_for_helm_and_registry():
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    registry = (ANSIBLE_DIR / "playbooks" / "ensure-registry.yml").read_text(encoding="utf-8")

    assert '("v" ~ helm_version)' in base
    assert 'docker port {{ registry_container_name }} 5000/tcp' in registry


def test_base_playbook_installs_uv_for_controlplane_wrapper() -> None:
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    assert "Install uv" in base
    assert "UV_INSTALL_DIR" in base or "command -v uv" in base


def test_k6_playbook_installs_k6_for_vm_loadtests() -> None:
    playbook = (ANSIBLE_DIR / "playbooks" / "install-k6.yml").read_text(encoding="utf-8")

    assert "Install k6" in playbook
    assert "https://github.com/grafana/k6/releases" in playbook
    assert "dest: /usr/local/bin/k6" in playbook
```

to:

```python
# M11: e2e-k3s-common.sh deleted. Ansible bootstrap/inventory helpers are now
# owned by AnsibleAdapter in tools/controlplane/src/controlplane_tool/ansible_adapter.py.
def test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python() -> None:
    assert not (REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )
```

**Step 5: Run the trimmed suite**

```bash
uv run --project tools/controlplane pytest scripts/tests -q
```

Expected: `30 passed` (37 minus the 7 removed: 1 deleted file + 1 removed from `test_e2e_runtime_contract.py` + 5 removed from `test_e2e_ansible_provisioning.py`).

**Step 6: Confirm nothing else references the deleted script**

```bash
grep -rn "scripts/controlplane\.sh" --include="*.py" --include="*.sh" scripts/ 2>/dev/null
```

Expected: no output (the only Python reference was the file just deleted; nothing under `scripts/` other than the deleted files referenced it).

**Step 7: Commit**

```bash
git add -u scripts
git commit -m "Delete scripts/controlplane.sh, now that nanolab.sh replaces it"
```

---

### Task 4: Repoint active docs at the nanolab checkout

**Repository:** `nanofaas` (`/Users/micheleciavotta/Downloads/mcFaas`)

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `platform/modules/k8s-deployment-provider/README.md`
- Modify: `platform/modules/container-deployment-provider/README.md`
- Modify: `docs/control-plane.md`
- Modify: `docs/quickstart.md`
- Modify: `docs/no-k8s-profile.md`
- Modify: `docs/tutorial-function.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`

**Interfaces:** none — this task only edits prose/examples, no code.

**Step 1: `README.md`**

Change:

```bash
scripts/controlplane.sh list
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh list
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

Then change:

```markdown
The external environment is suitable for a remote VM reachable through SSH and
provisioned separately with Ansible. Azure and Proxmox use the same workflow
model. See [the control-plane tool guide](tools/controlplane/README.md) and the
[quickstart](docs/quickstart.md).
```

to:

```markdown
The external environment is suitable for a remote VM reachable through SSH and
provisioned separately with Ansible. Azure and Proxmox use the same workflow
model. See [the nanolab guide](https://github.com/miciav/nanolab#readme) and
the [quickstart](docs/quickstart.md).
```

**Step 2: `AGENTS.md`**

Change:

```markdown
- `scripts/controlplane.sh e2e run docker` and `scripts/controlplane.sh e2e run buildpack` — run local E2E suites.
- `scripts/controlplane.sh e2e run k3s-junit-curl` — provision a Multipass VM with k3s, deploy via Helm, run curl checks, and then run `K8sE2eTest`.
```

to:

```markdown
- `nanolab.sh e2e run docker` and `nanolab.sh e2e run buildpack` (run from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo) — run local E2E suites.
- `nanolab.sh e2e run k3s-junit-curl` — provision a Multipass VM with k3s, deploy via Helm, run curl checks, and then run `K8sE2eTest`.
```

Change:

```markdown
- K8s E2E (`K8sE2eTest`) runs via `scripts/controlplane.sh e2e run k3s-junit-curl` on a real k3s cluster in Multipass.
```

to:

```markdown
- K8s E2E (`K8sE2eTest`) runs via `nanolab.sh e2e run k3s-junit-curl` on a real k3s cluster in Multipass.
```

**Step 3: `CLAUDE.md`**

Change the `Build & Development Commands` fenced block from:

```bash
# Canonical control-plane orchestration wrapper
./scripts/controlplane.sh --help
./scripts/controlplane.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run
./scripts/controlplane.sh e2e run validate-k3s --lifecycle multipass --dry-run
./scripts/controlplane.sh e2e all --only validate-k3s --dry-run

# Build all modules
./gradlew build

# Run locally
./scripts/controlplane.sh run --profile core # API on :8080, metrics on :8081
./gradlew :services:java:warm-echo:bootRun # Example service on :8080

# Run all tests
./gradlew test

# Run a single test class
./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.config.CoreDefaultsTest

# E2E tests (requires Docker)
./scripts/controlplane.sh e2e run validate-docker-pool
./scripts/controlplane.sh e2e run validate-buildpack-pool

# CLI E2E (full CLI against k3s, 47 tests)
./scripts/controlplane.sh cli-test run vm
./scripts/controlplane.sh cli-test run vm --no-cleanup-vm

# K3s E2E with Curl (self-contained Multipass VM)
./scripts/controlplane.sh e2e run validate-k3s
./scripts/controlplane.sh e2e run validate-k3s --no-cleanup-vm

# Kubernetes E2E (k3s in Multipass)
./scripts/controlplane.sh e2e run validate-k3s
# or:
./gradlew k8sE2e

# Build OCI images
./scripts/controlplane.sh image --profile all
./gradlew :services:java:warm-echo:bootBuildImage

# Control-plane optional module selection
./scripts/controlplane.sh run --profile all
./scripts/controlplane.sh test --profile all
./scripts/controlplane.sh jar --profile core
./scripts/controlplane.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run
# No-K8s managed deployment profile
./scripts/controlplane.sh run --profile container-local -- --args='--nanofaas.deployment.default-backend=container-local'
# Use --modules <csv|none|all> only for advanced overrides.
```

to:

```bash
# The ops/provisioning tool now lives in a separate checkout: https://github.com/miciav/nanolab
export NANOFAAS_ROOT="$(pwd)"   # nanolab commands below read nanoFaaS source from here

# Canonical control-plane orchestration wrapper
(cd ../nanolab && ./nanolab.sh --help)
(cd ../nanolab && ./nanolab.sh vm up --lifecycle multipass --name nanofaas-e2e --dry-run)
(cd ../nanolab && ./nanolab.sh e2e run validate-k3s --lifecycle multipass --dry-run)
(cd ../nanolab && ./nanolab.sh e2e all --only validate-k3s --dry-run)

# Build all modules
./gradlew build

# Run locally
(cd ../nanolab && ./nanolab.sh run --profile core) # API on :8080, metrics on :8081
./gradlew :services:java:warm-echo:bootRun # Example service on :8080

# Run all tests
./gradlew test

# Run a single test class
(cd ../nanolab && ./nanolab.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.config.CoreDefaultsTest)

# E2E tests (requires Docker)
(cd ../nanolab && ./nanolab.sh e2e run validate-docker-pool)
(cd ../nanolab && ./nanolab.sh e2e run validate-buildpack-pool)

# CLI E2E (full CLI against k3s, 47 tests)
(cd ../nanolab && ./nanolab.sh cli-test run vm)
(cd ../nanolab && ./nanolab.sh cli-test run vm --no-cleanup-vm)

# K3s E2E with Curl (self-contained Multipass VM)
(cd ../nanolab && ./nanolab.sh e2e run validate-k3s)
(cd ../nanolab && ./nanolab.sh e2e run validate-k3s --no-cleanup-vm)

# Kubernetes E2E (k3s in Multipass)
(cd ../nanolab && ./nanolab.sh e2e run validate-k3s)
# or:
./gradlew k8sE2e

# Build OCI images
(cd ../nanolab && ./nanolab.sh image --profile all)
./gradlew :services:java:warm-echo:bootBuildImage

# Control-plane optional module selection
(cd ../nanolab && ./nanolab.sh run --profile all)
(cd ../nanolab && ./nanolab.sh test --profile all)
(cd ../nanolab && ./nanolab.sh jar --profile core)
(cd ../nanolab && ./nanolab.sh matrix --task :control-plane:bootJar --max-combinations 4 --dry-run)
# No-K8s managed deployment profile
(cd ../nanolab && ./nanolab.sh run --profile container-local -- --args='--nanofaas.deployment.default-backend=container-local')
# Use --modules <csv|none|all> only for advanced overrides.
```

Also update the one prose line just above that block:

```markdown
VM-provisioning Ansible playbooks are bundled inside the `workflow_tasks` library (`tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/`).
```

to:

```markdown
VM-provisioning Ansible playbooks are bundled inside the `workflow_tasks` library in the `nanolab` repo (`packages/workflow-tasks/src/workflow_tasks/infra/ansible_assets/`).
```

**Step 4: `platform/modules/k8s-deployment-provider/README.md`**

Change:

```markdown
- E2E: `./scripts/controlplane.sh e2e run validate-k3s`.
```

to:

```markdown
- E2E: `./nanolab.sh e2e run validate-k3s` (from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo).
```

**Step 5: `platform/modules/container-deployment-provider/README.md`**

Change:

```markdown
- E2E: `./scripts/controlplane.sh e2e run validate-docker-pool`.
```

to:

```markdown
- E2E: `./nanolab.sh e2e run validate-docker-pool` (from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo).
```

**Step 6: `docs/control-plane.md`**

Change:

```bash
scripts/controlplane.sh inspect tools/controlplane/scenarios-v2/validate-k8s.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh inspect packages/nanolab/scenarios-v2/validate-k8s.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

**Step 7: `docs/quickstart.md`**

Change:

```bash
scripts/controlplane.sh doctor
scripts/controlplane.sh list
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh doctor
./nanolab.sh list
```

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

to (each fenced block in this doc stays independently copy-pasteable, so
repeat the two setup lines rather than relying on a previous block's `cd`):

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-container.yaml
```

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
```

Change the prose line:

```markdown
For an SSH-only remote VM, copy `tools/controlplane/environments/external.yaml.example`, set its host/user/home, provision the checkout with Ansible, then use the same `plan` or `run` command.

Azure and Proxmox use the same workflow. Copy the matching example from
`tools/controlplane/environments/`, fill in provider values, and run with
```

to:

```markdown
For an SSH-only remote VM, copy `packages/nanolab/environments/external.yaml.example` (in your `nanolab` checkout), set its host/user/home, provision the checkout with Ansible, then use the same `plan` or `run` command.

Azure and Proxmox use the same workflow. Copy the matching example from
`packages/nanolab/environments/`, fill in provider values, and run with
```

Change:

```bash
scripts/controlplane.sh tui
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
(cd ../nanolab && ./nanolab.sh tui)
```

**Step 8: `docs/no-k8s-profile.md`**

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-container.yaml
```

**Step 9: `docs/tutorial-function.md`**

Change:

```markdown
- Add the function key to a YAML scenario, inspect it with `scripts/controlplane.sh plan <scenario>`, then execute the same file with `scripts/controlplane.sh run <scenario>`.
```

to:

```markdown
- Add the function key to a YAML scenario, inspect it with `nanolab.sh plan <scenario>`, then execute the same file with `nanolab.sh run <scenario>` (from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo).
```

**Step 10: `docs/nanofaas-cli.md`**

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/cli.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/cli.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/cli.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/cli.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

**Step 11: `docs/testing.md`**

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/loadtest.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

**Step 12: `docs/e2e-tutorial.md`**

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-container.yaml
```

Change:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml \
  --provision
```

Change:

```markdown
Copy `tools/controlplane/environments/external.yaml.example` to `external.yaml` and set host/user/home. The first run connects over SSH, applies the same idempotent Ansible tasks, and synchronizes the repository; it never creates or destroys the remote VM:
```

to:

```markdown
Copy `packages/nanolab/environments/external.yaml.example` (in your `nanolab` checkout) to `external.yaml` and set host/user/home. The first run connects over SSH, applies the same idempotent Ansible tasks, and synchronizes the repository; it never creates or destroys the remote VM:
```

Change:

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --provision
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml \
  --provision)
```

Change:

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --provision \
  --run-dir tools/controlplane/runs/e2e
```

to:

```bash
export NANOFAAS_ROOT="$(pwd)"
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/loadtest.yaml \
  --environment packages/nanolab/environments/external.yaml \
  --provision \
  --run-dir packages/nanolab/runs/e2e)
```

**Step 13: Verify no active file still references the deleted script**

```bash
grep -rln "scripts/controlplane\.sh" --include="*.md" . 2>/dev/null | grep -v "^\./docs/plans/" | grep -v "^\./docs/superpowers/" | grep -v "^\./tools/controlplane/"
```

Expected: no output.

**Step 14: Commit**

```bash
git add README.md AGENTS.md CLAUDE.md platform/modules/k8s-deployment-provider/README.md platform/modules/container-deployment-provider/README.md docs/control-plane.md docs/quickstart.md docs/no-k8s-profile.md docs/tutorial-function.md docs/nanofaas-cli.md docs/testing.md docs/e2e-tutorial.md
git commit -m "Point docs at the nanolab checkout instead of scripts/controlplane.sh"
```

---

## Final verification checklist

From `/Users/micheleciavotta/Downloads/mcFaas/.worktrees/nanolab`:

```bash
uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests -q
uv run --locked --all-packages --all-groups pytest -c packages/workflow-tasks/pyproject.toml packages/workflow-tasks/tests -q
gh run list --repo miciav/nanolab --limit 3
```

From `/Users/micheleciavotta/Downloads/mcFaas`:

```bash
uv run --project tools/controlplane pytest scripts/tests -q
grep -rn "controlplane_tool\|controlplane-tool" scripts/ 2>/dev/null
git status --short
```

Expected:

- nanolab: `654 passed` for `packages/nanolab/tests` (651 baseline + 3 from Task 1), `478 passed` for `packages/workflow-tasks/tests` (475 baseline + 3 from Task 2), CI green on the latest `main` run.
- nanofaas: `30 passed`; the grep finds nothing under `scripts/` (the only prior references were in the deleted files); working tree has only the intended changes staged/committed.
