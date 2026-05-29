# Sotto-progetto 2b.4 — `bootstrap` component → library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare il componente `bootstrap` in `workflow_tasks/components/bootstrap.py`, eliminando l'unico accoppiamento residuo a controlplane (`ToolPaths`), sostituito dalla convenzione `repo_root/ops/ansible`.

**Architecture:** Move verbatim con due cambi: (1) blocco import rediretto al kernel di libreria; (2) le 3 occorrenze di `ToolPaths` sostituite dalla convenzione `ansible_root = context.repo_root / "ops" / "ansible"` (identico a come AnsibleAdapter/VmOrchestrator l'hanno fatto nel sotto-progetto 1). Re-export shim a controlplane che preserva OGNI nome pubblico (planner + component-def constants), usati da `vm_cluster_workflows`, `two_vm_loadtest`, `composer`.

**Tech Stack:** Python 3.11+, pytest, import-linter, uv.

**Comandi:** libreria singolo file con `--no-cov`; controlplane senza `--no-cov`. import-linter come nei piani precedenti.

**Baseline pre-esistente (NON nostro):** libreria 1 fail (proxmox); controlplane 3 fail (2× e2e_runner, 1× tui_choices). Nessun task li aumenta.

**Fatti verificati:**
- `bootstrap.py` import: `multipass.find_ssh_public_key`; `workflow_tasks.vm.multipass` (`_find_ssh_private_key_path`, `repo_rsync_command`, `repo_sync_ssh_rsh`) → già libreria; `controlplane_tool.workspace.paths.ToolPaths` → DA ELIMINARE; `...components.environment` (context), `...components.models`, `...components.operations`, `...infra.vm.vm_models` (VmRequest) → tutti già libreria via kernel/shim.
- ToolPaths usato SOLO in 3 punti: `paths = ToolPaths.repo_root(context.repo_root)` e due `paths.ansible_root` (playbook dir + ansible.cfg). `ansible_root == repo_root/ops/ansible`.
- Importatori: `infra/vm/vm_cluster_workflows.py` (`import bootstrap as bootstrap_components`, accesso a `bootstrap_components.<planner>`), `components/two_vm_loadtest.py` (`plan_loadtest_install_k6`, `plan_vm_ensure_running`, `plan_vm_provision_base`), `components/composer.py` (i component-def constants, inclusi `K3S_INSTALL`, `K3S_CONFIGURE_REGISTRY`, `LOADTEST_INSTALL_K6`).

---

### Task 1: `bootstrap` → `workflow_tasks/components/bootstrap.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/bootstrap.py`
- Create: `tools/workflow-tasks/tests/components/test_bootstrap.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/bootstrap.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim + ToolPaths→convenzione)**

Leggi `tools/controlplane/src/controlplane_tool/scenario/components/bootstrap.py`. Crea
`tools/workflow-tasks/src/workflow_tasks/components/bootstrap.py` copiandolo, con SOLO questi cambi:

(a) Import block. Da:
```python
from multipass import find_ssh_public_key

from workflow_tasks.vm.multipass import _find_ssh_private_key_path, repo_rsync_command, repo_sync_ssh_rsh

from controlplane_tool.workspace.paths import ToolPaths
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.infra.vm.vm_models import VmRequest
```
A:
```python
from multipass import find_ssh_public_key

from workflow_tasks.vm.multipass import _find_ssh_private_key_path, repo_rsync_command, repo_sync_ssh_rsh
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
```
(NB: rimosso `ToolPaths`; `VmRequest` ora da `workflow_tasks.vm.models`.)

(b) Nel corpo di `_ansible_operation` (intorno a riga 53), sostituisci:
```python
    paths = ToolPaths.repo_root(context.repo_root)
```
con:
```python
    # Repo layout convention: Ansible assets live at <repo_root>/ops/ansible/
    ansible_root = context.repo_root / "ops" / "ansible"
```
e le due occorrenze `paths.ansible_root` (playbook path + `ANSIBLE_CONFIG`) con `ansible_root`.
Verifica leggendo: se `context.repo_root` è una `Path`, `/ "ops" / "ansible"` funziona; se fosse
`str`, avvolgilo con `Path(...)`. (Il context neutro definisce `repo_root: Path`, quindi è una Path.)

Tutto il resto del file (helper `_remote_home`, `_remote_project_dir`, `_kubeconfig_path`,
`_inventory_target`, `_frozen_env`; planner `plan_vm_ensure_running`, `plan_vm_provision_base`,
`plan_repo_sync_to_vm`, `plan_registry_ensure_container`, `plan_k3s_install`,
`plan_k3s_configure_registry`, `plan_loadtest_install_k6`; e TUTTE le costanti component-def)
resta IDENTICO.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_bootstrap.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import bootstrap as bs
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, lifecycle: str = "external", host: str | None = "vm.test") -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle=lifecycle, name="nanofaas-e2e", user="ubuntu", host=host),
        cleanup_vm=True,
    )


def test_provision_base_uses_ops_ansible_playbook_path() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    rendered = " ".join(ops[0].argv)
    assert "ops/ansible/playbooks/" in rendered
    assert "provision-base" in rendered


def test_provision_base_sets_ansible_config_env() -> None:
    ops = bs.plan_vm_provision_base(_ctx())
    # ANSIBLE_CONFIG must point at <repo>/ops/ansible/ansible.cfg
    env = dict(ops[0].env)
    assert any("ops/ansible/ansible.cfg" in v for v in env.values())


def test_k3s_install_planner_runs() -> None:
    ops = bs.plan_k3s_install(_ctx())
    assert len(ops) >= 1


def test_component_definitions_present() -> None:
    assert bs.VM_ENSURE_RUNNING.component_id == "vm.ensure_running"
    assert bs.VM_PROVISION_BASE.component_id == "vm.provision_base"
```

ADATTA al sorgente reale: verifica i `component_id` esatti, i nomi dei planner, e come
`plan_vm_provision_base` costruisce l'operazione ansible (nome playbook, dove finisce
`ANSIBLE_CONFIG` — in `env` o in argv). Se `_ansible_operation` richiede campi VmRequest
specifici (es. `host` per `external`, o `_inventory_target`), passali nel `_ctx`. Mantieni almeno:
un test che prova il path `ops/ansible/playbooks/...`, uno che prova `ANSIBLE_CONFIG`, e i component_id.

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_bootstrap.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane (re-export di TUTTI i nomi pubblici)**

Prima ENUMERA i nomi pubblici dal sorgente: tutti i `plan_*` e tutte le costanti
`*_* = ScenarioComponentDefinition(...)` (almeno: `VM_ENSURE_RUNNING`, `VM_PROVISION_BASE`,
`REPO_SYNC_TO_VM`, `REGISTRY_ENSURE_CONTAINER`, `K3S_INSTALL`, `K3S_CONFIGURE_REGISTRY`,
`LOADTEST_INSTALL_K6` — conferma con `grep -nE "^[A-Z_]+ = ScenarioComponentDefinition" sul sorgente`).
Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/bootstrap.py` con uno shim
che li ri-esporta TUTTI:

```python
# Shim: re-exports from workflow_tasks.components.bootstrap (migrated in sub-project 2b.4).
from __future__ import annotations

from workflow_tasks.components.bootstrap import (
    K3S_CONFIGURE_REGISTRY,
    K3S_INSTALL,
    LOADTEST_INSTALL_K6,
    REGISTRY_ENSURE_CONTAINER,
    REPO_SYNC_TO_VM,
    VM_ENSURE_RUNNING,
    VM_PROVISION_BASE,
    plan_k3s_configure_registry,
    plan_k3s_install,
    plan_loadtest_install_k6,
    plan_registry_ensure_container,
    plan_repo_sync_to_vm,
    plan_vm_ensure_running,
    plan_vm_provision_base,
)

__all__ = [
    "K3S_CONFIGURE_REGISTRY",
    "K3S_INSTALL",
    "LOADTEST_INSTALL_K6",
    "REGISTRY_ENSURE_CONTAINER",
    "REPO_SYNC_TO_VM",
    "VM_ENSURE_RUNNING",
    "VM_PROVISION_BASE",
    "plan_k3s_configure_registry",
    "plan_k3s_install",
    "plan_loadtest_install_k6",
    "plan_registry_ensure_container",
    "plan_repo_sync_to_vm",
    "plan_vm_ensure_running",
    "plan_vm_provision_base",
]
```
Allinea ESATTAMENTE l'elenco ai nomi reali del sorgente (aggiungi/rimuovi se differiscono). NB:
`vm_cluster_workflows.py` fa `import bootstrap as bootstrap_components` e poi
`bootstrap_components.<planner>` — il re-export rende i nomi attributi del modulo shim, quindi funziona.

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_recipe_execution_hooks.py`
Expected: nessun nuovo fallimento. Se ImportError su un nome bootstrap, aggiungilo allo shim.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/bootstrap.py tools/workflow-tasks/tests/components/test_bootstrap.py tools/controlplane/src/controlplane_tool/scenario/components/bootstrap.py
git commit -m "refactor(workflow-tasks): move bootstrap component into library, drop ToolPaths coupling

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Boundary test + verifica finale

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi asserzione di confine**

Append (stesso pattern inline):

```python
def test_components_bootstrap_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.bootstrap")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -v --no-cov` → PASS.

- [ ] **Step 3: Verifica finale**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4` → coverage ≥90%, solo fail proxmox.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4` → solo i 3 baseline.
Run i due `lint-imports` → 0 broken.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert bootstrap component stays independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- Dopo 2b.4, `bootstrap` + `cleanup` sono in libreria → `two_vm_loadtest` è quasi sbloccato (resta la dipendenza dalle funzioni catalog-coupled di `two_vm_loadtest_config`: `two_vm_control_plane_url`, `two_vm_load_stages`, ecc., da valutare nel passo successivo).
- `ToolPaths` resta in controlplane (usato altrove); la libreria calcola `ops/ansible` per convenzione.
- Shim temporanei (rimossi nel sotto-progetto 4).
- Prima di spostare i simboli, esegui `gitnexus_impact` come da CLAUDE.md.
