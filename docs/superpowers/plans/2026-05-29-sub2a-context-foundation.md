# Sotto-progetto 2a — Fondamenta: context neutro + registry (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Posare le fondamenta del sotto-progetto 2 spostando il framework `ComponentRegistry` in libreria e definendo un `ScenarioExecutionContext` **neutro** (senza i tipi di request di prodotto) in `workflow_tasks`, così che i componenti concreti possano salire in 2b.

**Architecture:** `registry.py` (dipende solo dal kernel `models`) sale in libreria con shim. Il context neutro vive in `workflow_tasks/components/context.py` con due `Protocol` strutturali per `resolved_scenario`; il campo vestigiale `request` viene **cancellato**. La factory `resolve_scenario_environment` e i request di prodotto restano in controlplane; `environment.py` re-esporta il context neutro così i consumatori esistenti non cambiano.

**Tech Stack:** Python 3.11+, dataclasses, typing.Protocol, pytest, import-linter, uv.

**Comandi base:**
- Test libreria: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests`
- Test controlplane: `uv run --project tools/controlplane pytest tools/controlplane/tests`
- import-linter libreria: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter`
- import-linter controlplane: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`

**Invariante per ogni task:** dopo ogni commit, le due suite e i due import-linter restano verdi. Eccezione nota: esistono fallimenti **pre-esistenti** dal commit WIP `4c58d3ac` (1 test proxmox in libreria; alcuni test e2e/tui in controlplane). Non sono causati da questo lavoro; vanno solo confermati come invariati (non aumentati).

---

### Task 1: `ComponentRegistry` → `workflow_tasks/components/registry.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/registry.py`
- Create: `tools/workflow-tasks/tests/components/test_registry.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/registry.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria**

Crea `tools/workflow-tasks/src/workflow_tasks/components/registry.py` (copia verbatim
dell'originale, con l'import di `ScenarioComponentDefinition` ridiretto al kernel di libreria
— già migrato nel sotto-progetto 1):

```python
"""
registry.py — Explicit component registry for ScenarioComponentDefinition.
"""
from __future__ import annotations

from workflow_tasks.components.models import ScenarioComponentDefinition


class ComponentRegistry:
    """Holds all known ScenarioComponentDefinitions, keyed by component_id."""

    def __init__(self) -> None:
        self._store: dict[str, ScenarioComponentDefinition] = {}

    def register(self, component: ScenarioComponentDefinition) -> None:
        if component.component_id in self._store:
            raise ValueError(
                f"Component '{component.component_id}' already registered"
            )
        self._store[component.component_id] = component

    def get(self, component_id: str) -> ScenarioComponentDefinition:
        try:
            return self._store[component_id]
        except KeyError:
            raise ValueError(f"Unknown scenario component: {component_id}") from None

    def all_ids(self) -> list[str]:
        return list(self._store.keys())
```

Prima verifica leggendo l'originale `controlplane_tool/scenario/components/registry.py` che il
contenuto coincida (a parte l'import). Se differisce, copia il contenuto REALE.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_registry.py`:

```python
from __future__ import annotations

import pytest

from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.registry import ComponentRegistry


def _comp(cid: str) -> ScenarioComponentDefinition:
    return ScenarioComponentDefinition(component_id=cid, summary=cid)


def test_register_and_get_roundtrip() -> None:
    reg = ComponentRegistry()
    comp = _comp("a.b")
    reg.register(comp)
    assert reg.get("a.b") is comp
    assert reg.all_ids() == ["a.b"]


def test_register_duplicate_raises() -> None:
    reg = ComponentRegistry()
    reg.register(_comp("a.b"))
    with pytest.raises(ValueError, match="already registered"):
        reg.register(_comp("a.b"))


def test_get_unknown_raises() -> None:
    reg = ComponentRegistry()
    with pytest.raises(ValueError, match="Unknown scenario component"):
        reg.get("missing")
```

- [ ] **Step 3: Esegui i test di libreria**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_registry.py -v`
Expected: PASS (3 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/registry.py` con:

```python
# Shim: re-exports from workflow_tasks.components.registry (migrated in sub-project 2a).
from __future__ import annotations

from workflow_tasks.components.registry import ComponentRegistry

__all__ = ["ComponentRegistry"]
```

Se l'originale esporta altri nomi pubblici, includili tutti.

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_component_registry.py -v`
Expected: PASS.

- [ ] **Step 6: import-linter (entrambi)**

Run i due comandi `lint-imports`. Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/registry.py tools/workflow-tasks/tests/components/test_registry.py tools/controlplane/src/controlplane_tool/scenario/components/registry.py
git commit -m "refactor(workflow-tasks): move ComponentRegistry into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `ScenarioExecutionContext` neutro → `workflow_tasks/components/context.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/context.py`
- Create: `tools/workflow-tasks/tests/components/test_context.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/environment.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py`

**Contesto chiave (verificato):**
- I componenti leggono dal context solo: `repo_root, scenario_name, runtime, namespace,
  local_registry, resolved_scenario (.namespace e .functions[].{key,family,runtime,image}),
  vm_request, cleanup_vm, manifest_path, release, loadgen_vm_request`.
- **Nessuno legge `context.request`** → il campo viene cancellato.
- I costruttori del context sono SOLO due: `environment.resolve_scenario_environment` (factory) e
  `infra/vm/vm_cluster_workflows.py` (costruzione diretta, oggi passa `request=cast(Any, vm_request)`).
- `e2e/e2e_runner.py` legge `context.resolved_scenario` ma **non** va toccato: il context porta
  a runtime il vero `ResolvedScenario`; `e2e_runner` non è nella include-list di basedpyright
  (nessun gate di tipo lo rileva).

- [ ] **Step 1: Crea il context neutro in libreria**

Crea `tools/workflow-tasks/src/workflow_tasks/components/context.py`:

```python
from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from workflow_tasks.vm.models import VmRequest


class ResolvedFunctionView(Protocol):
    """Structural view of a resolved function, as read by components."""

    key: str
    family: str | None
    runtime: str
    image: str | None


class ResolvedScenarioView(Protocol):
    """Structural view of a resolved scenario, as read by components.

    The concrete ResolvedScenario (pydantic) in controlplane satisfies this by shape.
    """

    namespace: str | None
    functions: Sequence[ResolvedFunctionView]


@dataclass(frozen=True, slots=True)
class ScenarioExecutionContext:
    """Neutral execution context consumed by scenario components.

    Deliberately free of product request types (E2eRequest/CliTestRequest): the
    factory that builds it lives in controlplane.
    """

    repo_root: Path
    scenario_name: str
    runtime: str
    namespace: str | None
    local_registry: str
    resolved_scenario: ResolvedScenarioView | None
    vm_request: VmRequest
    cleanup_vm: bool
    manifest_path: Path | None = None
    release: str | None = None
    loadgen_vm_request: VmRequest | None = None
```

- [ ] **Step 2: Test di libreria per il context**

Crea `tools/workflow-tasks/tests/components/test_context.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import (
    ResolvedScenarioView,
    ScenarioExecutionContext,
)
from workflow_tasks.vm.models import VmRequest


@dataclass
class _FakeFunction:
    key: str
    family: str | None
    runtime: str
    image: str | None


@dataclass
class _FakeResolvedScenario:
    namespace: str | None
    functions: list[_FakeFunction]


def test_context_holds_neutral_fields() -> None:
    ctx = ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=None,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        cleanup_vm=True,
    )
    assert ctx.scenario_name == "k3s-junit-curl"
    assert ctx.manifest_path is None
    assert ctx.release is None
    assert ctx.loadgen_vm_request is None


def test_resolved_scenario_view_is_satisfied_structurally() -> None:
    rs: ResolvedScenarioView = _FakeResolvedScenario(
        namespace="ns",
        functions=[_FakeFunction(key="echo", family="echo", runtime="java", image=None)],
    )
    ctx = ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime="java",
        namespace=None,
        local_registry="r",
        resolved_scenario=rs,
        vm_request=VmRequest(lifecycle="multipass", name="x"),
        cleanup_vm=False,
    )
    assert ctx.resolved_scenario is not None
    assert ctx.resolved_scenario.namespace == "ns"
    assert ctx.resolved_scenario.functions[0].runtime == "java"


def test_context_has_no_request_field() -> None:
    # The vestigial product-request field was intentionally dropped.
    assert "request" not in ScenarioExecutionContext.__dataclass_fields__
```

- [ ] **Step 3: Esegui i test di libreria**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_context.py -v`
Expected: PASS (3 test).

- [ ] **Step 4: Riusa il context neutro da `environment.py` e rimuovi `request`**

In `tools/controlplane/src/controlplane_tool/scenario/components/environment.py`:
1. RIMUOVI la definizione locale della dataclass `ScenarioExecutionContext` (l'intero blocco
   `@dataclass(frozen=True, slots=True) class ScenarioExecutionContext: ...`).
2. AGGIUNGI in cima agli import:
   `from workflow_tasks.components.context import ScenarioExecutionContext`
   (così resta importabile da `controlplane_tool.scenario.components.environment` — i componenti
   oggi la importano da qui).
3. Nella factory `resolve_scenario_environment`, nel `return ScenarioExecutionContext(...)`,
   RIMUOVI la riga `request=request,`. Lascia invariato tutto il resto (la factory continua a
   usare `request` come parametro per `_managed_vm_request`, namespace/release, ecc.).

Mantieni gli altri import di `environment.py` (E2eRequest, CliTestRequest, RuntimeKind,
ResolvedScenario, write_scenario_manifest, resolve_scenario_namespace/release, build_scenario_recipe,
VmRequest): sono ancora usati dalla factory e da `_managed_vm_request`.

- [ ] **Step 5: Rimuovi `request=` dalla costruzione diretta in `vm_cluster_workflows.py`**

In `tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py`, nella
costruzione `scenario_context = ScenarioExecutionContext(...)`:
1. RIMUOVI la riga `request=cast(Any, vm_request),`.
2. Il campo `runtime` ora è `str` nel context: se la riga è `runtime=cast(RuntimeKind, runtime),`
   semplificala in `runtime=runtime,` (il parametro `runtime` è già `str`).
3. Dopo le rimozioni, esegui `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py` e rimuovi eventuali
   import diventati inutilizzati (`cast`, `Any`, `RuntimeKind`) se ruff li segnala (F401).
   Lascia gli import ancora usati altrove nel file.

- [ ] **Step 6: Esegui i test controlplane che esercitano il context e i suoi consumatori**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_recipe_execution_hooks.py tools/controlplane/tests/test_e2e_runner.py -v 2>&1 | tail -25`
Expected: nessun NUOVO fallimento rispetto al baseline del commit WIP `4c58d3ac`. (I test e2e
già rossi nel WIP possono restare rossi; non devono aumentare.) Se un test fallisce per
`AttributeError: 'ScenarioExecutionContext' object has no attribute 'request'`, significa che un
consumatore legge ancora `.request` — trovalo (`grep -rn "\.request\b" su variabili di tipo
ScenarioExecutionContext`) e STOP: riporta BLOCKED (la verifica diceva che nessuno lo legge).

- [ ] **Step 7: Suite complete + import-linter**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4`
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -6`
Run: i due `lint-imports`.
Expected: libreria verde (coverage ≥90%, salvo il fallimento proxmox pre-esistente);
controlplane senza NUOVI fallimenti oltre a quelli pre-esistenti del WIP; import-linter 0 broken.

- [ ] **Step 8: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/context.py tools/workflow-tasks/tests/components/test_context.py tools/controlplane/src/controlplane_tool/scenario/components/environment.py tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py
git commit -m "refactor(workflow-tasks): add neutral ScenarioExecutionContext in library, drop vestigial request field

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Boundary test + verifica finale

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi asserzioni di confine per i nuovi moduli**

Append in `tools/workflow-tasks/tests/test_package_boundaries.py` (stesso pattern inline dei test
esistenti):

```python
def test_components_registry_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.registry")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_context_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.context")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui i boundary test**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -v`
Expected: PASS (esistenti + 2 nuovi).

- [ ] **Step 3: Verifica finale completa**

Run la suite libreria completa, la suite controlplane completa, i due `lint-imports`.
Expected: nessun nuovo fallimento oltre ai pre-esistenti del WIP; import-linter 0 broken;
coverage libreria ≥90%.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert components registry/context stay independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- Shim creato qui (`registry.py`) e re-export del context da `environment.py` sono **temporanei**:
  in 2b i componenti importeranno il context da `workflow_tasks.components.context`; in
  sotto-progetto 4 gli shim si rimuovono.
- NON toccare `e2e_runner.py` (non type-checked, runtime ok col context neutro).
- `composer.py`/`recipes.py`/componenti concreti: fuori da 2a (vedi 2b/2c nello spec).
- Baseline fallimenti pre-esistenti = commit `4c58d3ac`. Prima di iniziare, opzionalmente cattura
  il baseline: `uv run --project tools/controlplane pytest tools/controlplane/tests -q 2>&1 | tail -5`.
- Prima di spostare/cancellare simboli, esegui `gitnexus_impact` come da CLAUDE.md.
