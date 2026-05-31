# Sotto-progetto 2b.3 — builder puri + `verification` + `cleanup` → library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare in libreria i builder di comando puri di cui `verification` ha bisogno (platform CLI arg builders + lo script k8s e2e), poi spostare i componenti `verification` e `cleanup`.

**Architecture:** `cli_platform_workflow.py` è interamente puro → si sposta intero in `workflow_tasks/components/platform_commands.py` (shim a controlplane per `cli.py`/`verification`). `k8s_e2e_test_vm_script` (puro, ma intrappolato nell'impuro `scenario_tasks.py`) viene reimplementato in `workflow_tasks/components/remote_script.py` **inlinizzando** l'unica riga di `_render_remote_script` per il caso a comando singolo (comportamento identico: `cd {quote(dir)} && {cmd}`); `scenario_tasks.py` ri-esporta `k8s_e2e_test_vm_script` dalla libreria e mantiene i propri helper per le altre funzioni. Poi `verification` e `cleanup` salgono in libreria con shim.

**Tech Stack:** Python 3.11+, shlex, pytest, import-linter, uv.

**Comandi base:** come nei piani 2b precedenti. Libreria singolo file: aggiungi `--no-cov`. Controlplane: NIENTE `--no-cov`.

**Baseline pre-esistente (NON nostro):** libreria 1 fail (proxmox `test_ensure_running_allows_slow_proxmox_guest_agent`); controlplane 3 fail (2× `test_e2e_runner.py`, 1× `test_tui_choices.py`). Nessun task deve aumentarli.

**Fatti verificati:**
- `cli_platform_workflow.py` è PURO (solo `pathlib`): `_image_parts`, `platform_install_command`, `platform_status_command`, `platform_uninstall_command`. Importatori: `verification` (status), `cli.py` (install+status).
- `k8s_e2e_test_vm_script(*, remote_dir, kubeconfig_path, runtime_image, namespace, remote_manifest_path=None) -> str` (in `scenario_tasks.py`) è puro ma usa `_render_remote_script(remote_dir=..., commands=[command])` con UN solo comando → equivale a `f"cd {shlex.quote(remote_dir)} && {command}"`. Importatori di `k8s_e2e_test_vm_script`: `verification` e `scenario_tasks` stesso.
- `verification.py` dipende SOLO da: `platform_status_command` (cli_platform_workflow), `k8s_e2e_test_vm_script` (scenario_tasks), context (environment), operations. Gli altri riferimenti a `controlplane_tool.e2e.k3s_curl_runner` / `controlplane-tool` / `experiments/autoscaling.py` sono **stringhe di comando** (eseguite nella VM), non import.
- `cleanup.py` dipende SOLO da: `verification.plan_verify_cli_platform_status_fails` + kernel.
- Importatori shim da preservare: `cleanup` ← `two_vm_loadtest` (`plan_vm_down`), `composer`, `components/__init__`; `verification` ← `composer`, `components/__init__`.

---

### Task 1: `cli_platform_workflow` → `workflow_tasks/components/platform_commands.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/platform_commands.py`
- Create: `tools/workflow-tasks/tests/components/test_platform_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_platform_workflow.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (copia verbatim, è puro)**

Leggi `tools/controlplane/src/controlplane_tool/cli_validation/cli_platform_workflow.py` e crea
`tools/workflow-tasks/src/workflow_tasks/components/platform_commands.py` con contenuto IDENTICO
(solo `from __future__ import annotations` + `from pathlib import Path` + le 4 funzioni
`_image_parts`, `platform_install_command`, `platform_status_command`, `platform_uninstall_command`).
Aggiungi un `__all__` con le 3 funzioni pubbliche:
```python
__all__ = ["platform_install_command", "platform_status_command", "platform_uninstall_command"]
```

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_platform_commands.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.components.platform_commands import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)


def test_status_command() -> None:
    assert platform_status_command("nf") == ["platform", "status", "-n", "nf"]


def test_uninstall_command() -> None:
    assert platform_uninstall_command(release="cp", namespace="nf") == [
        "platform", "uninstall", "--release", "cp", "-n", "nf",
    ]


def test_install_command_splits_image_repo_and_tag() -> None:
    cmd = platform_install_command(
        repo_root=Path("/repo"),
        release="cp",
        namespace="nf",
        control_plane_image="reg:5000/nanofaas/control-plane:e2e",
    )
    assert cmd[:2] == ["platform", "install"]
    assert "--control-plane-repository" in cmd
    i = cmd.index("--control-plane-repository")
    assert cmd[i + 1] == "reg:5000/nanofaas/control-plane"
    j = cmd.index("--control-plane-tag")
    assert cmd[j + 1] == "e2e"
    assert "/repo/helm/nanofaas" in cmd


def test_install_command_defaults_tag_to_latest_when_no_colon() -> None:
    cmd = platform_install_command(
        repo_root=Path("/repo"), release="cp", namespace="nf",
        control_plane_image="control-plane",
    )
    j = cmd.index("--control-plane-tag")
    assert cmd[j + 1] == "latest"
```

VERIFICA gli esatti flag prodotti da `platform_install_command` contro il sorgente e allinea gli
assert se differiscono.

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_platform_commands.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane**

Sostituisci `tools/controlplane/src/controlplane_tool/cli_validation/cli_platform_workflow.py` con:

```python
# Shim: re-exports from workflow_tasks.components.platform_commands (migrated in sub-project 2b.3).
from __future__ import annotations

from workflow_tasks.components.platform_commands import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)

__all__ = [
    "platform_install_command",
    "platform_status_command",
    "platform_uninstall_command",
]
```
NB: se qualche consumatore importa anche `_image_parts` da questo modulo, controlla con
`grep -rn "cli_platform_workflow import" tools/controlplane/src` e aggiungilo allo shim. (Atteso:
solo `cli.py` importa `platform_install_command, platform_status_command`.)

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_scenario_component_library.py`
Expected: nessun nuovo fallimento. Se un test cerca `_image_parts` o altri nomi, aggiungili allo shim.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/platform_commands.py tools/workflow-tasks/tests/components/test_platform_commands.py tools/controlplane/src/controlplane_tool/cli_validation/cli_platform_workflow.py
git commit -m "refactor(workflow-tasks): move platform CLI command builders into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `k8s_e2e_test_vm_script` → `workflow_tasks/components/remote_script.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/remote_script.py`
- Create: `tools/workflow-tasks/tests/components/test_remote_script.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py` (k8s_e2e_test_vm_script → re-export)

- [ ] **Step 1: Crea il modulo di libreria (reimplementa inlinizzando il render single-command)**

Crea `tools/workflow-tasks/src/workflow_tasks/components/remote_script.py`:

```python
"""Pure remote-shell script builders used by scenario components."""
from __future__ import annotations

import shlex

__all__ = ["k8s_e2e_test_vm_script"]


def k8s_e2e_test_vm_script(
    *,
    remote_dir: str,
    kubeconfig_path: str,
    runtime_image: str,
    namespace: str,
    remote_manifest_path: str | None = None,
) -> str:
    manifest_property = ""
    if remote_manifest_path is not None:
        manifest_property = f"-Dnanofaas.e2e.scenarioManifest={shlex.quote(remote_manifest_path)} "
    command = (
        f"KUBECONFIG={shlex.quote(kubeconfig_path)} "
        f"FUNCTION_RUNTIME_IMAGE={shlex.quote(runtime_image)} "
        f"NANOFAAS_E2E_NAMESPACE={shlex.quote(namespace)} "
        f"./gradlew :control-plane-modules:k8s-deployment-provider:test "
        f"{manifest_property}-PrunE2e --tests "
        "it.unimib.datai.nanofaas.modules.k8s.e2e.K8sE2eTest --no-daemon"
    )
    # Equivalent to scenario_tasks._render_remote_script(remote_dir, commands=[command])
    # for the single-command case: `cd <dir> && <command>`.
    return f"cd {shlex.quote(remote_dir)} && {command}"
```

CRITICO: copia il corpo `command = (...)` ESATTAMENTE dal sorgente
`scenario_tasks.k8s_e2e_test_vm_script` (verifica leggendolo). L'unica differenza ammessa è
l'inline del `cd ... &&` al posto della chiamata a `_render_remote_script`.

- [ ] **Step 2: Test di libreria (incl. equivalenza con l'originale)**

Crea `tools/workflow-tasks/tests/components/test_remote_script.py`:

```python
from __future__ import annotations

from workflow_tasks.components.remote_script import k8s_e2e_test_vm_script


def test_script_has_cd_prefix_and_gradle_invocation() -> None:
    script = k8s_e2e_test_vm_script(
        remote_dir="/home/ubuntu/nanofaas",
        kubeconfig_path="/home/ubuntu/.kube/config",
        runtime_image="reg:5000/nanofaas/function-runtime:e2e",
        namespace="nf",
    )
    assert script.startswith("cd /home/ubuntu/nanofaas && ")
    assert "KUBECONFIG=/home/ubuntu/.kube/config" in script
    assert "FUNCTION_RUNTIME_IMAGE=reg:5000/nanofaas/function-runtime:e2e" in script
    assert "NANOFAAS_E2E_NAMESPACE=nf" in script
    assert ":control-plane-modules:k8s-deployment-provider:test" in script
    assert "K8sE2eTest" in script
    assert "scenarioManifest" not in script  # none provided


def test_script_includes_manifest_property_when_present() -> None:
    script = k8s_e2e_test_vm_script(
        remote_dir="/r",
        kubeconfig_path="/k",
        runtime_image="img",
        namespace="nf",
        remote_manifest_path="/r/manifests/x.yml",
    )
    assert "-Dnanofaas.e2e.scenarioManifest=/r/manifests/x.yml" in script
```

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_remote_script.py -v --no-cov` → PASS.

- [ ] **Step 4: `scenario_tasks.py` ri-esporta dalla libreria**

In `tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`:
1. CANCELLA la `def k8s_e2e_test_vm_script(...)` (l'intera funzione, righe ~306-325).
2. AGGIUNGI in cima un import: `from workflow_tasks.components.remote_script import k8s_e2e_test_vm_script  # noqa: F401` (re-export per i consumatori di `scenario_tasks.k8s_e2e_test_vm_script`).
3. NON toccare il resto del file: `_render_remote_script`, `_shell_join`, `_with_sudo`, e le altre ~10 funzioni che usano `_render_remote_script` restano IDENTICHE (sono ancora usate).
4. Esegui `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`. Se `_render_remote_script`/`_shell_join` diventassero inutilizzati (non dovrebbero: 10 chiamanti restano), NON cancellarli senza verificare. Risolvi solo F401 reali.

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_e2e_runner.py`
Expected: nessun nuovo fallimento (oltre ai 3 baseline). Verifica in particolare che i test che
controllano lo script k8s e2e (se esistono) passino con l'output inlinizzato — l'output deve essere
byte-identico all'originale per il caso single-command.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/remote_script.py tools/workflow-tasks/tests/components/test_remote_script.py tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py
git commit -m "refactor(workflow-tasks): move k8s e2e remote-script builder into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `verification` component → `workflow_tasks/components/verification.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/verification.py`
- Create: `tools/workflow-tasks/tests/components/test_verification.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/verification.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim, import rediretti)**

Leggi `tools/controlplane/src/controlplane_tool/scenario/components/verification.py`. Crea
`tools/workflow-tasks/src/workflow_tasks/components/verification.py` come copia VERBATIM,
cambiando SOLO il blocco import:

Da:
```python
from controlplane_tool.cli_validation.cli_platform_workflow import platform_status_command
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.scenario_tasks import k8s_e2e_test_vm_script
```
A:
```python
from workflow_tasks.components.platform_commands import platform_status_command
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
from workflow_tasks.components.remote_script import k8s_e2e_test_vm_script
```
Tutto il resto IDENTICO (helper `_namespace`, `_kubeconfig_path`, `_remote_home`,
`_remote_project_dir`, `_remote_manifest_path`, `_remote_exec_argv`, `_managed_vm_env`,
`_cli_binary`, `_frozen_env`; planner `plan_verify_cli_platform_status_fails`,
`plan_run_k3s_curl_checks`, `plan_run_k8s_junit`, `plan_loadtest_run`, `plan_autoscaling_experiment`).
Le stringhe `controlplane_tool.e2e.k3s_curl_runner`, `tools/controlplane`, `controlplane-tool`,
`experiments/autoscaling.py` restano invariate (sono comandi eseguiti nella VM).

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_verification.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import verification as ver
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, namespace: str | None = "nf", manifest: Path | None = None) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace=namespace,
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu"),
        cleanup_vm=True,
        manifest_path=manifest,
    )


def test_verify_cli_platform_status_fails_builds_platform_status_argv() -> None:
    ops = ver.plan_verify_cli_platform_status_fails(_ctx())
    argv = ops[0].argv
    assert "platform" in argv and "status" in argv
    assert argv[-1] == "nf"
    assert ops[0].execution_target == "vm"


def test_run_k8s_junit_embeds_gradle_e2e_script() -> None:
    ops = ver.plan_run_k8s_junit(_ctx())
    rendered = " ".join(ops[0].argv)
    assert "K8sE2eTest" in rendered
    assert "k8s-deployment-provider:test" in rendered


def test_run_k3s_curl_checks_runs_controlplane_runner() -> None:
    ops = ver.plan_run_k3s_curl_checks(_ctx())
    rendered = " ".join(ops[0].argv)
    assert "controlplane_tool.e2e.k3s_curl_runner" in rendered
```

ADATTA gli assert al sorgente reale (nomi planner, struttura argv). Se un planner richiede campi
del context non impostati, aggiungili al `_ctx`. Verifica i campi di `VmRequest`
(`cpus`/`memory`/`disk`/`home` usati da `_managed_vm_env` — se obbligatori senza default, passali).

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_verification.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane**

Leggi i nomi pubblici reali e gli importatori (`grep -rn "components.verification import" tools/controlplane/src`). Sostituisci
`tools/controlplane/src/controlplane_tool/scenario/components/verification.py` con uno shim che
ri-esporta TUTTI i planner pubblici usati altrove (almeno: `plan_verify_cli_platform_status_fails`,
`plan_run_k3s_curl_checks`, `plan_run_k8s_junit`, `plan_loadtest_run`, `plan_autoscaling_experiment`):

```python
# Shim: re-exports from workflow_tasks.components.verification (migrated in sub-project 2b.3).
from __future__ import annotations

from workflow_tasks.components.verification import (
    plan_autoscaling_experiment,
    plan_loadtest_run,
    plan_run_k3s_curl_checks,
    plan_run_k8s_junit,
    plan_verify_cli_platform_status_fails,
)

__all__ = [
    "plan_autoscaling_experiment",
    "plan_loadtest_run",
    "plan_run_k3s_curl_checks",
    "plan_run_k8s_junit",
    "plan_verify_cli_platform_status_fails",
]
```
Includi ogni altro nome pubblico che il grep mostra importato.

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_recipe_execution_hooks.py`
Expected: nessun nuovo fallimento.

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/verification.py tools/workflow-tasks/tests/components/test_verification.py tools/controlplane/src/controlplane_tool/scenario/components/verification.py
git commit -m "refactor(workflow-tasks): move verification component into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `cleanup` component → `workflow_tasks/components/cleanup.py`

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/components/cleanup.py`
- Create: `tools/workflow-tasks/tests/components/test_cleanup.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/cleanup.py` (→ shim)

- [ ] **Step 1: Crea il modulo di libreria (move verbatim, import rediretti)**

Leggi `tools/controlplane/src/controlplane_tool/scenario/components/cleanup.py`. Crea
`tools/workflow-tasks/src/workflow_tasks/components/cleanup.py` come copia VERBATIM, cambiando SOLO
il blocco import:

Da:
```python
from controlplane_tool.scenario.components.environment import ScenarioExecutionContext
from controlplane_tool.scenario.components.models import ScenarioComponentDefinition
from controlplane_tool.scenario.components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.scenario.components.verification import plan_verify_cli_platform_status_fails
```
A:
```python
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.components.models import ScenarioComponentDefinition
from workflow_tasks.components.operations import RemoteCommandOperation, ScenarioOperation
from workflow_tasks.components.verification import plan_verify_cli_platform_status_fails
```
Tutto il resto IDENTICO (helper `_frozen_env`, `_namespace`, `_control_plane_release`,
`_kubeconfig_path`; planner `plan_uninstall_control_plane`, `plan_uninstall_function_runtime`,
`plan_vm_down`; costanti `UNINSTALL_CONTROL_PLANE`, `UNINSTALL_FUNCTION_RUNTIME`, `VM_DOWN`,
`VERIFY_CLI_PLATFORM_STATUS_FAILS`).

- [ ] **Step 2: Test di libreria**

Crea `tools/workflow-tasks/tests/components/test_cleanup.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from workflow_tasks.components import cleanup as cl
from workflow_tasks.components.context import ScenarioExecutionContext
from workflow_tasks.vm.models import VmRequest


@dataclass
class _RS:
    namespace: str | None
    functions: list


def _ctx(*, lifecycle: str = "multipass", name: str = "nanofaas-e2e") -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=Path("/repo"),
        scenario_name="k3s-junit-curl",
        runtime="java",
        namespace="nf",
        local_registry="localhost:5000",
        resolved_scenario=_RS(namespace="nf", functions=[]),
        vm_request=VmRequest(lifecycle=lifecycle, name=name, user="ubuntu"),
        cleanup_vm=True,
    )


def test_uninstall_control_plane_uses_helm_uninstall() -> None:
    ops = cl.plan_uninstall_control_plane(_ctx())
    argv = ops[0].argv
    assert argv[0] == "helm" and "uninstall" in argv
    assert "-n" in argv and "nf" in argv
    assert ops[0].execution_target == "vm"


def test_vm_down_multipass_deletes_vm() -> None:
    ops = cl.plan_vm_down(_ctx(lifecycle="multipass", name="myvm"))
    rendered = " ".join(ops[0].argv)
    assert "multipass" in rendered and "delete" in rendered and "myvm" in rendered


def test_vm_down_external_skips_teardown() -> None:
    ops = cl.plan_vm_down(_ctx(lifecycle="external"))
    rendered = " ".join(ops[0].argv)
    assert "Skipping teardown" in rendered or "echo" in ops[0].argv[0]


def test_component_definitions_present() -> None:
    assert cl.UNINSTALL_CONTROL_PLANE.component_id == "cleanup.uninstall_control_plane"
    assert cl.VM_DOWN.component_id == "vm.down"
    assert cl.VERIFY_CLI_PLATFORM_STATUS_FAILS.component_id == "cleanup.verify_cli_platform_status_fails"
```

ADATTA gli assert al sorgente (per `external` lifecycle `VmRequest` potrebbe richiedere `host`;
se serve, passa `host="vm.test"`). Verifica i `component_id` esatti.

- [ ] **Step 3: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/components/test_cleanup.py -v --no-cov` → PASS.

- [ ] **Step 4: Shim controlplane**

Sostituisci `tools/controlplane/src/controlplane_tool/scenario/components/cleanup.py` con uno shim
che ri-esporta TUTTI i nomi pubblici (planner + 4 component-def):

```python
# Shim: re-exports from workflow_tasks.components.cleanup (migrated in sub-project 2b.3).
from __future__ import annotations

from workflow_tasks.components.cleanup import (
    UNINSTALL_CONTROL_PLANE,
    UNINSTALL_FUNCTION_RUNTIME,
    VERIFY_CLI_PLATFORM_STATUS_FAILS,
    VM_DOWN,
    plan_uninstall_control_plane,
    plan_uninstall_function_runtime,
    plan_vm_down,
)

__all__ = [
    "UNINSTALL_CONTROL_PLANE",
    "UNINSTALL_FUNCTION_RUNTIME",
    "VERIFY_CLI_PLATFORM_STATUS_FAILS",
    "VM_DOWN",
    "plan_uninstall_control_plane",
    "plan_uninstall_function_runtime",
    "plan_vm_down",
]
```

- [ ] **Step 5: Test controlplane correlati**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_two_vm_loadtest_components.py`
Expected: nessun nuovo fallimento (`two_vm_loadtest` importa `plan_vm_down` dallo shim cleanup).

- [ ] **Step 6: import-linter (entrambi)** → 0 broken.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/components/cleanup.py tools/workflow-tasks/tests/components/test_cleanup.py tools/controlplane/src/controlplane_tool/scenario/components/cleanup.py
git commit -m "refactor(workflow-tasks): move cleanup component into library

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Boundary test + verifica finale

**Files:**
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`

- [ ] **Step 1: Aggiungi asserzioni di confine** (stesso pattern inline) per:
  `workflow_tasks.components.platform_commands`, `workflow_tasks.components.remote_script`,
  `workflow_tasks.components.verification`, `workflow_tasks.components.cleanup`.

```python
def test_components_platform_commands_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.platform_commands")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_remote_script_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.remote_script")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_verification_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.verification")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_components_cleanup_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.components.cleanup")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 2: Esegui** `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_package_boundaries.py -v --no-cov` → PASS.

- [ ] **Step 3: Verifica finale completa**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -4` → coverage ≥90%, solo fail proxmox.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4` → solo i 3 baseline.
Run i due `lint-imports` → 0 broken.

- [ ] **Step 4: Commit**

```bash
git add tools/workflow-tasks/tests/test_package_boundaries.py
git commit -m "test(workflow-tasks): assert verification/cleanup/builders stay independent of controlplane

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Note di esecuzione

- Ordine obbligatorio: Task 1+2 (builder) PRIMA di Task 3 (verification li importa), Task 3 PRIMA di Task 4 (cleanup importa verification).
- `scenario_tasks.py` RESTA in controlplane (impuro: HelmOps/ImageOps); estraiamo solo `k8s_e2e_test_vm_script`.
- `cli_platform_workflow.py` diventa shim; `cli.py` (gruppo 2c) continua a usarlo dal re-export.
- Shim temporanei (rimossi nel sotto-progetto 4).
- Prima di spostare i simboli, esegui `gitnexus_impact` come da CLAUDE.md.
