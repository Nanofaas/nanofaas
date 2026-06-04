# Ansible Task Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide one reusable, TUI-aware ansible task (`RunPlaybook`) plus a shared `install_k6_task` factory, and migrate the loadgen k6 install of all three loadtest scenarios (`two-vm`, `azure`, `proxmox`) from the hand-built bash `InstallK6` to ansible — additively (bash kept, deprecated).

**Architecture:** Reuse the existing `AnsibleAdapter` (`workflow_tasks/infra/ansible.py`), which already builds and runs `ansible-playbook` on the host shell (with live TUI log streaming via `SubprocessShell`). Add a public generic `run_playbook()` method (DRY-refactoring the typed methods onto it), a thin `RunPlaybook` Task wrapper, and a connectivity-parametric `install_k6_task` factory. The factory turns per-lifecycle SSH connectivity into constructor arguments `(host, user, private_key, port)` — `port` flows through ansible's `-e ansible_port=...`.

**Tech Stack:** Python 3.12, `dataclasses`, `pytest`, `uv`, ansible (`ansible-playbook`), `workflow_tasks` library, `controlplane_tool`.

**Scope:** Phase 1 (reusable bricks + tests) and Phase 2 — migrating the loadgen k6 install of **all three** loadtest scenarios (`two-vm`, `azure`, `proxmox`) to ansible. The orchestrators already expose the needed loadgen SSH endpoint: proxmox via public `ssh_endpoint()` + `ssh_private_key_path()`; azure via `connection_host()` + a new `ssh_private_key_path()` (added in Task 5). Out of scope (Phase 3, documented in the spec): recipe-fragment composition to collapse near-identical scenarios, and helm/namespace/cleanup → ansible.

**Note vs spec:** the spec proposed a new `workflow_tasks/infra/ansible/` directory. Since `workflow_tasks/infra/ansible.py` already exists and houses `AnsibleAdapter`, `RunPlaybook` and `install_k6_task` are co-located there (cohesion, no import breakage). Same intent, pragmatic placement.

---

## File Structure

- `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py` — add `run_playbook()` + `install_k6()` to `AnsibleAdapter`; add `RunPlaybook` task and `install_k6_task` factory.
- `tools/workflow-tasks/src/workflow_tasks/__init__.py` — export `RunPlaybook`, `install_k6_task`.
- `tools/workflow-tasks/tests/infra/test_ansible_run_playbook.py` — new tests for `run_playbook`, `RunPlaybook`, `install_k6_task`.
- `tools/workflow-tasks/tests/test_public_api.py` — assert new exports.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — replace bash `InstallK6` instantiation with `install_k6_task(...)`.
- `tools/workflow-tasks/src/workflow_tasks/vm/azure.py` — add public `ssh_private_key_path()` (mirrors proxmox).
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py` — replace bash `InstallK6` in `run()` with `install_k6_task(...)`.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` — replace bash `InstallK6` in `_install_k6` with `install_k6_task(...)`.

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

## Task 5: Add public `ssh_private_key_path` to `AzureVmProvider`

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/azure.py`
- Test: `tools/workflow-tasks/tests/test_vm_lifecycle_adapters.py` (or the azure provider test module)

Context: `AzureVmProvider` has a private `_ssh_key(request)` and a public `connection_host(request)`. Proxmox already exposes a public `ssh_private_key_path()`. Add the symmetric public method to azure so scenario code resolves the loadgen key through a clean boundary.

- [ ] **Step 1: Write the failing test**

Add to the azure provider test module (e.g. `tools/workflow-tasks/tests/test_vm_lifecycle_adapters.py`):

```python
def test_azure_provider_exposes_ssh_private_key_path(tmp_path) -> None:
    from pathlib import Path

    from workflow_tasks.vm.azure import AzureVmProvider
    from workflow_tasks.vm.models import VmRequest

    key = tmp_path / "id_ed25519"
    key.write_text("x")
    provider = AzureVmProvider(repo_root=Path("/repo"))
    request = VmRequest(lifecycle="azure", name="loadgen", user="azureuser", azure_ssh_key_path=str(key))

    assert provider.ssh_private_key_path(request) == key
```

> If `VmRequest` lacks `azure_ssh_key_path`, check the field name in `workflow_tasks/vm/models.py` and use the actual one (the azure provider reads `request.azure_ssh_key_path`).

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_vm_lifecycle_adapters.py -v -k ssh_private_key_path`
Expected: FAIL with `AttributeError: 'AzureVmProvider' object has no attribute 'ssh_private_key_path'`.

- [ ] **Step 3: Add the public method**

In `azure.py`, add right after `_ssh_key`:

```python
    def ssh_private_key_path(self, request: VmRequest) -> Path | None:
        return self._ssh_key(request)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_vm_lifecycle_adapters.py -v -k ssh_private_key_path`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/vm/azure.py tools/workflow-tasks/tests/test_vm_lifecycle_adapters.py
git commit -m "feat(azure): expose public ssh_private_key_path"
```

---

## Task 6: Migrate `azure-vm-loadtest` loadgen install to ansible

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py:148-151`
- Test: `tools/controlplane/tests/test_azure_vm_loadtest_runner.py`

Context: `azure_vm_loadtest.py` `run()` (lines ~84-166) builds `azure_orch = AzureVmOrchestrator(...)`, ensures both VMs, then a `Workflow(tasks=[InstallK6(task_id=s_install_k6.task_id, ..., runner=loadgen_runner, remote_dir=remote_home), ...])`. The `_skeleton()` method keeps its `InstallK6(..., runner=None, ...)` placeholder purely for `task_id`/`title` — leave it; only `run()`'s real instantiation changes. Azure VMs have public IPs reachable by host ansible; `connection_host()` returns the public IP, `ssh_private_key_path()` (Task 5) the key.

- [ ] **Step 1: Write the failing test**

Add to `tools/controlplane/tests/test_azure_vm_loadtest_runner.py`:

```python
def test_azure_loadgen_install_uses_runplaybook_not_bash() -> None:
    import inspect

    from controlplane_tool.scenario.scenarios import azure_vm_loadtest

    source = inspect.getsource(azure_vm_loadtest.AzureVmLoadtestPlan.run)
    assert "install_k6_task(" in source
    assert "InstallK6(" not in source
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_azure_vm_loadtest_runner.py::test_azure_loadgen_install_uses_runplaybook_not_bash -v`
Expected: FAIL — `run()` still contains `InstallK6(`.

- [ ] **Step 3: Update imports in `azure_vm_loadtest.py`**

In the `from workflow_tasks import (...)` block (keep `InstallK6` — still used by `_skeleton`), add `install_k6_task,`.

- [ ] **Step 4: Replace the bash install task in `run()`**

Replace the `InstallK6(task_id=s_install_k6.task_id, title=s_install_k6.title, runner=loadgen_runner, remote_dir=remote_home)` entry inside `Workflow(tasks=[...])` (line ~150) with:

```python
                install_k6_task(
                    task_id=s_install_k6.task_id,
                    title=s_install_k6.title,
                    repo_root=self.runner.paths.workspace_root,
                    shell=self.runner.shell,
                    host=azure_orch.connection_host(request.loadgen_vm),
                    user=request.loadgen_vm.user,
                    private_key=azure_orch.ssh_private_key_path(request.loadgen_vm),
                ),
```

- [ ] **Step 5: Run the guard test + the azure runner suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_azure_vm_loadtest_runner.py -v`
Expected: PASS (guard test passes; the existing runner tests use a mock orchestrator for `RunK6` and are unaffected by the install change).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py tools/controlplane/tests/test_azure_vm_loadtest_runner.py
git commit -m "refactor(azure-vm-loadtest): install k6 via ansible RunPlaybook"
```

---

## Task 7: Migrate `proxmox-vm-loadtest` loadgen install to ansible

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py:730-736`
- Test: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py:217-326`

Context: in `_tail_tasks`, the `_install_k6` callable currently runs the bash `InstallK6(...)` via `OrchestratorVmRunner`. Proxmox exposes public `ssh_endpoint(request) -> (host, port)` (publishes the SSH NAT rule if missing) and `ssh_private_key_path(request)`. The loadgen VM is created with the SSH public key via cloud-init, so it is reachable by host ansible. `self.runner` (E2eRunner) and `loadgen_request`/`proxmox_orch` are in scope inside `_tail_tasks`.

- [ ] **Step 1: Update the existing tail-events test (it monkeypatches `InstallK6`)**

In `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`, in `test_proxmox_vm_loadtest_tail_events_start_after_prelude`:

(a) Add the two new methods to `FakeProxmoxVmOrchestrator` (after `publish_port`):

```python
        def ssh_endpoint(self, request):
            return "127.0.0.1", 2222

        def ssh_private_key_path(self, request):
            return None
```

(b) Replace the line `monkeypatch.setattr(proxmox_plan, "InstallK6", FakeTask)` with:

```python
    monkeypatch.setattr(proxmox_plan, "install_k6_task", FakeTask)
```

(`FakeTask.__init__` accepts `task_id, title, **kwargs`, so it absorbs the factory's keyword args.)

- [ ] **Step 2: Add the guard test**

Add to the same test file:

```python
def test_proxmox_loadgen_install_uses_runplaybook_not_bash() -> None:
    import inspect

    from controlplane_tool.scenario.scenarios import proxmox_vm_loadtest

    source = inspect.getsource(proxmox_vm_loadtest.ProxmoxVmLoadtestPlan._tail_tasks)
    assert "install_k6_task(" in source
    assert "InstallK6(" not in source
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py -v -k "tail_events or runplaybook_not_bash"`
Expected: FAIL — `_tail_tasks` still contains `InstallK6(`; the tail-events test fails because `install_k6_task` is not yet a module name to patch.

- [ ] **Step 4: Update imports in `proxmox_vm_loadtest.py`**

In the `from workflow_tasks import (...)` block (keep `InstallK6` — still used by `_skeleton` at line ~167), add `install_k6_task,`.

- [ ] **Step 5: Replace the bash install in `_install_k6`**

Replace the `_install_k6` body (lines ~730-736) with:

```python
        def _install_k6() -> None:
            host, port = proxmox_orch.ssh_endpoint(loadgen_request)
            install_k6_task(
                task_id=s_install_k6.task_id,
                title=s_install_k6.title,
                repo_root=self.runner.paths.workspace_root,
                shell=self.runner.shell,
                host=host,
                user=loadgen_request.user,
                private_key=proxmox_orch.ssh_private_key_path(loadgen_request),
                port=port,
            ).run()
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py -v`
Expected: PASS (guard test, tail-events test, task_id/ordering tests — `loadgen.install_k6` still present).

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py
git commit -m "refactor(proxmox-vm-loadtest): install k6 via ansible RunPlaybook"
```

---

## Task 8: Full suites + deprecation note

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

## Out of scope (Phase 3, documented in the spec)

- **Recipe-fragment composition** (spec §4.4 Axis A): replace the flat `ScenarioRecipe` tuples with shared fragments (`PROVISION_PRELUDE`, `LOADGEN_SEQUENCE`) so near-identical scenarios compose instead of duplicate. With `install_k6_task` proving connectivity-as-parameters (Axis B), this collapses two-vm/azure/proxmox toward one shared definition.
- **helm / namespace / cleanup → ansible** (`helm` module) — large, low-value-for-now conversion.
- **Removing the deprecated bash `InstallK6`** classes once nothing references them.

---

## Self-Review

- **Spec coverage:** generic ansible exec via `AnsibleAdapter.run_playbook` → Task 1; `RunPlaybook` (§4.1) → Task 2; TUI integration (§4.2) → SubprocessShell live-log streaming (baseline; structured per-ansible-task sub-steps noted as future); connectivity adapter (§4.4 Axis B) → `install_k6_task` factory (Task 3); migration of all three loadtest scenarios (§4.3) → Tasks 4 (two-vm), 6 (azure), 7 (proxmox), with azure key boundary in Task 5; additive/bash kept + deprecation note (§3 goals) → Task 8; recipe-fragment composition + helm (§4.4 Axis A, Phase 3) → explicitly out of scope. Mapping (§2) lives in the committed spec.
- **Placeholder scan:** no TBD/TODO; all code blocks concrete. Conditional notes (`VmRequest.azure_ssh_key_path` field name) give the contract to satisfy.
- **Type consistency:** `RunPlaybook(task_id, title, adapter, playbook, request, extra_vars)` used identically across Tasks 2/3/4; `install_k6_task(*, task_id, title, repo_root, shell, host, user, private_key=None, port=None)` keyword-only signature identical in Task 3 tests and the Task 4/6/7 call sites; `AnsibleAdapter.run_playbook(playbook_name, request, *, extra_vars, dry_run)` matches `_build_command`'s signature; proxmox `ssh_endpoint(request) -> (host, port)` and `ssh_private_key_path(request)` are existing public methods; azure `connection_host(request)` existing + `ssh_private_key_path(request)` added in Task 5.
- **Cross-test integrity:** Task 7 updates the existing `test_proxmox_vm_loadtest_tail_events_start_after_prelude` (which monkeypatches the module-level install symbol and uses a fake orchestrator) to patch `install_k6_task` and to add `ssh_endpoint`/`ssh_private_key_path` to `FakeProxmoxVmOrchestrator` — otherwise the migrated `_install_k6` would call methods the fake lacks.
