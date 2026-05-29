# Sotto-progetto 2b.1 — `images` + `namespace` components → library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare i due componenti context-consumer più puliti (`namespace`, `images`) da `controlplane_tool/scenario/components/` a `workflow_tasks/components/`, ora che il context neutro è in libreria (sotto-progetto 2a).

**Architecture:** Move verbatim con redirezione dei soli import al kernel di libreria (`workflow_tasks.components.context`/`.models`/`.operations`). Re-export shim in controlplane preserva ogni nome pubblico (i consumatori `helm.py` e `composer.py` continuano a importare dalla vecchia sede). Nessun cambiamento di comportamento.

**Tech Stack:** Python 3.11+, dataclasses, pytest, import-linter, uv.

**Comandi base:**
- Test libreria: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests`
- Test libreria (singolo file, senza far scattare il gate coverage del pacchetto): aggiungi `--no-cov`
- Test controlplane: `uv run --project tools/controlplane pytest tools/controlplane/tests` (NON usare `--no-cov`: controlplane non ha pytest-cov)
- import-linter libreria: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter`
- import-linter controlplane: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`

**Baseline fallimenti pre-esistenti (NON nostri, dal commit WIP):** libreria 1 (`test_proxmox_provider.py::test_ensure_running_allows_slow_proxmox_guest_agent`); controlplane 3 (`test_e2e_runner.py::test_helm_stack_execute_resolves_vm_host_for_autoscaling_env`, `test_e2e_runner.py::test_run_all_bootstraps_vm_once_and_reuses_it`, `test_tui_choices.py::test_tui_proxmox_vm_loadtest_keeps_cleanup_phases_enabled`). Ogni task deve mantenere questi numeri invariati (nessun NUOVO fallimento).

**Fatti verificati:**
- `images.py` e `namespace.py` importano SOLO: `ScenarioExecutionContext` (da `controlplane_tool.scenario.components.environment`, che è già un re-export del context neutro di libreria), `ScenarioComponentDefinition` (kernel libreria), `RemoteCommandOperation`/`ScenarioOperation` (kernel libreria).
- Letture dal context: `namespace` → `.namespace`, `.resolved_scenario.namespace`, `.vm_request.{home,user}`; `images` → `.local_registry`, `.runtime`, `.resolved_scenario.functions[].{key,family,runtime,image}`.
- Importatori da preservare via shim: `images` ← `helm.py` (`control_image, runtime_image`) e `composer.py` (`BUILD_CORE, BUILD_SELECTED_FUNCTIONS`); `namespace` ← `composer.py` (`NAMESPACE_INSTALL, NAMESPACE_UNINSTALL`).

---

### Task 1: `namespace` component → `workflow_tasks/components/namespace.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/namespace.py`
- Create: `tools/workflow-tasks/tests/components/test_namespace.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/namespace.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim, import rediretti)**

Copia il contenuto ATTUALE di `tools/controlplane/src/controlplane_tool/scenario/components/namespace.py`
in `tools/workflow-tasks/src/workflow_tasks/components/namespace.py`, cambiando SOLO il blocco import in testa:

Da:
```python
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
```
A:
```python
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
```
Tutto il resto (le funzioni `_frozen_env`, `_namespace`, `_kubeconfig_path`, `namespace_release_name`,
`plan_install_namespace`, `plan_uninstall_namespace`, le costanti `NAMESPACE_RELEASE_NAMESPACE`,
`NAMESPACE_INSTALL`, `NAMESPACE_UNINSTALL`) resta IDENTICO. Verifica leggendo il file reale.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_namespace.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.namespace import (
    NAMESPACE_INSTALL,
    NAMESPACE_UNINSTALL,
    namespace_release_name,
    plan_install_namespace,
    plan_uninstall_namespace,
)
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.functions is None:
            self.functions = []


def _ctx(*, namespace: str | None = None, rs_namespace: str | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime="java",
        namespace=namespace,
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace=rs_namespace) if rs_namespace is not None else None,
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_namespace_release_name() -> None:
    assert namespace_release_name("foo") == "foo-namespace"


def test_install_uses_explicit_namespace() -> None:
    ops = plan_install_namespace(_ctx(namespace="myns"))
    rendered = " ".join(ops[0].argv)
    assert "myns-namespace" in rendered
    assert "namespace.name=myns" in rendered


def test_install_falls_back_to_resolved_then_default() -> None:
    ops_resolved = plan_install_namespace(_ctx(rs_namespace="resns"))
    assert "resns-namespace" in " ".join(ops_resolved[0].argv)
    ops_default = plan_install_namespace(_ctx())
    assert "nanofaas-e2e-namespace" in " ".join(ops_default[0].argv)


def test_uninstall_targets_namespace_release() -> None:
    ops = plan_uninstall_namespace(_ctx(namespace="myns"))
    rendered = " ".join(ops[0].argv)
    assert "uninstall" in rendered
    assert "myns-namespace" in rendered


def test_component_definitions_wire_planners() -> None:
    assert NAMESPACE_INSTALL.planner is plan_install_namespace
    assert NAMESPACE_UNINSTALL.planner is plan_uninstall_namespace
```

NOTA: verifica i campi reali di `VmRequest` (`lifecycle`, `name`, `user`, `home`). Il fallback
namespace di default atteso è `"nanofaas-e2e"` (vedi `_namespace` nel sorgente). Se il default reale
differisce, allinea l'assert al sorgente, non viceversa.

- [ ] **Step 3: Esegui i test di libreria**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_namespace.py -v --no-cov`
Expected: PASS (5 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/namespace.py` con:

```python
# Shim: re-exports from workflow_tasks.components.namespace (migrated in sub-project 2b.1).
from __future__ import annotations

from workflow_tasks.components.namespace import (
    NAMESPACE_INSTALL,
    NAMESPACE_RELEASE_NAMESPACE,
    NAMESPACE_UNINSTALL,
    namespace_release_name,
    plan_install_namespace,
    plan_uninstall_namespace,
)

__all__ = [
    "NAMESPACE_INSTALL",
    "NAMESPACE_RELEASE_NAMESPACE",
    "NAMESPACE_UNINSTALL",
    "namespace_release_name",
    "plan_install_namespace",
    "plan_uninstall_namespace",
]
```

Se leggendo il sorgente trovi altri nomi pubblici (senza underscore) non elencati, aggiungili.

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_component_registry.py tools/controlplane/tests/test_scenario_builders.py`
Expected: PASS (nessun nuovo fallimento).

- [ ] **Step 6: import-linter (entrambi)**

Run i due `lint-imports`. Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/namespace.py tools/workflow-tasks/tests/components/test_namespace.py tools/controlplane/src/controlplane_tool/scenario/components/namespace.py
git commit -m "refactor(workflow-tasks): move namespace component into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `images` component → `workflow_tasks/components/images.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/images.py`
- Create: `tools/workflow-tasks/tests/components/test_images.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/images.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim, import rediretti)**

Copia il contenuto ATTUALE di `tools/controlplane/src/controlplane_tool/scenario/components/images.py`
in `tools/workflow-tasks/src/workflow_tasks/components/images.py`, cambiando SOLO il blocco import:

Da:
```python
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
```
A:
```python
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
```
Lascia invariato tutto il resto: `control_image`, `runtime_image`, `function_image_specs`,
`_frozen_env`, `_dockerfile_for_runtime_kind`, `_RUST_CP_DIR`, `plan_build_core`, `_prune_image_op`,
`plan_build_selected_functions`, `BUILD_CORE`, `BUILD_SELECTED_FUNCTIONS`, e gli import stdlib
(`shlex`, `Mapping`, `Path`, `MappingProxyType`). Verifica leggendo il file reale.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_images.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.images import (
    BUILD_CORE,
    BUILD_SELECTED_FUNCTIONS,
    control_image,
    function_image_specs,
    plan_build_core,
    plan_build_selected_functions,
    runtime_image,
)
from workflow_tasks.vm.models import VmRequest


@dataclass
class _Fn:
    key: str
    family: str | None
    runtime: str
    image: str | None


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, runtime: str = "java", functions: list | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="s",
        runtime=runtime,
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="ns", functions=functions or []),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_image_name_helpers() -> None:
    assert control_image("reg:5000") == "reg:5000/nanofaas/control-plane:e2e"
    assert runtime_image("reg:5000") == "reg:5000/nanofaas/function-runtime:e2e"


def test_function_image_specs_skips_fixtures_and_familyless() -> None:
    fns = [
        _Fn(key="a", family="echo", runtime="java", image=None),
        _Fn(key="b", family=None, runtime="java", image=None),
        _Fn(key="c", family="x", runtime="fixture", image=None),
    ]
    specs = function_image_specs(_RS(namespace=None, functions=fns), "fallback:img")
    keys = [s[3] for s in specs]
    assert keys == ["a"]
    assert specs[0][0] == "fallback:img"  # image falls back


def test_plan_build_core_java_builds_jars_and_pushes() -> None:
    ops = plan_build_core(_ctx(runtime="java"))
    ids = [op.operation_id for op in ops]
    assert "images.build_core.boot_jars" in ids
    assert "images.build_core.control_image" in ids
    assert "images.build_core.push_runtime_image" in ids


def test_plan_build_core_rust_skips_boot_jars() -> None:
    ops = plan_build_core(_ctx(runtime="rust"))
    ids = [op.operation_id for op in ops]
    assert "images.build_core.boot_jars" not in ids
    assert "images.build_core.control_image" in ids


def test_plan_build_selected_functions_emits_build_push_prune() -> None:
    fns = [_Fn(key="echo", family="echo", runtime="java", image="reg:5000/echo:e2e")]
    ops = plan_build_selected_functions(_ctx(functions=fns))
    ids = [op.operation_id for op in ops]
    assert "images.build_selected_functions.echo" in ids
    assert "images.push_selected_functions.echo" in ids
    assert any(i.startswith("images.prune_selected_functions") for i in ids)


def test_component_definitions_wire_planners() -> None:
    assert BUILD_CORE.planner is plan_build_core
    assert BUILD_SELECTED_FUNCTIONS.planner is plan_build_selected_functions
```

NOTA: i `operation_id` attesi sono presi dal sorgente reale di `images.py`. Se differiscono (il
WIP potrebbe averli toccati), allinea gli assert al sorgente. Verifica anche la firma di
`function_image_specs(resolved_scenario, fallback_runtime_image)`.

- [ ] **Step 3: Esegui i test di libreria**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_images.py -v --no-cov`
Expected: PASS (6 test).

- [ ] **Step 4: Converti il modulo controlplane in shim**

Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/images.py` con:

```python
# Shim: re-exports from workflow_tasks.components.images (migrated in sub-project 2b.1).
from __future__ import annotations

from workflow_tasks.components.images import (
    BUILD_CORE,
    BUILD_SELECTED_FUNCTIONS,
    control_image,
    function_image_specs,
    plan_build_core,
    plan_build_selected_functions,
    runtime_image,
)

__all__ = [
    "BUILD_CORE",
    "BUILD_SELECTED_FUNCTIONS",
    "control_image",
    "function_image_specs",
    "plan_build_core",
    "plan_build_selected_functions",
    "runtime_image",
]
```

Se leggendo il sorgente trovi altri nomi pubblici (senza underscore) non elencati, aggiungili
(in particolare assicurati che `control_image` e `runtime_image`, usati da `helm.py`, ci siano).

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_scenario_builders.py`
Expected: PASS (nessun nuovo fallimento). NOTA: `helm.py` importa `control_image`/`runtime_image`
dallo shim — se un test su helm fallisce per ImportError, lo shim manca un nome: aggiungilo.

- [ ] **Step 6: import-linter (entrambi)**

Run i due `lint-imports`. Expected: 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/images.py tools/workflow-tasks/tests/components/test_images.py tools/controlplane/src/controlplane_tool/scenario/components/images.py
git commit -m "refactor(workflow-tasks): move images component into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Boundary test + verifica finale

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi asserzioni di confine**

Append in `tools/workflow-tasks/tests/test_package_boundaries.py` (stesso pattern inline esistente):

```python
def test_components_namespace_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.namespace")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_images_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.images")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui i boundary test**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -v --no-cov`
Expected: PASS (esistenti + 2 nuovi).

- [ ] **Step 3: Verifica finale completa**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4`
Expected: passa, coverage ≥90%, solo il fallimento proxmox pre-esistente.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4`
Expected: `3 failed, <N> passed` — solo i 3 baseline, nessun nuovo fallimento.
Run i due `lint-imports`. Expected: 0 broken.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert images/namespace components stay independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- Gli shim (`namespace.py`, `images.py`) sono temporanei (rimossi nel sotto-progetto 4).
- `helm.py` e `composer.py` continuano a importare dai vecchi path (shim) — NON modificarli qui.
- Componenti restanti (`helm`, `two_vm_loadtest`, `verification`, `cleanup`, `bootstrap`) e i
  contratti condivisi (`remote_k6`) sono fuori da 2b.1 (vedi 2b.2/2b.3/2b.4).
- Prima di spostare i simboli, esegui `gitnexus_impact` come da CLAUDE.md.
