# Ansible Task Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide one reusable, TUI-aware ansible task (`RunPlaybook`) plus a shared `install_k6_task` factory, and migrate the `two-vm-loadtest` loadgen k6 install from the hand-built bash `InstallK6` to ansible — additively (bash kept, deprecated).

**Architecture:** Reuse the existing `AnsibleAdapter` (`workflow_tasks/infra/ansible.py`), which already builds and runs `ansible-playbook` on the host shell (with live TUI log streaming via `SubprocessShell`). Add a public generic `run_playbook()` method (DRY-refactoring the typed methods onto it), a thin `RunPlaybook` Task wrapper, and a connectivity-parametric `install_k6_task` factory. The factory turns per-lifecycle SSH connectivity into constructor arguments `(host, user, private_key, port)` — `port` flows through ansible's `-e ansible_port=...`.

**Tech Stack:** Python 3.12, `dataclasses`, `pytest`, `uv`, ansible (`ansible-playbook`), `workflow_tasks` library, `controlplane_tool`.

**Scope:** Phase 1 (reusable bricks + tests) and Phase 2a (two-vm migration). `azure-vm-loadtest` and `proxmox-vm-loadtest` migrations are **deferred** to a follow-up plan — they require first exposing each orchestrator's loadgen SSH endpoint (proxmox: published host + mapped SSH port + key; azure: public host + key). See "Deferred" at the end.

**Note vs spec:** the spec proposed a new `workflow_tasks/infra/ansible/` directory. Since `workflow_tasks/infra/ansible.py` already exists and houses `AnsibleAdapter`, `RunPlaybook` and `install_k6_task` are co-located there (cohesion, no import breakage). Same intent, pragmatic placement.

---

## File Structure

- `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py` — add `run_playbook()` + `install_k6()` to `AnsibleAdapter`; add `RunPlaybook` task and `install_k6_task` factory.
- `tools/workflow-tasks/src/workflow_tasks/__init__.py` — export `RunPlaybook`, `install_k6_task`.
- `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py` — new tests for `run_playbook`, `RunPlaybook`, `install_k6_task`.
- `tools/workflow-tasks/tests/test_public_api.py` — assert new exports.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — replace bash `InstallK6` instantiation with `install_k6_task(...)`.

---

## Task 1: `AnsibleAdapter.run_playbook` (generic) + `install_k6`

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Test: `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py`

- [ ] **Step 1: Write the failing test**

Create `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py`:

```python
from pathlib import Path

from workflow_tasks.infra.ansible import AnsibleAdapter
from workflow_tasks.shell import RecordingShell
from workflow_tasks.vm.models import VmRequest


def _external_request() -> VmRequest:
    return VmRequest(lifecycle="external", host="10.0.0.5", user="ubuntu")


def test_run_playbook_builds_ansible_command_and_runs_on_host() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
        private_key_path=Path("/keys/id_ed25519"),
    )

    adapter.run_playbook(
        "install-k6.yml",
        _external_request(),
        extra_vars={"ansible_port": "2222"},
    )

    assert len(shell.commands) == 1
    command = shell.commands[0]
    assert command[0] == "ansible-playbook"
    assert "-i" in command and "10.0.0.5," in command
    assert "-u" in command and "ubuntu" in command
    assert "--private-key" in command and "/keys/id_ed25519" in command
    assert "-e" in command and "ansible_port=2222" in command
    assert command[-1].endswith("playbooks/install-k6.yml")


def test_install_k6_uses_install_k6_playbook() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )

    adapter.install_k6(_external_request())

    assert shell.commands[0][-1].endswith("playbooks/install-k6.yml")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v`
Expected: FAIL with `AttributeError: 'AnsibleAdapter' object has no attribute 'run_playbook'` (and `install_k6`).

- [ ] **Step 3: Add `run_playbook` and `install_k6`, refactor typed methods onto `run_playbook`**

In `ansible.py`, add the public method right after `_build_command`:

```python
    def run_playbook(
        self,
        playbook_name: str,
        request: VmRequest,
        *,
        extra_vars: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            playbook_name, request, extra_vars=extra_vars, dry_run=dry_run
        )
        return self.shell.run(command, cwd=self.repo_root, env=env, dry_run=dry_run)

    def install_k6(
        self,
        request: VmRequest,
        *,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook("install-k6.yml", request, dry_run=dry_run)
```

Then DRY-refactor the existing typed methods to delegate. For `provision_base` replace its body with:

```python
    def provision_base(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.run_playbook(
            "provision-base.yml",
            request,
            extra_vars={
                "install_helm": str(install_helm).lower(),
                "helm_version": helm_version.removeprefix("v"),
                "vm_user": request.user,
            },
            dry_run=dry_run,
        )
```

Apply the same delegation to `provision_k3s`, `ensure_registry_container`, and `configure_k3s_registry` (replace their `command, env = self._build_command(...)` + `return self.shell.run(...)` bodies with a single `return self.run_playbook(<playbook>, request, extra_vars=<same extra_vars>, dry_run=dry_run)`). Leave `configure_registry` (the two-step composite) unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v`
Expected: PASS (both tests).

- [ ] **Step 5: Run the existing ansible-adapter test to confirm the refactor is behavior-preserving**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_ansible_adapter.py -v`
Expected: PASS (no regressions in provision_base/provision_k3s/registry behavior).

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/infra/ansible.py tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py
git commit -m "feat(ansible): add generic AnsibleAdapter.run_playbook + install_k6"
```

---

## Task 2: `RunPlaybook` task

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py`

- [ ] **Step 1: Write the failing test**

Append to `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py`:

```python
import pytest

from workflow_tasks.infra.ansible import RunPlaybook
from workflow_tasks.shell import ShellBackend, ShellExecutionResult


class _FailingShell(ShellBackend):
    """Minimal shell that always fails — pattern mirrors tests/infra/test_ansible.py."""

    def run(self, command, *, cwd=None, env=None, dry_run=False) -> ShellExecutionResult:
        return ShellExecutionResult(command=command, return_code=2, stderr="boom")


def test_run_playbook_task_runs_playbook_and_returns_none() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=shell,
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )
    task = RunPlaybook(
        task_id="loadgen.install_k6",
        title="Install k6 on loadgen VM",
        adapter=adapter,
        playbook="install-k6.yml",
        request=_external_request(),
    )

    assert task.run() is None
    assert shell.commands[0][-1].endswith("playbooks/install-k6.yml")


def test_run_playbook_task_raises_on_nonzero_exit() -> None:
    adapter = AnsibleAdapter(
        repo_root=Path("/repo"),
        shell=_FailingShell(),
        host_resolver=lambda request, dry_run=False: "10.0.0.5",
    )
    task = RunPlaybook(
        task_id="loadgen.install_k6",
        title="Install k6",
        adapter=adapter,
        playbook="install-k6.yml",
        request=_external_request(),
    )

    with pytest.raises(RuntimeError, match="boom"):
        task.run()
```

> `RecordingShell` (from `workflow_tasks.shell`) records each invocation in `.commands` (a `list[list[str]]`) and returns `return_code=0`. `ShellExecutionResult` fields: `command, return_code, stdout="", stderr="", dry_run=False, env={}`. Verified against `shellcraft.backend`.

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v -k RunPlaybook`
Expected: FAIL with `ImportError: cannot import name 'RunPlaybook'`.

- [ ] **Step 3: Add `RunPlaybook` to `ansible.py`**

Add `from dataclasses import dataclass` to the imports, then at the end of `ansible.py`:

```python
@dataclass
class RunPlaybook:
    """Honest Task that runs an ansible playbook on the host via AnsibleAdapter.

    Connectivity is parametric through the injected adapter (host_resolver +
    private_key_path) and extra_vars (e.g. ansible_port for non-22 SSH).
    Satisfies the workflow_tasks.Task protocol; raises on non-zero exit so
    Workflow.run() stops and triggers cleanup.
    """

    task_id: str
    title: str
    adapter: AnsibleAdapter
    playbook: str
    request: VmRequest
    extra_vars: dict[str, str] | None = None

    def run(self) -> None:
        result = self.adapter.run_playbook(
            self.playbook, self.request, extra_vars=self.extra_vars
        )
        if result.return_code != 0:
            raise RuntimeError(
                result.stderr.strip()
                or result.stdout.strip()
                or f"{self.task_id} failed (exit {result.return_code})"
            )
```

- [ ] **Step 4: Export from the package `__init__.py`**

In `tools/workflow-tasks/src/workflow_tasks/__init__.py`, after the loadtest import block add:

```python
from workflow_tasks.infra.ansible import RunPlaybook, install_k6_task
```

and add `"RunPlaybook", "install_k6_task",` to `__all__` (e.g. at the end of the vm section).

> `install_k6_task` is created in Task 3; importing it here now will fail until Task 3. To keep Task 2 green in isolation, import only `RunPlaybook` here and add `install_k6_task` to the import + `__all__` in Task 3 Step 4.

- [ ] **Step 5: Run tests to verify they pass**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v`
Expected: PASS (all tests).

- [ ] **Step 6: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/infra/ansible.py tools/workflow-tasks/src/workflow_tasks/__init__.py tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py
git commit -m "feat(ansible): add RunPlaybook honest task"
```

---

## Task 3: `install_k6_task` shared factory (the connectivity adapter)

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py`

- [ ] **Step 1: Write the failing test**

Append:

```python
from workflow_tasks.infra.ansible import install_k6_task


def test_install_k6_task_factory_builds_runplaybook_with_connectivity() -> None:
    shell = RecordingShell()
    task = install_k6_task(
        task_id="loadgen.install_k6",
        title="Install k6 on loadgen VM",
        repo_root=Path("/repo"),
        shell=shell,
        host="10.0.0.5",
        user="ubuntu",
        private_key=Path("/keys/id_ed25519"),
        port=2222,
    )

    assert isinstance(task, RunPlaybook)
    assert task.task_id == "loadgen.install_k6"
    assert task.playbook == "install-k6.yml"

    task.run()
    command = shell.commands[0]
    assert "10.0.0.5," in command
    assert "ubuntu" in command
    assert "/keys/id_ed25519" in command
    assert "ansible_port=2222" in command


def test_install_k6_task_factory_omits_port_when_none() -> None:
    shell = RecordingShell()
    task = install_k6_task(
        task_id="loadgen.install_k6",
        title="Install k6",
        repo_root=Path("/repo"),
        shell=shell,
        host="10.0.0.5",
        user="ubuntu",
    )
    task.run()
    assert not any("ansible_port=" in arg for arg in shell.commands[0])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v -k install_k6_task`
Expected: FAIL with `ImportError: cannot import name 'install_k6_task'`.

- [ ] **Step 3: Add the factory to `ansible.py`**

Add `from workflow_tasks.shell import ShellBackend` to imports if not present (it already imports `ShellBackend`). Then append:

```python
def install_k6_task(
    *,
    task_id: str,
    title: str,
    repo_root: Path,
    shell: ShellBackend,
    host: str,
    user: str,
    private_key: Path | None = None,
    port: int | None = None,
) -> RunPlaybook:
    """Build a RunPlaybook for the k6 install against a resolved VM endpoint.

    The single, shared way to install k6 via ansible. Per-lifecycle connectivity
    is captured as plain arguments:
      - multipass: host=<resolved IP>, default user, multipass key, port=None
      - proxmox:   host=<proxmox host>, port=<published SSH port>, proxmox key
      - azure:     host=<public IP>, azure key, port=None
    """
    adapter = AnsibleAdapter(
        repo_root=repo_root,
        shell=shell,
        host_resolver=lambda request, dry_run=False: host,
        private_key_path=private_key,
    )
    request = VmRequest(lifecycle="external", host=host, user=user)
    extra_vars = {"ansible_port": str(port)} if port is not None else None
    return RunPlaybook(
        task_id=task_id,
        title=title,
        adapter=adapter,
        playbook="install-k6.yml",
        request=request,
        extra_vars=extra_vars,
    )
```

- [ ] **Step 4: Add `install_k6_task` to exports**

In `tools/workflow-tasks/src/workflow_tasks/__init__.py`, change the Task-2 import line to:

```python
from workflow_tasks.infra.ansible import RunPlaybook, install_k6_task
```

and ensure `__all__` contains both `"RunPlaybook"` and `"install_k6_task"`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py -v`
Expected: PASS (all tests).

- [ ] **Step 6: Update public-API test**

In `tools/workflow-tasks/tests/test_public_api.py`, add near the existing `assert hasattr(workflow_tasks, "InstallK6")` line:

```python
    assert hasattr(workflow_tasks, "RunPlaybook")
    assert hasattr(workflow_tasks, "install_k6_task")
```

- [ ] **Step 7: Run the public-API + boundary tests**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_public_api.py tools/workflow-tasks/tests/test_package_boundaries.py -v`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/infra/ansible.py tools/workflow-tasks/src/workflow_tasks/__init__.py tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py tools/workflow-tasks/tests/test_public_api.py
git commit -m "feat(ansible): add install_k6_task factory + export"
```

---

## Task 4: Migrate `two-vm-loadtest` loadgen install to ansible

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py:224-231`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_plan.py`

Context: `two_vm_loadtest.py` `run()` builds the loadgen `Workflow` (lines ~224-258) with `InstallK6(task_id="loadgen.install_k6", ..., runner=loadgen_runner, remote_dir=remote_home)` as the first task. `loadgen_info` (a `VmInfo` with `.host`) is available from `ensure_loadgen.run()` (line ~168). A freshly-launched multipass VM is reachable by host-side ansible with the user's SSH key — this is how the stack's `vm.provision_base` (ansible) already works.

- [ ] **Step 1: Write the failing test**

Add to `tools/controlplane/tests/test_two_vm_loadtest_plan.py`:

```python
def test_two_vm_loadgen_install_uses_runplaybook_not_bash() -> None:
    """The loadgen install step must be the ansible RunPlaybook, not bash InstallK6."""
    import inspect

    from controlplane_tool.scenario.scenarios import two_vm_loadtest

    source = inspect.getsource(two_vm_loadtest.TwoVmLoadtestPlan.run)
    assert "install_k6_task(" in source
    assert "InstallK6(" not in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py::test_two_vm_loadgen_install_uses_runplaybook_not_bash -v`
Expected: FAIL — `run()` still contains `InstallK6(` and not `install_k6_task(`.

- [ ] **Step 3: Update imports in `two_vm_loadtest.py`**

In the `from workflow_tasks import (...)` block (lines ~7-18), remove `InstallK6,` and add `install_k6_task,`. Add these imports near the other `from workflow_tasks...` lines:

```python
from multipass import find_ssh_public_key
from workflow_tasks.vm.multipass import _find_ssh_private_key_path
```

- [ ] **Step 4: Replace the bash install task with the factory**

In `run()`, replace the `InstallK6(...)` entry (lines ~226-231) inside the `Workflow(tasks=[...])` with:

```python
                install_k6_task(
                    task_id="loadgen.install_k6",
                    title="Install k6 on loadgen VM",
                    repo_root=self.runner.paths.workspace_root,
                    shell=self.runner.shell,
                    host=loadgen_info.host,
                    user=request.loadgen_vm.user,
                    private_key=_find_ssh_private_key_path(find_ssh_public_key()),
                ),
```

- [ ] **Step 5: Run the migration test + the existing plan test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py -v`
Expected: PASS — both `test_two_vm_loadtest_plan_has_expected_task_ids` (task_id `loadgen.install_k6` still present) and the new RunPlaybook test.

- [ ] **Step 6: Run the broader two-vm + scenario suites**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_two_vm_loadtest_runner.py tools/controlplane/tests/test_scenario_recipes.py -v`
Expected: PASS (recipe still lists `loadgen.install_k6`; runner/report unaffected).

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py tools/controlplane/tests/test_two_vm_loadtest_plan.py
git commit -m "refactor(two-vm-loadtest): install k6 via ansible RunPlaybook"
```

---

## Task 5: Full suites + deprecation note

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py` (docstring only)

- [ ] **Step 1: Mark bash `InstallK6` as deprecated (kept, not deleted)**

In `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py`, add to the `InstallK6` class docstring:

```python
@dataclass
class InstallK6:
    """DEPRECATED: bash binary-download k6 install (runs on the VM).

    Superseded by the ansible path: ``install_k6_task`` / ``RunPlaybook`` with
    ``install-k6.yml``. Retained for back-compat until all loadtest scenarios
    (azure, proxmox) are migrated. Do not use in new code.
    """
    task_id: str
    ...
```

- [ ] **Step 2: Run the full workflow_tasks suite**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q`
Expected: PASS.

- [ ] **Step 3: Run the full controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS. (Per project lore, always run the full controlplane suite even for library changes — `test_milestone_gates.py` / `test_wrapper_docs.py` assert on script existence; this change touches neither, so they should stay green.)

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py
git commit -m "docs(loadtest): mark bash InstallK6 deprecated in favor of ansible"
```

---

## Deferred (follow-up plan): azure & proxmox loadgen migration

Not in this plan because accurate code requires first **exposing each orchestrator's loadgen SSH endpoint** for host-side ansible:

- **proxmox** — ansible from host must target the **proxmox host at the published SSH port** (not the guest IP). Prerequisite: resolve `(host, port, key)` for the loadgen VM via the proxmox orchestrator (`_ssh_key(loadgen_request)`, `_published_rule(loadgen_request, "SSH").host_port`) and ensure the loadgen SSH port is published. Then call `install_k6_task(..., host=<proxmox_host>, port=<published_port>, private_key=<key>)` in `proxmox_vm_loadtest.py`'s `_install_k6`.
- **azure** — confirm `AzureVmOrchestrator` exposes the loadgen VM's public host (`loadgen_info.host`) **and** SSH key path/user. Then call `install_k6_task(..., host=loadgen_info.host, user=..., private_key=<azure key>)` in `azure_vm_loadtest.py`'s `run()`.

Both then update their argv-oracle tests (`test_proxmox_vm_loadtest_plan.py`, `test_azure_vm_loadtest_runner.py`) to expect `ansible-playbook ... install-k6.yml`. The `install_k6_task` factory built here is the shared brick they will reuse.

---

## Self-Review

- **Spec coverage:** RunPlaybook (§4.1) → Tasks 2; generic via AnsibleAdapter.run_playbook → Task 1; TUI integration (§4.2) → satisfied by SubprocessShell live-log streaming (baseline; structured sub-steps noted as future); connectivity adapter (§4.4 Axis B) → `install_k6_task` factory (Task 3); two-vm migration (§4.3) → Task 4; additive/bash kept (§3 goals) → Task 5; azure/proxmox + recipe-fragment composition (§4.4 Axis A, Phase 3) → explicitly Deferred, matching the spec's phasing. Mapping (§2) lives in the committed spec.
- **Placeholder scan:** no TBD/TODO; all code blocks concrete. The one conditional note (`ScriptedShell` signature) gives the behavioral contract to satisfy.
- **Type consistency:** `RunPlaybook(task_id, title, adapter, playbook, request, extra_vars)` used identically in Tasks 2/3/4; `install_k6_task(*, task_id, title, repo_root, shell, host, user, private_key=None, port=None)` keyword-only signature used identically in Task 3 tests and Task 4 call site; `AnsibleAdapter.run_playbook(playbook_name, request, *, extra_vars, dry_run)` matches `_build_command`'s existing signature.
