# Sotto-progetto 2b.2 — `remote_k6` + two-VM constants + `helm` → library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare in libreria il contratto k6 (`remote_k6`), le costanti pure two-VM, e il componente `helm`, in modo che `helm` non dipenda più da controlplane.

**Architecture:** `remote_k6` (modulo puro) → `workflow_tasks/loadtest/remote_k6.py`. Le 5 costanti two-VM pure → `workflow_tasks/loadtest/two_vm.py`; `two_vm_loadtest_config.py` (che ha 8 importatori e una dipendenza controlplane su `loadtest_catalog`, quindi NON si sposta) le re-importa dalla libreria così i suoi consumatori restano invariati. `helm` → `workflow_tasks/components/helm.py`, importando context/models/operations + `control_image`/`runtime_image` + le 4 costanti dalla libreria. Re-export shim per ogni modulo spostato.

**Tech Stack:** Python 3.11+, dataclasses, pytest, import-linter, uv.

**Comandi base:**
- Test libreria (singolo file): `uv run --project tools/workflow-tasks pytest <path> -v --no-cov`
- Test libreria full: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests`
- Test controlplane: `uv run --project tools/controlplane pytest tools/controlplane/tests` (NIENTE `--no-cov`)
- import-linter: `uv run --project tools/workflow-tasks lint-imports --config tools/workflow-tasks/.importlinter` e `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`

**Baseline pre-esistente (NON nostro):** libreria 1 fail (`test_proxmox_provider.py::test_ensure_running_allows_slow_proxmox_guest_agent`); controlplane 3 fail (`test_e2e_runner.py::test_helm_stack_execute_resolves_vm_host_for_autoscaling_env`, `::test_run_all_bootstraps_vm_once_and_reuses_it`, `test_tui_choices.py::test_tui_proxmox_vm_loadtest_keeps_cleanup_phases_enabled`). Nessun task deve aumentarli.

**Fatti verificati:**
- `remote_k6.py` è puro (solo stdlib): `RemoteK6RunConfig` (frozen dataclass) + `build_k6_command`. Importatori: `scenario/components/two_vm_loadtest.py` e `e2e/two_vm_loadtest_runner.py`.
- `helm.py` dipende da: kernel (context/models/operations), `components.images` (`control_image`, `runtime_image` — già in libreria), e 4 costanti da `two_vm_loadtest_config`: `LOADTEST_SCENARIOS`, `TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT`, `TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT`, `TWO_VM_PROMETHEUS_NODE_PORT` (usate alle righe 61-65 e 112). Nessun'altra dipendenza controlplane.
- Costanti pure in `two_vm_loadtest_config.py`: `LOADTEST_SCENARIOS` (frozenset[str]), `TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT=30080`, `TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT=30081`, `TWO_VM_PROMETHEUS_NODE_PORT=30090`, `TWO_VM_REMOTE_DIR_NAME="two-vm-loadtest"`. Il resto del file (funzioni che usano `loadtest_catalog`, `VmRequest`) RESTA in controlplane.

---

### Task 1: `remote_k6` → `workflow_tasks/loadtest/remote_k6.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/remote_k6.py`
- Create: `tools/workflow-tasks/tests/loadtest/test_remote_k6.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (copia verbatim)**

Leggi `tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py` e crea
`tools/workflow-tasks/src/workflow_tasks/loadtest/remote_k6.py` con contenuto IDENTICO
(è già puro: nessun import controlplane da cambiare — solo `dataclasses` e `pathlib`).

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/loadtest/test_remote_k6.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command


def _cfg(**kw) -> RemoteK6RunConfig:
    base = dict(
        script_path=Path("/k6/script.js"),
        summary_path=Path("/k6/summary.json"),
        control_plane_url="http://cp:30080",
        function_name="echo",
    )
    base.update(kw)
    return RemoteK6RunConfig(**base)


def test_build_k6_command_uses_stages_by_default() -> None:
    cmd = build_k6_command(_cfg(stages=(("30s", 10), ("1m", 50))))
    assert cmd[0:2] == ("k6", "run")
    assert "--summary-export" in cmd
    assert "--stage" in cmd
    joined = " ".join(cmd)
    assert "30s:10" in joined
    assert "1m:50" in joined
    assert "-e" in cmd
    assert "NANOFAAS_URL=http://cp:30080" in cmd
    assert "NANOFAAS_FUNCTION=echo" in cmd
    assert cmd[-1] == "/k6/script.js"


def test_build_k6_command_vus_duration_override_stages() -> None:
    cmd = build_k6_command(_cfg(vus=5, duration="2m", stages=(("30s", 10),)))
    assert "--vus" in cmd and "5" in cmd
    assert "--duration" in cmd and "2m" in cmd
    assert "--stage" not in cmd  # stages skipped when vus/duration set


def test_build_k6_command_includes_payload_when_present() -> None:
    cmd = build_k6_command(_cfg(payload_path=Path("/k6/payload.json")))
    assert "NANOFAAS_PAYLOAD=/k6/payload.json" in cmd


def test_build_k6_command_custom_script_skips_stages() -> None:
    cmd = build_k6_command(_cfg(custom_script=True, stages=(("30s", 10),)))
    assert "--stage" not in cmd
```

VERIFICA contro il sorgente: la logica esatta di quando gli `--stage` vengono emessi (il
sorgente: stages solo se `not custom_script and vus is None and duration is None`). Allinea gli
assert al sorgente reale se differisce.

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_remote_k6.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane**

Sostituisci `tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py` con:

```python
# Shim: re-exports from workflow_tasks.loadtest.remote_k6 (migrated in sub-project 2b.2).
from __future__ import annotations

from workflow_tasks.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command

__all__ = ["RemoteK6RunConfig", "build_k6_command"]
```

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_two_vm_loadtest_runner.py`
Expected: nessun nuovo fallimento.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/loadtest/remote_k6.py tools/workflow-tasks/tests/loadtest/test_remote_k6.py tools/controlplane/src/controlplane_tool/loadtest/remote_k6.py
git commit -m "refactor(workflow-tasks): move remote_k6 command builder into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Two-VM constants → `workflow_tasks/loadtest/two_vm.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py`
- Create: `tools/workflow-tasks/tests/loadtest/test_two_vm_constants.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/two_vm_loadtest_config.py`

- [ ] **Step 1: Crea il modulo costanti in libreria**

Leggi le definizioni reali in `tools/controlplane/src/controlplane_tool/scenario/two_vm_loadtest_config.py`
(righe ~32-50: `LOADTEST_SCENARIOS`, `TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT`,
`TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT`, `TWO_VM_PROMETHEUS_NODE_PORT`, `TWO_VM_REMOTE_DIR_NAME`).
Crea `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py` con quelle 5 costanti VERBATIM:

```python
"""Pure two-VM loadtest constants (node ports, scenario set, remote dir name)."""
from __future__ import annotations

# Copy the exact LOADTEST_SCENARIOS frozenset contents from the source file.
LOADTEST_SCENARIOS: frozenset[str] = frozenset(
    {
        # ... exact scenario names from two_vm_loadtest_config.py ...
    }
)

TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT = 30080
TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT = 30081
TWO_VM_PROMETHEUS_NODE_PORT = 30090
TWO_VM_REMOTE_DIR_NAME = "two-vm-loadtest"

__all__ = [
    "LOADTEST_SCENARIOS",
    "TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT",
    "TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT",
    "TWO_VM_PROMETHEUS_NODE_PORT",
    "TWO_VM_REMOTE_DIR_NAME",
]
```
IMPORTANTE: copia il contenuto ESATTO del frozenset `LOADTEST_SCENARIOS` dal sorgente (non
inventarlo). Verifica leggendo il file.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/loadtest/test_two_vm_constants.py`:

```python
from __future__ import annotations

from workflow_tasks.loadtest.two_vm import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
    TWO_VM_PROMETHEUS_NODE_PORT,
    TWO_VM_REMOTE_DIR_NAME,
)


def test_node_ports_are_stable() -> None:
    assert TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT == 30080
    assert TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT == 30081
    assert TWO_VM_PROMETHEUS_NODE_PORT == 30090


def test_remote_dir_name() -> None:
    assert TWO_VM_REMOTE_DIR_NAME == "two-vm-loadtest"


def test_loadtest_scenarios_is_nonempty_frozenset() -> None:
    assert isinstance(LOADTEST_SCENARIOS, frozenset)
    assert len(LOADTEST_SCENARIOS) >= 1
```

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_two_vm_constants.py -v --no-cov` → PASS.

- [ ] **Step 4: Re-importa le costanti in `two_vm_loadtest_config.py`**

In `tools/controlplane/src/controlplane_tool/scenario/two_vm_loadtest_config.py`:
1. CANCELLA le 5 definizioni di costante (`LOADTEST_SCENARIOS = ...`, i 3 `TWO_VM_*_NODE_PORT = ...`,
   `TWO_VM_REMOTE_DIR_NAME = ...`).
2. AGGIUNGI in cima un import che le ri-porta (mantiene i nomi disponibili per gli 8 importatori
   esterni e per gli usi interni del file):
   ```python
   from workflow_tasks.loadtest.two_vm import (
       LOADTEST_SCENARIOS,
       TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
       TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
       TWO_VM_PROMETHEUS_NODE_PORT,
       TWO_VM_REMOTE_DIR_NAME,
   )
   ```
3. Lascia invariato il resto del file (funzioni `remap_loadtest_component_id`,
   `two_vm_control_plane_url`, ecc., che USANO queste costanti e `loadtest_catalog`).
4. Esegui `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/two_vm_loadtest_config.py` e correggi eventuali F401 (se una costante risultasse non più usata internamente, mantienila comunque nell'import per il re-export — aggiungi `# noqa: F401` solo se necessario; preferisci mantenere l'import esplicito perché è un re-export pubblico).

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_components.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`
Expected: nessun nuovo fallimento.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py tools/workflow-tasks/tests/loadtest/test_two_vm_constants.py tools/controlplane/src/controlplane_tool/scenario/two_vm_loadtest_config.py
git commit -m "refactor(workflow-tasks): move pure two-VM loadtest constants into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `helm` component → `workflow_tasks/components/helm.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/helm.py`
- Create: `tools/workflow-tasks/tests/components/test_helm.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/helm.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim, import rediretti)**

Leggi `tools/controlplane/src/controlplane_tool/scenario/components/helm.py`. Crea
`tools/workflow-tasks/src/workflow_tasks/components/helm.py` come copia VERBATIM, cambiando SOLO
il blocco import:

Da:
```python
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.components.images import control_image, runtime_image
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
    TWO_VM_PROMETHEUS_NODE_PORT,
)
```
A:
```python
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
from workflow_tasks.components.images import control_image, runtime_image
from workflow_tasks.loadtest.two_vm import (
    LOADTEST_SCENARIOS,
    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
    TWO_VM_PROMETHEUS_NODE_PORT,
)
```
Tutto il resto del corpo (funzioni `_image_parts`, i planner Helm, le costanti
`HELM_DEPLOY_CONTROL_PLANE`, `HELM_DEPLOY_FUNCTION_RUNTIME`, ecc.) resta IDENTICO. Verifica
leggendo il file. NB: gli stdlib import (`Mapping`, `MappingProxyType`) restano.

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_helm.py`. Prima LEGGI il corpo di `helm.py` per
identificare i planner pubblici esatti (probabili: `plan_deploy_control_plane`,
`plan_deploy_function_runtime`) e le component-def (`HELM_DEPLOY_CONTROL_PLANE`,
`HELM_DEPLOY_FUNCTION_RUNTIME`). Poi scrivi test che esercitano un planner con un context two-VM
e uno non-two-VM, verificando l'effetto delle costanti:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import helm as helm_mod
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.loadtest.two_vm import LOADTEST_SCENARIOS, TWO_VM_PROMETHEUS_NODE_PORT
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(scenario_name: str) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name=scenario_name,
        runtime="java",
        namespace="ns",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="ns", functions=[]),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
    )


def test_component_definitions_present() -> None:
    assert helm_mod.HELM_DEPLOY_CONTROL_PLANE.component_id == "helm.deploy_control_plane"
    assert helm_mod.HELM_DEPLOY_FUNCTION_RUNTIME.component_id == "helm.deploy_function_runtime"


def test_control_plane_planner_exposes_node_ports_for_loadtest_scenario() -> None:
    scenario = next(iter(LOADTEST_SCENARIOS))
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx(scenario))
    rendered = " ".join(op_arg for op in ops for op_arg in op.argv)
    assert str(TWO_VM_PROMETHEUS_NODE_PORT) in rendered or "nodePort" in rendered


def test_control_plane_planner_for_non_loadtest_scenario_runs() -> None:
    ops = helm_mod.HELM_DEPLOY_CONTROL_PLANE.planner(_ctx("k3s-junit-curl"))
    assert len(ops) >= 1
```

ADATTA i nomi dei planner/component-id e gli assert al sorgente REALE di helm.py (i nomi sopra
sono ipotesi da verificare). L'obiettivo: almeno un test che prova che il planner gira e uno che
copre il ramo "scenario in LOADTEST_SCENARIOS".

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_helm.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane**

Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/helm.py` con un re-export
shim. Prima LEGGI i nomi pubblici reali (planner + component-def + eventuali helper pubblici come
`control_image`/`runtime_image` se erano ri-esportati). Esempio (adatta ai nomi reali):

```python
# Shim: re-exports from workflow_tasks.components.helm (migrated in sub-project 2b.2).
from __future__ import annotations

from workflow_tasks.components.helm import (
    HELM_DEPLOY_CONTROL_PLANE,
    HELM_DEPLOY_FUNCTION_RUNTIME,
    plan_deploy_control_plane,
    plan_deploy_function_runtime,
)

__all__ = [
    "HELM_DEPLOY_CONTROL_PLANE",
    "HELM_DEPLOY_FUNCTION_RUNTIME",
    "plan_deploy_control_plane",
    "plan_deploy_function_runtime",
]
```
Includi OGNI nome pubblico (senza underscore) che il file originale esponeva e che altri moduli
importano (controlla con `grep -rn "components.helm import" tools/controlplane/src`).

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_two_vm_loadtest_components.py`
Expected: nessun nuovo fallimento.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/helm.py tools/workflow-tasks/tests/components/test_helm.py tools/controlplane/src/controlplane_tool/scenario/components/helm.py
git commit -m "refactor(workflow-tasks): move helm component into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Boundary test + verifica finale

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi asserzioni di confine**

Append (stesso pattern inline):

```python
def test_loadtest_remote_k6_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest.remote_k6")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_loadtest_two_vm_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest.two_vm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_helm_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.helm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -v --no-cov` → PASS.

- [ ] **Step 3: Verifica finale completa**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4` → passa, coverage ≥90%, solo il fail proxmox pre-esistente.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4` → solo i 3 baseline.
Run i due `lint-imports` → 0 broken.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert remote_k6/two_vm/helm stay independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- `two_vm_loadtest` component NON è in 2b.2: dipende da `bootstrap`/`cleanup` (non ancora migrati) e dalle funzioni catalog-coupled di `two_vm_loadtest_config`. Va in 2b.3+.
- `two_vm_loadtest_config.py` RESTA in controlplane (dipende da `loadtest_catalog`); qui ne estraiamo solo le costanti pure.
- Shim temporanei (rimossi nel sotto-progetto 4).
- Prima di spostare i simboli, esegui `gitnexus_impact` come da CLAUDE.md.
