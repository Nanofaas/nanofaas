# Bundle Ansible playbooks into the workflow_tasks library (Design)

**Status:** approved (direction), pending implementation plan
**Date:** 2026-06-03

## Goal

Make `workflow_tasks` self-contained for VM provisioning by **bundling the Ansible
playbooks inside the library** instead of reaching out to `<repo_root>/ops/ansible/`
by convention. This removes the last hard repo-layout coupling that blocks consuming
`workflow_tasks` as a standalone package.

## Problem

`AnsibleAdapter` (in `workflow_tasks/infra/ansible.py`) orchestrates provisioning but
the *implementation* — the playbooks — lives at `<repo_root>/ops/ansible/`:
```python
self.ansible_root = self.repo_root / "ops" / "ansible"
```
Per the project's boundary rule ("how a thing is done" → library), the playbooks ARE
"how a VM is provisioned" and belong with the adapter. Today a standalone consumer of
the library would have to supply `ops/ansible/` in its own repo.

Verified: `ops/ansible/` is consumed only by the library itself (the adapter +
`components/bootstrap.py`), its tests, and docs — through the `AnsibleAdapter`. No CI,
Gradle, or external tool runs `ansible-playbook` on `ops/ansible/` directly. So the
move is clean.

## What moves

`ops/ansible/` (5 playbooks + `ansible.cfg` + `requirements.txt`):
```
ops/ansible/ansible.cfg
ops/ansible/requirements.txt
ops/ansible/playbooks/{provision-base,provision-k3s,ensure-registry,configure-k3s-registry,install-k6}.yml
```
→ `tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/` (same internal
layout: `playbooks/*.yml`, `ansible.cfg`, `requirements.txt`).

Layout choice: a sibling `ansible_assets/` directory next to the adapter module
`infra/ansible.py`, NOT `infra/ansible/` (which would clash with the existing
`ansible.py` module name and force a module→package rename / break
`from workflow_tasks.infra.ansible import AnsibleAdapter`).

## Adapter change

`AnsibleAdapter.__init__` gains an optional `ansible_root` override; the default points
at the bundled assets, resolved relative to the module file (works for editable and
wheel installs in this monorepo):
```python
def __init__(self, repo_root, ..., ansible_root: Path | None = None):
    self.repo_root = Path(repo_root)
    self.ansible_root = Path(ansible_root) if ansible_root is not None \
        else Path(__file__).parent / "ansible_assets"
    ...
```
`repo_root` is KEPT — it is still the `cwd` for the `ansible-playbook` subprocess runs
(ansible.py lines ~108/135/159/179). Only `ansible_root` stops depending on it.

Everything else (the `playbooks/<name>.yml` join, `ANSIBLE_CONFIG=<ansible_root>/ansible.cfg`)
is unchanged — it now resolves to the bundled directory.

## Packaging

`tools/workflow-tasks/pyproject.toml` — extend `[tool.setuptools.package-data]` so the
non-`.py` assets ship in the wheel:
```toml
[tool.setuptools.package-data]
workflow_tasks = ["py.typed", "infra/ansible_assets/**/*"]
```
(Confirm setuptools globs `**/*` for the .yml/.cfg/.txt; if not, list the concrete
patterns `infra/ansible_assets/*`, `infra/ansible_assets/playbooks/*`.)

## Reference updates

- **Tests** asserting the old path:
  - `tools/workflow-tasks/tests/infra/test_ansible.py::test_provision_base_uses_ops_ansible_root`
  - `tools/controlplane/tests/test_ansible_adapter.py::test_provision_base_uses_ops_ansible_root`
  Both assert `"ops/ansible/playbooks/provision-base.yml" in command`. Update to assert
  the playbook resolves under the bundled `ansible_assets/playbooks/provision-base.yml`
  (rename the tests accordingly, e.g. `..._uses_bundled_ansible_root`). Check
  `tools/workflow-tasks/tests/components/test_bootstrap.py`,
  `tests/vm/test_orchestrator.py`, and `tools/controlplane/tests/test_proxmox_prelude_workflow.py`
  for any `ops/ansible` assumption and update.
- **Docs**: `README.md`, `CLAUDE.md`, `tools/controlplane/README.md` mention
  `ops/ansible/...` (e.g. the `controlplane.sh` "provision the VM with
  ops/ansible/playbooks/provision-base.yml" hint). Repoint to the bundled location or
  reword to "the bundled provisioning playbooks in workflow_tasks". Also update the
  `AGENTS.md`/`CLAUDE.md` line "Ansible assets for VM provisioning live under `ops/ansible/`".
- **`ops/ansible/` directory is removed** from the repo root after the move.

## Testing / verification

- `uv run --project tools/workflow-tasks pytest tools/workflow-tasks` green (coverage gate 90 holds).
- `uv run --project tools/controlplane pytest tools/controlplane/tests` green.
- `uv run --project tools/workflow-tasks lint-imports` 0 broken.
- A test asserts the bundled assets exist and resolve: the default `AnsibleAdapter(repo_root=...)`
  (no `ansible_root`) produces a command whose playbook path is under
  `workflow_tasks/infra/ansible_assets/playbooks/` AND that file exists on disk.
- Build sanity: `uv build --project tools/workflow-tasks` (or equivalent) produces a wheel
  that includes the `ansible_assets` files (confirms package-data is correct). If `uv build`
  is unavailable, at minimum assert the files are present under `src/workflow_tasks/infra/ansible_assets/`.
- `grep -rn "ops/ansible" tools/ .github/ build.gradle` → no live references (docs updated, dir gone).

## Out of scope

- Genericizing the nanofaas-specific defaults inside the playbooks/components (registry
  name `nanofaas-e2e-registry`, etc.) — the library is deliberately nanofaas-opinionated.
- The `verification.py` components that shell out to `controlplane_tool` (separate
  runtime-string coupling; a different follow-up).
- Actually extracting `workflow_tasks` to its own GitHub repo (this just removes a blocker).

## Success criteria

- The 5 playbooks + `ansible.cfg` + `requirements.txt` live under
  `workflow_tasks/infra/ansible_assets/` and ship as package data.
- `AnsibleAdapter` defaults to the bundled assets (with an `ansible_root` override); no
  longer derives the path from `repo_root`.
- `ops/ansible/` is gone; no live reference remains; both suites green.
