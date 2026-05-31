# Kernel in libreria (sotto-progetto 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare il kernel (shell backend, data-model dei componenti, VmOrchestrator + AnsibleAdapter) da `controlplane_tool` a `workflow_tasks`, lasciando re-export shim in controlplane così che il comportamento resti identico.

**Architecture:** Migrazione bottom-up rispettosa delle dipendenze. Ogni modulo sale in `workflow_tasks`, il modulo originale in `controlplane_tool` diventa un re-export shim. `ToolPaths` NON si sposta: le classi infra di libreria calcolano `workspace_root = repo_root` e `ansible_root = repo_root/ops/ansible` per convenzione. Lo YAML Ansible resta in `ops/`. `azure_vm_adapter`/`proxmox_vm_adapter` sono già alias dei provider di libreria: nessun lavoro.

**Tech Stack:** Python 3.11+, pydantic, shellcraft, multipass-sdk, pytest, import-linter, uv.

**Comandi base:**
- Test libreria: `uv run --project tools/workflow-tasks pytest`
- Test controlplane: `uv run --project tools/controlplane pytest`
- import-linter libreria: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter`
- import-linter controlplane: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`

**Invariante per ogni task:** dopo ogni commit, ENTRAMBE le suite di test e ENTRAMBI i contratti import-linter restano verdi.

---

### Task 1: Shell backend → `workflow_tasks/shell.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/shell.py`
- Create: `tools/workflow-tasks/tests/test_shell.py`
- Modify: `tools/controlplane/src/controlplane_tool/core/shell_backend.py` (→ shim)

- [ ] **Step 1: Scrivi il modulo di libreria**

Create `tools/workflow-tasks/src/workflow_tasks/shell.py`:

```python
# Shell backend: shellcraft re-exports + workflow-aware SubprocessShell.
from __future__ import annotations

from shellcraft.backend import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell as _ShellcraftSubprocessShell,
)

from workflow_tasks import has_workflow_sink, workflow_log

__all__ = [
    "OutputListener",
    "RecordingShell",
    "ScriptedShell",
    "ShellBackend",
    "ShellExecutionResult",
    "SubprocessShell",
]


class SubprocessShell(_ShellcraftSubprocessShell):
    """SubprocessShell with TUI workflow-log integration.

    Routes each output line to workflow_log when a workflow sink is active,
    in addition to any explicitly set output_listener.
    """

    def _emit_output(self, stream: str, line: str) -> None:
        super()._emit_output(stream, line)
        if has_workflow_sink():
            workflow_log(line, stream=stream)
```

NOTA: verifica che `has_workflow_sink`/`workflow_log` siano esportati da
`workflow_tasks` (sono usati come `from workflow_tasks import has_workflow_sink, workflow_log`
nel codice attuale). Se l'import da `workflow_tasks.workflow.reporting` fallisce, usa
`from workflow_tasks import has_workflow_sink, workflow_log`.

- [ ] **Step 2: Scrivi il test di libreria**

Create `tools/workflow-tasks/tests/test_shell.py`:

```python
from __future__ import annotations

from unittest.mock import MagicMock, patch

from workflow_tasks.shell import (
    RecordingShell,
    ScriptedShell,
    ShellExecutionResult,
    SubprocessShell,
)


def test_shell_execution_result_captures_stdout_stderr() -> None:
    r = ShellExecutionResult(command=["cmd"], return_code=0, stdout="out", stderr="err")
    assert r.stdout == "out"
    assert r.stderr == "err"


def test_subprocess_shell_dry_run_returns_zero_without_executing() -> None:
    shell = SubprocessShell()
    result = shell.run(["rm", "-rf", "/"], dry_run=True)
    assert result.return_code == 0
    assert result.dry_run is True


def test_subprocess_shell_returns_ok_on_zero_exit() -> None:
    shell = SubprocessShell()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="hello", stderr="")
        result = shell.run(["echo", "hello"])
    assert result.return_code == 0
    assert result.stdout == "hello"


def test_recording_shell_records_commands() -> None:
    shell = RecordingShell()
    shell.run(["cmd1", "arg1"])
    shell.run(["cmd2"])
    assert shell.commands == [["cmd1", "arg1"], ["cmd2"]]


def test_scripted_shell_returns_configured_return_code() -> None:
    shell = ScriptedShell(return_code_map={("fail",): 1})
    result = shell.run(["fail"])
    assert result.return_code == 1


def test_subprocess_shell_routes_output_to_workflow_log_when_sink_active() -> None:
    captured: list[tuple[str, str]] = []
    from workflow_tasks import bind_workflow_sink

    bind_workflow_sink(lambda line, stream="stdout": captured.append((stream, line)))
    try:
        shell = SubprocessShell()
        shell._emit_output("stdout", "hello-line")
    finally:
        bind_workflow_sink(None)
    assert ("stdout", "hello-line") in captured
```

NOTA: adatta l'ultimo test all'API reale di `bind_workflow_sink` — verifica firma e modo
di disattivare il sink (`bind_workflow_sink(None)` o context manager). Se l'API differisce,
correggi il test, NON la libreria.

- [ ] **Step 3: Esegui i test di libreria — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/test_shell.py -v`
Expected: PASS (5 test). Se l'ultimo fallisce per API sink diversa, aggiusta solo il test.

- [ ] **Step 4: Converti il modulo controlplane in shim**

Replace `tools/controlplane/src/controlplane_tool/core/shell_backend.py` con:

```python
# Shim: re-exports from workflow_tasks.shell (migrato in sotto-progetto 1).
from __future__ import annotations

from workflow_tasks.shell import (
    OutputListener,
    RecordingShell,
    ScriptedShell,
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)

__all__ = [
    "OutputListener",
    "RecordingShell",
    "ScriptedShell",
    "ShellBackend",
    "ShellExecutionResult",
    "SubprocessShell",
]
```

- [ ] **Step 5: Esegui i test controlplane dello shell — devono passare invariati**

Run: `uv run --project tools/controlplane pytest tests/test_shell_backend.py -v`
Expected: PASS (tutti i test esistenti passano tramite lo shim).

- [ ] **Step 6: Verifica import-linter**

Run: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter`
Run: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`
Expected: entrambi "Contracts: N kept, 0 broken."

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/shell.py tools/workflow-tasks/tests/test_shell.py tools/controlplane/src/controlplane_tool/core/shell_backend.py
git commit -m "refactor(workflow-tasks): move shell backend into library, leave shim in controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Component operations → `workflow_tasks/components/operations.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/components/operations.py`
- Create: `tools/workflow-tasks/tests/components/__init__.py`
- Create: `tools/workflow-tasks/tests/components/test_operations.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/operations.py` (→ shim)

- [ ] **Step 1: Crea il package e il modulo di libreria**

Create `tools/workflow-tasks/src/workflow_tasks/components/__init__.py` (vuoto).

Create `tools/workflow-tasks/src/workflow_tasks/components/operations.py` (copia esatta del puro data-model):

```python
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from types import MappingProxyType


def _empty_env() -> Mapping[str, str]:
    return MappingProxyType({})


@dataclass(frozen=True, slots=True)
class ScenarioOperation:
    operation_id: str
    summary: str


@dataclass(frozen=True, slots=True)
class RemoteCommandOperation(ScenarioOperation):
    argv: tuple[str, ...]
    env: Mapping[str, str] = field(default_factory=_empty_env)
    # "vm" means the command must run inside the VM (e.g. docker, helm, kubectl);
    # "host" means it runs on the local machine (e.g. ansible-playbook, multipass).
    execution_target: str = "host"
```

- [ ] **Step 2: Scrivi il test di libreria**

Create `tools/workflow-tasks/tests/components/__init__.py` (vuoto).
Create `tools/workflow-tasks/tests/components/test_operations.py`:

```python
from __future__ import annotations

from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation


def test_scenario_operation_holds_id_and_summary() -> None:
    op = ScenarioOperation(operation_id="op1", summary="do thing")
    assert op.operation_id == "op1"
    assert op.summary == "do thing"


def test_remote_command_operation_defaults_to_host_target_and_empty_env() -> None:
    op = RemoteCommandOperation(operation_id="op2", summary="run", argv=("echo", "hi"))
    assert op.execution_target == "host"
    assert dict(op.env) == {}
    assert op.argv == ("echo", "hi")


def test_remote_command_operation_is_frozen() -> None:
    op = RemoteCommandOperation(operation_id="op3", summary="run", argv=("ls",))
    try:
        op.summary = "x"  # type: ignore[misc]
    except AttributeError:
        return
    raise AssertionError("expected frozen dataclass")
```

- [ ] **Step 3: Esegui i test di libreria — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/components/test_operations.py -v`
Expected: PASS (3 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Replace `tools/controlplane/src/controlplane_tool/scenario/components/operations.py` con:

```python
# Shim: re-exports from workflow_tasks.components.operations (migrato in sotto-progetto 1).
from __future__ import annotations

from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation

__all__ = ["RemoteCommandOperation", "ScenarioOperation"]
```

- [ ] **Step 5: Esegui i test controlplane correlati — devono passare**

Run: `uv run --project tools/controlplane pytest tests/test_scenario_component_models.py tests/test_component_registry.py -v`
Expected: PASS.

- [ ] **Step 6: Verifica import-linter (entrambi)**

Run i due comandi `lint-imports` come in Task 1 Step 6.
Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/ tools/workflow-tasks/tests/components/ tools/controlplane/src/controlplane_tool/scenario/components/operations.py
git commit -m "refactor(workflow-tasks): move component operations data-model into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Component models → `workflow_tasks/components/models.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/models.py`
- Create: `tools/workflow-tasks/tests/components/test_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/models.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria**

Create `tools/workflow-tasks/src/workflow_tasks/components/models.py`:

```python
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

from workflow_tasks.components.operations import ScenarioOperation


def _planner_not_implemented(_: object) -> tuple[ScenarioOperation, ...]:
    raise NotImplementedError("ScenarioComponentDefinition.planner is not implemented")


@dataclass(frozen=True, slots=True)
class ScenarioComponentDefinition:
    component_id: str
    summary: str
    planner: Callable[..., tuple[ScenarioOperation, ...]] = field(
        default=_planner_not_implemented,
        repr=False,
        compare=False,
    )


@dataclass(frozen=True, slots=True)
class ScenarioRecipe:
    name: str
    component_ids: tuple[str, ...]
    requires_managed_vm: bool = True
```

- [ ] **Step 2: Scrivi il test di libreria**

Create `tools/workflow-tasks/tests/components/test_models.py`:

```python
from __future__ import annotations

import pytest

from workflow_tasks.components.models import ScenarioComponentDefinition, ScenarioRecipe


def test_recipe_defaults_to_requires_managed_vm_true() -> None:
    recipe = ScenarioRecipe(name="r", component_ids=("a", "b"))
    assert recipe.requires_managed_vm is True
    assert recipe.component_ids == ("a", "b")


def test_component_definition_default_planner_raises() -> None:
    comp = ScenarioComponentDefinition(component_id="c", summary="s")
    with pytest.raises(NotImplementedError):
        comp.planner(object())


def test_component_definition_accepts_custom_planner() -> None:
    def planner(_: object) -> tuple:
        return ()

    comp = ScenarioComponentDefinition(component_id="c", summary="s", planner=planner)
    assert comp.planner(object()) == ()
```

- [ ] **Step 3: Esegui i test di libreria — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/components/test_models.py -v`
Expected: PASS (3 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Replace `tools/controlplane/src/controlplane_tool/scenario/components/models.py` con:

```python
# Shim: re-exports from workflow_tasks.components.models (migrato in sotto-progetto 1).
from __future__ import annotations

from workflow_tasks.components.models import ScenarioComponentDefinition, ScenarioRecipe

__all__ = ["ScenarioComponentDefinition", "ScenarioRecipe"]
```

- [ ] **Step 5: Esegui i test controlplane correlati — devono passare**

Run: `uv run --project tools/controlplane pytest tests/test_scenario_component_models.py tests/test_component_registry.py tests/test_scenario_builders.py -v`
Expected: PASS.

- [ ] **Step 6: Verifica import-linter (entrambi)**

Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/models.py tools/workflow-tasks/tests/components/test_models.py tools/controlplane/src/controlplane_tool/scenario/components/models.py
git commit -m "refactor(workflow-tasks): move component definition/recipe models into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: AnsibleAdapter → `workflow_tasks/infra/ansible.py` (scorpora ToolPaths)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/infra/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Create: `tools/workflow-tasks/tests/infra/__init__.py`
- Create: `tools/workflow-tasks/tests/infra/test_ansible.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/ansible_adapter.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (senza ToolPaths)**

Create `tools/workflow-tasks/src/workflow_tasks/infra/__init__.py` (vuoto).

Create `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`. È il contenuto di
`controlplane_tool/infra/vm/ansible_adapter.py` con DUE sole modifiche:
(a) niente import di `ToolPaths`/`controlplane_tool`;
(b) `self.paths.ansible_root` → `self.ansible_root` e `self.paths.workspace_root` → `self.workspace_root`,
calcolati da `repo_root`.

```python
from __future__ import annotations

from pathlib import Path
from typing import Protocol

from multipass import MultipassClient

from workflow_tasks.shell import ShellBackend, ShellExecutionResult, SubprocessShell
from workflow_tasks.vm.models import VmRequest


class HostResolver(Protocol):
    def __call__(self, request: VmRequest, *, dry_run: bool = False) -> str: ...


class AnsibleAdapter:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        host_resolver: HostResolver | None = None,
        private_key_path: Path | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.workspace_root = self.repo_root
        self.ansible_root = self.repo_root / "ops" / "ansible"
        self.shell = shell or SubprocessShell()
        if host_resolver is None:
            from workflow_tasks.vm.multipass import resolve_connection_host

            client = multipass_client or MultipassClient()
            host_resolver = lambda request, dry_run=False: resolve_connection_host(
                request,
                client,
                dry_run=dry_run,
            )
        self.host_resolver = host_resolver
        self.private_key_path = private_key_path

    def _inventory_target(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return f"{self.host_resolver(request, dry_run=dry_run)},"

    def _build_command(
        self,
        playbook_name: str,
        request: VmRequest,
        *,
        extra_vars: dict[str, str] | None = None,
        dry_run: bool = False,
    ) -> tuple[list[str], dict[str, str]]:
        playbook = self.ansible_root / "playbooks" / playbook_name
        command = [
            "ansible-playbook",
            "-i",
            self._inventory_target(request, dry_run=dry_run),
            "-u",
            request.user,
        ]
        if self.private_key_path is not None:
            command.extend(["--private-key", str(self.private_key_path)])
        for key, value in (extra_vars or {}).items():
            command.extend(["-e", f"{key}={value}"])
        command.append(str(playbook))
        env = {"ANSIBLE_CONFIG": str(self.ansible_root / "ansible.cfg")}
        return command, env

    def _registry_extra_vars(
        self,
        *,
        registry: str,
        container_name: str | None = None,
    ) -> dict[str, str]:
        registry_host, registry_port = registry.rsplit(":", 1)
        extra_vars = {
            "registry": registry,
            "registry_host": registry_host,
            "registry_port": registry_port,
        }
        if container_name is not None:
            extra_vars["registry_container_name"] = container_name
        return extra_vars

    def provision_base(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            "provision-base.yml",
            request,
            extra_vars={
                "install_helm": str(install_helm).lower(),
                "helm_version": helm_version.removeprefix("v"),
                "vm_user": request.user,
            },
            dry_run=dry_run,
        )
        return self.shell.run(command, cwd=self.workspace_root, env=env, dry_run=dry_run)

    def provision_k3s(
        self,
        request: VmRequest,
        *,
        kubeconfig_path: str,
        k3s_version: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        extra_vars = {
            "vm_user": request.user,
            "kubeconfig_path": kubeconfig_path,
        }
        if k3s_version:
            extra_vars["k3s_version_override"] = k3s_version
        command, env = self._build_command(
            "provision-k3s.yml",
            request,
            extra_vars=extra_vars,
            dry_run=dry_run,
        )
        return self.shell.run(command, cwd=self.workspace_root, env=env, dry_run=dry_run)

    def ensure_registry_container(
        self,
        request: VmRequest,
        *,
        registry: str,
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            "ensure-registry.yml",
            request,
            extra_vars=self._registry_extra_vars(
                registry=registry,
                container_name=container_name,
            ),
            dry_run=dry_run,
        )
        return self.shell.run(command, cwd=self.workspace_root, env=env, dry_run=dry_run)

    def configure_k3s_registry(
        self,
        request: VmRequest,
        *,
        registry: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command, env = self._build_command(
            "configure-k3s-registry.yml",
            request,
            extra_vars=self._registry_extra_vars(registry=registry),
            dry_run=dry_run,
        )
        return self.shell.run(command, cwd=self.workspace_root, env=env, dry_run=dry_run)

    def configure_registry(
        self,
        request: VmRequest,
        *,
        registry: str,
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        ensure_result = self.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )
        if ensure_result.return_code != 0:
            return ensure_result
        return self.configure_k3s_registry(request, registry=registry, dry_run=dry_run)
```

- [ ] **Step 2: Scrivi il test di libreria**

Create `tools/workflow-tasks/tests/infra/__init__.py` (vuoto).
Create `tools/workflow-tasks/tests/infra/test_ansible.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.infra.ansible import AnsibleAdapter
from workflow_tasks.shell import RecordingShell
from workflow_tasks.vm.models import VmRequest


def test_provision_base_uses_ops_ansible_root() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.provision_base(request, dry_run=True)

    command = shell.commands[0]
    assert "ansible-playbook" in command
    assert "ops/ansible/playbooks/provision-base.yml" in " ".join(command)
    assert "vm.example.test," in command


def test_configure_k3s_registry_sets_expected_extra_vars() -> None:
    shell = RecordingShell()
    adapter = AnsibleAdapter(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    adapter.configure_k3s_registry(request, registry="registry.example.test:5000", dry_run=True)

    rendered = " ".join(shell.commands[0])
    assert "configure-k3s-registry.yml" in rendered
    assert "registry=registry.example.test:5000" in rendered
    assert "registry_port=5000" in rendered
```

NOTA: verifica la firma di `VmRequest` (campi `lifecycle`/`host`/`user`) — è importata da
`workflow_tasks.vm.models`. Se i nomi differiscono, allinea il test al modello reale.

- [ ] **Step 3: Esegui i test di libreria — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/infra/test_ansible.py -v`
Expected: PASS (2 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Replace `tools/controlplane/src/controlplane_tool/infra/vm/ansible_adapter.py` con:

```python
# Shim: re-exports from workflow_tasks.infra.ansible (migrato in sotto-progetto 1).
from __future__ import annotations

from workflow_tasks.infra.ansible import AnsibleAdapter, HostResolver

__all__ = ["AnsibleAdapter", "HostResolver"]
```

- [ ] **Step 5: Esegui i test controlplane dell'AnsibleAdapter — devono passare invariati**

Run: `uv run --project tools/controlplane pytest tests/test_ansible_adapter.py -v`
Expected: PASS (tutti i test esistenti passano tramite lo shim, compresi quelli Multipass).

- [ ] **Step 6: Verifica import-linter (entrambi)**

Expected: 0 broken. In particolare il contratto `no_external_deps` della libreria conferma
che `workflow_tasks.infra.ansible` non importa `controlplane_tool`.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/infra/ tools/workflow-tasks/tests/infra/ tools/controlplane/src/controlplane_tool/infra/vm/ansible_adapter.py
git commit -m "refactor(workflow-tasks): move AnsibleAdapter into library, drop ToolPaths coupling

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: VmOrchestrator → `workflow_tasks/vm/orchestrator.py` (scorpora ToolPaths)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/orchestrator.py`
- Create: `tools/workflow-tasks/tests/vm/test_orchestrator.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (senza ToolPaths)**

Create `tools/workflow-tasks/src/workflow_tasks/vm/orchestrator.py`. È il contenuto di
`controlplane_tool/infra/vm/vm_adapter.py` con queste modifiche:
(a) import `AnsibleAdapter` da `workflow_tasks.infra.ansible`;
(b) import `ShellExecutionResult` da `workflow_tasks.shell` (era implicito);
(c) rimuovi `ToolPaths`; `self.paths.workspace_root` → `self.repo_root`.

```python
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from multipass import MultipassCommandError

from workflow_tasks.vm.multipass import (
    MultipassVmProvider,
    _ok,
    _sdk_error,
    repo_rsync_command,
    repo_sync_ssh_rsh,
)
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.shell import ShellExecutionResult

if TYPE_CHECKING:
    from shellcraft.backend import ShellBackend
    from multipass import MultipassClient
    from workflow_tasks.infra.ansible import AnsibleAdapter

__all__ = ["VmOrchestrator", "repo_rsync_command"]


class VmOrchestrator(MultipassVmProvider):
    def __init__(
        self,
        repo_root: Path,
        shell: "ShellBackend | None" = None,
        ansible: "AnsibleAdapter | None" = None,
        multipass_client: "MultipassClient | None" = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        super().__init__(
            workspace_root=self.repo_root,
            shell=shell,
            multipass_client=multipass_client,
        )
        if ansible is None:
            from workflow_tasks.infra.ansible import AnsibleAdapter

            ansible = AnsibleAdapter(
                self.repo_root,
                shell=self.shell,
                host_resolver=self.connection_host,
                private_key_path=self._private_key_path,
            )
        self.ansible = ansible

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/nanofaas"

    def kubeconfig_path(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/.kube/config"

    def remote_path_for_local(
        self,
        request: VmRequest,
        local_path: Path,
        *,
        local_root: Path | None = None,
        fallback_subdir: str | None = None,
    ) -> str:
        path = Path(local_path).resolve()
        root = Path(local_root or self.repo_root).resolve()
        remote_dir = self.remote_project_dir(request)

        try:
            relative = path.relative_to(root)
            return f"{remote_dir}/{relative.as_posix()}"
        except ValueError:
            if fallback_subdir:
                fallback = fallback_subdir.strip("/")
                return f"{remote_dir}/{fallback}/{path.name}"
            return f"{remote_dir}/{path.name}"

    def sync_project(
        self,
        request: VmRequest,
        *,
        source_dir: Path | None = None,
        remote_dir: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        source = Path(source_dir or self.repo_root)
        destination = remote_dir or self.remote_project_dir(request)

        if request.lifecycle == "external":
            return self._shell_run(
                repo_rsync_command(
                    source=source,
                    user=request.user,
                    host=str(request.host),
                    destination=destination,
                ),
                dry_run=dry_run,
            )

        host = self.connection_host(request, dry_run=dry_run)
        return self._shell_run(
            repo_rsync_command(
                source=source,
                user=request.user,
                host=host,
                destination=destination,
                ssh_rsh=repo_sync_ssh_rsh(self._private_key_path),
            ),
            dry_run=dry_run,
        )

    def install_dependencies(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_base(
            request,
            install_helm=install_helm,
            helm_version=helm_version,
            dry_run=dry_run,
        )

    def install_k3s(
        self,
        request: VmRequest,
        *,
        kubeconfig_path: str | None = None,
        k3s_version: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_k3s(
            request,
            kubeconfig_path=kubeconfig_path or self.kubeconfig_path(request),
            k3s_version=k3s_version,
            dry_run=dry_run,
        )

    def setup_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        ensure_result = self.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )
        if ensure_result.return_code != 0:
            return ensure_result
        return self.configure_k3s_registry(request, registry=registry, dry_run=dry_run)

    def ensure_registry_container(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )

    def configure_k3s_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.configure_k3s_registry(request, registry=registry, dry_run=dry_run)

    def export_kubeconfig(
        self,
        request: VmRequest,
        *,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        kubeconfig_path = self.kubeconfig_path(request)
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", f"{request.user}@{request.host}:{kubeconfig_path}", str(destination)],
                dry_run=dry_run,
            )

        name = self._vm_name(request)
        transfer_cmd = ["multipass", "transfer", f"{name}:{kubeconfig_path}", str(destination)]
        if dry_run:
            return _ok(transfer_cmd)

        try:
            self._client.get_vm(name).transfer(f"{name}:{kubeconfig_path}", str(destination))
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(transfer_cmd)
```

- [ ] **Step 2: Scrivi il test di libreria**

Create `tools/workflow-tasks/tests/vm/test_orchestrator.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.orchestrator import VmOrchestrator


def test_remote_project_dir_uses_nanofaas_suffix() -> None:
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    assert orch.remote_project_dir(request).endswith("/nanofaas")


def test_install_dependencies_delegates_to_ansible_provision_base() -> None:
    shell = RecordingShell()
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")

    orch.install_dependencies(request, dry_run=True)

    rendered = " ".join(shell.commands[-1])
    assert "ops/ansible/playbooks/provision-base.yml" in rendered


def test_remote_path_for_local_uses_repo_root_as_default_root() -> None:
    orch = VmOrchestrator(repo_root=Path("/repo"), shell=RecordingShell())
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    remote = orch.remote_path_for_local(request, Path("/repo/control-plane/app.jar"))
    assert remote.endswith("/nanofaas/control-plane/app.jar")
```

NOTA: allinea i campi di `VmRequest` e i metodi `_remote_home`/`connection_host` al modello
reale; se un metodo richiede uno stato Multipass per `external` lifecycle, usa `external`
come in `test_ansible_adapter.py` (non richiede client reale per `dry_run=True`).

- [ ] **Step 3: Esegui i test di libreria — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/vm/test_orchestrator.py -v`
Expected: PASS (3 test). Correggi solo il test se l'API del provider differisce.

- [ ] **Step 4: Converti il modulo controlplane in shim**

Replace `tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py` con:

```python
# Shim: re-exports from workflow_tasks.vm.orchestrator (migrato in sotto-progetto 1).
from __future__ import annotations

from workflow_tasks.vm.orchestrator import VmOrchestrator, repo_rsync_command

__all__ = ["VmOrchestrator", "repo_rsync_command"]
```

- [ ] **Step 5: Esegui i test controlplane del VmOrchestrator — devono passare invariati**

Run: `uv run --project tools/controlplane pytest tests/test_vm_adapter.py tests/test_vm_tasks.py tests/tasks/test_vm_tasks.py -v`
Expected: PASS.

- [ ] **Step 6: Verifica import-linter (entrambi)**

Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/vm/orchestrator.py tools/workflow-tasks/tests/vm/test_orchestrator.py tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py
git commit -m "refactor(workflow-tasks): move VmOrchestrator into library, drop ToolPaths coupling

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Confini di package — test e contratto import-linter per i nuovi subpackage

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi i test di confine per i nuovi subpackage**

Append in `tools/workflow-tasks/tests/test_package_boundaries.py`:

```python
def test_components_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.operations")
    importlib.import_module("workflow_tasks.components.models")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_infra_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.infra.ansible")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_vm_orchestrator_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.orchestrator")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui i test di confine — devono passare**

Run: `uv run --project tools/workflow-tasks pytest tests/test_package_boundaries.py -v`
Expected: PASS (test esistenti + 3 nuovi).

- [ ] **Step 3: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert kernel subpackages stay independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Verifica finale completa

- [ ] **Step 1: Suite completa libreria**

Run: `uv run --project tools/workflow-tasks pytest`
Expected: PASS, coverage ≥ 90% (gate `--cov-fail-under=90` in pyproject).

- [ ] **Step 2: Suite completa controlplane**

Run: `uv run --project tools/controlplane pytest`
Expected: PASS (tutti i test passano tramite gli shim, comportamento invariato).

- [ ] **Step 3: import-linter su entrambi i progetti**

Run i due comandi `lint-imports`.
Expected: entrambi 0 broken.

- [ ] **Step 4: GitNexus detect_changes (come da CLAUDE.md)**

Verifica che le modifiche tocchino solo i simboli attesi (shell backend, operations, models,
AnsibleAdapter, VmOrchestrator e i loro shim). Nessun simbolo inatteso.

- [ ] **Step 5: Aggiorna l'indice GitNexus dopo i commit**

Run: `npx gitnexus analyze` (con `--embeddings` se `.gitnexus/meta.json` mostra embeddings > 0).
NOTA: un hook PostToolUse potrebbe già farlo automaticamente dopo i commit.

---

## Note di esecuzione

- Gli **shim** creati qui (`shell_backend`, `operations`, `models`, `ansible_adapter`,
  `vm_adapter`) sono **temporanei**: verranno rimossi nel sotto-progetto 4 (pulizia
  controlplane), quando tutti i consumatori importeranno direttamente da `workflow_tasks`.
- `azure_vm_adapter`/`proxmox_vm_adapter` sono **già** alias dei provider di libreria: non
  toccarli in questo sotto-progetto.
- `ToolPaths` (`controlplane_tool/workspace/paths.py`) **resta** in controlplane: serve ancora
  per `profiles_dir`/`runs_dir`/`scenarios_dir`. La libreria calcola i propri path da
  `repo_root` per convenzione.
- Prima di spostare ogni simbolo, esegui `gitnexus_impact({target, direction: "upstream"})`
  come richiesto da CLAUDE.md e riporta il blast radius.
