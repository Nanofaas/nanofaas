# Bundle Ansible playbooks into workflow_tasks (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Ansible playbooks from `<repo_root>/ops/ansible/` into the `workflow_tasks` library as package data, so the library is self-contained for VM provisioning.

**Architecture:** `git mv ops/ansible → workflow_tasks/infra/ansible_assets/`; `AnsibleAdapter.ansible_root` defaults to that bundled directory (resolved via `Path(__file__).parent / "ansible_assets"`) with an `ansible_root` override; ship the assets via setuptools `package-data`. Tests that hard-coded `ops/ansible` paths are repointed to the bundled location (the proxmox oracle computes the bundled path dynamically).

**Tech Stack:** Python, setuptools package-data, Ansible (data only), pytest, uv.

**Commands:** library `uv run --project tools/workflow-tasks pytest <path>`; controlplane `uv run --project tools/controlplane pytest <path>`. Branch: `refactor/wt-bundle-ansible-playbooks` (created). Spec: `docs/superpowers/specs/2026-06-03-bundle-ansible-playbooks-design.md`. Baseline: both suites green.

**Verified facts:**
- `ops/ansible/` = `ansible.cfg`, `requirements.txt`, `playbooks/{provision-base,provision-k3s,ensure-registry,configure-k3s-registry,install-k6}.yml`.
- `AnsibleAdapter.__init__` (`tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`) sets `self.ansible_root = self.repo_root / "ops" / "ansible"`. `repo_root` is also the `cwd` for the runs (lines ~108/135/159/179) — KEEP it; only `ansible_root` changes.
- Tests asserting the path: `tools/workflow-tasks/tests/infra/test_ansible.py`, `tools/workflow-tasks/tests/components/test_bootstrap.py` (lines 33,40), `tools/workflow-tasks/tests/vm/test_orchestrator.py` (line 64), `tools/controlplane/tests/test_ansible_adapter.py`, `tools/controlplane/tests/test_proxmox_prelude_workflow.py` (literal snapshot, lines ~138/141/163/166/203/206/215/218).
- Docs/launchers: `README.md` (23,80), `CLAUDE.md` (71), `tools/controlplane/README.md` (161), `scripts/controlplane.sh` (8), `scripts/fn-init.sh` (8). (AGENTS.md may also mention it — grep.)

---

### Task 1: Move assets into the library + bundle + adapter default

**Files:**
- Move: `ops/ansible/**` → `tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/**`
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Modify: `tools/workflow-tasks/pyproject.toml`
- Modify: `tools/workflow-tasks/tests/infra/test_ansible.py`, `tests/components/test_bootstrap.py`, `tests/vm/test_orchestrator.py`
- Create: a bundled-resolution test in `tests/infra/test_ansible.py`

- [ ] **Step 1: Move the assets with git mv**

```bash
mkdir -p tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets
git mv ops/ansible/ansible.cfg       tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/ansible.cfg
git mv ops/ansible/requirements.txt  tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/requirements.txt
git mv ops/ansible/playbooks         tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks
rmdir ops/ansible 2>/dev/null || true
```
Verify: `ls tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/` lists the 5 `.yml` files, and `ops/ansible` no longer exists.

- [ ] **Step 2: Point `AnsibleAdapter.ansible_root` at the bundled assets**

In `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`, change the `__init__` signature and the `ansible_root` line:

```python
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        host_resolver: HostResolver | None = None,
        private_key_path: Path | None = None,
        multipass_client: MultipassClient | None = None,
        ansible_root: Path | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        # Playbooks are bundled with the library; callers may override.
        self.ansible_root = (
            Path(ansible_root)
            if ansible_root is not None
            else Path(__file__).parent / "ansible_assets"
        )
```
(Delete the old `# Repo layout convention...` comment + `self.ansible_root = self.repo_root / "ops" / "ansible"` line. Leave everything else — `repo_root` stays the cwd.)

- [ ] **Step 3: Ship the assets as package data**

In `tools/workflow-tasks/pyproject.toml`, replace the `[tool.setuptools.package-data]` block:
```toml
[tool.setuptools.package-data]
workflow_tasks = ["py.typed", "infra/ansible_assets/ansible.cfg", "infra/ansible_assets/requirements.txt", "infra/ansible_assets/playbooks/*.yml"]
```

- [ ] **Step 4: Update + add library tests**

In `tools/workflow-tasks/tests/infra/test_ansible.py`, rename `test_provision_base_uses_ops_ansible_root` → `test_provision_base_uses_bundled_ansible_root` and change the path assertion:
```python
def test_provision_base_uses_bundled_ansible_root() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_base(request, dry_run=True)

    command = shell.commands[0]
    assert "ansible-playbook" in command
    assert "infra/ansible_assets/playbooks/provision-base.yml" in " ".join(command)
    assert "vm.example.test," in command


def test_bundled_ansible_assets_exist_on_disk() -> None:
    adapter = AnsibleAdapter(repo_root=Path("/repo"))
    assert (adapter.ansible_root / "playbooks" / "provision-base.yml").is_file()
    assert (adapter.ansible_root / "ansible.cfg").is_file()


def test_ansible_root_override_is_respected(tmp_path: Path) -> None:
    adapter = AnsibleAdapter(repo_root=Path("/repo"), ansible_root=tmp_path)
    assert adapter.ansible_root == tmp_path
```

In `tools/workflow-tasks/tests/components/test_bootstrap.py`, update lines 33 and 40:
```python
    assert "infra/ansible_assets/playbooks/" in rendered
```
```python
    assert any("infra/ansible_assets/ansible.cfg" in str(v) for v in env.values())
```

In `tools/workflow-tasks/tests/vm/test_orchestrator.py` line 64:
```python
    assert "infra/ansible_assets/playbooks/provision-base.yml" in rendered
```

- [ ] **Step 5: Run the library suite**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks -q 2>&1 | tail -4`
Expected: all pass; coverage ≥ 90%. (The new `test_bundled_ansible_assets_exist_on_disk` proves the assets resolve from the package.)

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks ":(glob)tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/**"
git commit -m "feat(workflow-tasks): bundle ansible playbooks as package data"
```

---

### Task 2: Repoint controlplane tests

**Files:**
- Modify: `tools/controlplane/tests/test_ansible_adapter.py`
- Modify: `tools/controlplane/tests/test_proxmox_prelude_workflow.py`

- [ ] **Step 1: Update `test_ansible_adapter.py`**

Rename `test_provision_base_uses_ops_ansible_root` → `test_provision_base_uses_bundled_ansible_root` and change line 17:
```python
    assert "infra/ansible_assets/playbooks/provision-base.yml" in " ".join(command)
```

- [ ] **Step 2: Make the proxmox oracle compute the bundled ansible paths dynamically**

In `tools/controlplane/tests/test_proxmox_prelude_workflow.py`, the literal `/repo/ops/ansible/...` paths no longer match (the playbooks are now an absolute package path). Add near the top of the module (after imports):
```python
from workflow_tasks.infra.ansible import AnsibleAdapter

_ANSIBLE_ROOT = AnsibleAdapter(repo_root=Path("/repo")).ansible_root


def _playbook(name: str) -> str:
    return str(_ANSIBLE_ROOT / "playbooks" / name)


_ANSIBLE_CFG = str(_ANSIBLE_ROOT / "ansible.cfg")
```
Then in `EXPECTED_PRELUDE_COMMANDS`, replace each literal ansible path and config:
- `"/repo/ops/ansible/playbooks/provision-base.yml"` → `_playbook("provision-base.yml")`
- `"/repo/ops/ansible/playbooks/ensure-registry.yml"` → `_playbook("ensure-registry.yml")`
- `"/repo/ops/ansible/playbooks/provision-k3s.yml"` → `_playbook("provision-k3s.yml")`
- `"/repo/ops/ansible/playbooks/configure-k3s-registry.yml"` → `_playbook("configure-k3s-registry.yml")`
- every `"env": {"ANSIBLE_CONFIG": "/repo/ops/ansible/ansible.cfg"}` → `"env": {"ANSIBLE_CONFIG": _ANSIBLE_CFG}`

(`Path` is already imported in that test; if not, add `from pathlib import Path`.)

- [ ] **Step 3: Run the controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3`
Expected: 0 failures.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/tests/test_ansible_adapter.py tools/controlplane/tests/test_proxmox_prelude_workflow.py
git commit -m "test(controlplane): point ansible tests at bundled workflow_tasks playbooks"
```

---

### Task 3: Update docs + final verification

**Files:**
- Modify: `README.md`, `CLAUDE.md`, `AGENTS.md` (if it mentions it), `tools/controlplane/README.md`, `scripts/controlplane.sh`, `scripts/fn-init.sh`

- [ ] **Step 1: Repoint the docs and launcher hints**

- `scripts/controlplane.sh:8` and `scripts/fn-init.sh:8` — change the hint text:
  ```
  echo "uv not found. Install uv (https://github.com/astral-sh/uv) and retry." >&2
  ```
  (Drop the `ops/ansible/playbooks/provision-base.yml` reference — provisioning is now bundled in the library.)
- `README.md:23` and `:80`, `CLAUDE.md:71`, `tools/controlplane/README.md:161` — reword the "Ansible assets live under `ops/ansible/`" lines to: "VM provisioning Ansible playbooks are bundled inside the `workflow_tasks` library (`workflow_tasks/infra/ansible_assets/`)."
- Grep `AGENTS.md` for `ops/ansible`; if present, reword the same way.

- [ ] **Step 2: Verify no live `ops/ansible` reference remains**

```bash
grep -rn "ops/ansible" . --include="*.py" --include="*.sh" --include="*.md" --include="*.toml" --include="*.yml" --include="*.yaml" --include="*.gradle" \
  | grep -v "docs/plans/\|docs/superpowers/\|experiments/control-plane-staging/versions/\|node_modules\|\.git/"
```
Expected: EMPTY. And `test -d ops/ansible && echo EXISTS || echo GONE` → `GONE`.

- [ ] **Step 3: Package-data build sanity**

Confirm the assets are inside the package source tree (they ship because package-data points at them):
```bash
ls tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/*.yml | wc -l   # expect 5
```
If `uv build --project tools/workflow-tasks` is available, run it and confirm the wheel lists `workflow_tasks/infra/ansible_assets/playbooks/provision-base.yml` (e.g. `unzip -l dist/*.whl | grep ansible_assets`); otherwise the in-tree check above + the `test_bundled_ansible_assets_exist_on_disk` test are sufficient (editable installs resolve the files in place).

- [ ] **Step 4: Full verification both suites + linters**

```bash
uv run --project tools/workflow-tasks pytest tools/workflow-tasks -q 2>&1 | tail -3
uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -3
uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter
uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter
```
Expected: both suites pass; import-linter 0 broken on both.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: point provisioning docs at bundled ansible playbooks; remove ops/ansible"
```

---

## Self-Review

- **Spec coverage:** move assets → Task 1 Step 1; adapter default + override → Step 2; package-data → Step 3; library tests (incl. bundled-resolution + override) → Step 4; controlplane test repoint incl. dynamic proxmox oracle → Task 2; docs + launcher hints + remove dir + verify → Task 3. `repo_root` kept as cwd is preserved (only the `ansible_root` line changes). ✓
- **Placeholder scan:** none — full code for the adapter change, package-data, every test edit (incl. the dynamic `_playbook`/`_ANSIBLE_CFG` helpers), exact git mv + grep commands with expected output.
- **Type consistency:** the bundled directory name `ansible_assets` is used identically in the git mv target, the adapter default (`Path(__file__).parent / "ansible_assets"`), package-data globs, and every test assertion/helper. The new `ansible_root` param name matches between the adapter signature and the override test. ✓
