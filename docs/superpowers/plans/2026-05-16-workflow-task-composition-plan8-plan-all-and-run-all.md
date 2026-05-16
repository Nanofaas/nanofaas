# Workflow Task Composition — Piano 8: plan_all e run_all Builder Dispatch

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Completare la migrazione di `plan_all()` e `run_all()` in modo che tutti gli scenari VM restituiscano builder tipizzati e vengano eseguiti tramite `plan.run()`, eliminando il path di esecuzione diretto via `_execute_steps()` per i piani recipe-based.

**Architecture:** Due cambiamenti coordinati in `e2e_runner.py`. Task 1: `plan_all()` avvolge l'output di `_planner.vm_backed_steps()` in builder tipizzati per k3s-junit-curl, helm-stack, cli-stack — preservando l'ottimizzazione `include_bootstrap`. Task 2: `run_all()` dispatcha i builder plans via `plan.run()` invece di `_execute_steps()` direttamente. Il `ScenarioPlanner` rimane (ancora usato per generare i step in `plan_all()` con `include_bootstrap`) — la sua rimozione è Piano 9.

**Tech Stack:** Python 3.11+, dataclasses, builder pattern (Piano 3-7). Solo `e2e_runner.py` e `test_e2e_runner.py` modificati.

---

## Background — stato attuale

**`plan_all()` per scenari VM:**
```python
if scenario.name == "two-vm-loadtest":   → TwoVmLoadtestPlan   ✅ (Piano 6)
if scenario.name == "azure-vm-loadtest": → AzureVmLoadtestPlan  ✅ (Piano 6)
# k3s-junit-curl / helm-stack / cli-stack:
steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))  # ❌ non tipizzato
```

**`run_all()` esecuzione:**
```python
for plan in plans:
    self._execute_steps(plan)   # ❌ bypassa plan.run() per tutti i piani
```

**Dopo Piano 8:**
- `plan_all()` → tutti e 5 gli scenari VM restituiscono builder tipizzati
- `run_all()` → usa `plan.run()` per i builder, `_execute_steps()` solo per local scenarios

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — ramo k3s/helm/cli-stack in `plan_all()`, loop in `run_all()`
- `tools/controlplane/tests/test_e2e_runner.py` — 4 nuovi test

---

## Task 1: `plan_all()` restituisce builder tipizzati per k3s / helm-stack / cli-stack

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:428–432`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

**Contesto:** `_planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)` continua a generare i passi (preserva l'ottimizzazione). Cambia SOLO il wrapper: da `ScenarioPlan(scenario, request, steps)` a `K3sJunitCurlPlan(scenario, request, steps, runner=self)`.

- [ ] **Step 1: Leggi il ramo corrente**

```bash
sed -n '425,435p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
```

Verifica di vedere il blocco generico con `ScenarioPlan(scenario=scenario, ...)`.

- [ ] **Step 2: Aggiungi 3 test che falliscono**

Leggi le ultime 30 righe di `tools/controlplane/tests/test_e2e_runner.py`, poi aggiungi in fondo:

```python
def test_plan_all_returns_typed_builder_for_k3s_junit_curl(tmp_path: Path) -> None:
    """plan_all() must return K3sJunitCurlPlan for k3s-junit-curl, not generic ScenarioPlan."""
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["k3s-junit-curl"])

    assert len(plans) == 1
    assert isinstance(plans[0], K3sJunitCurlPlan), (
        f"Expected K3sJunitCurlPlan, got {type(plans[0])}"
    )


def test_plan_all_returns_typed_builder_for_helm_stack(tmp_path: Path) -> None:
    """plan_all() must return HelmStackPlan for helm-stack."""
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["helm-stack"])

    assert len(plans) == 1
    assert isinstance(plans[0], HelmStackPlan), (
        f"Expected HelmStackPlan, got {type(plans[0])}"
    )


def test_plan_all_returns_typed_builder_for_cli_stack(tmp_path: Path) -> None:
    """plan_all() must return CliStackPlan for cli-stack."""
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli-stack"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliStackPlan), (
        f"Expected CliStackPlan, got {type(plans[0])}"
    )
```

- [ ] **Step 3: Verifica che i test falliscano**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_all_returns_typed_builder_for_k3s_junit_curl -v 2>&1 | tail -6
```
Expected: `AssertionError: Expected K3sJunitCurlPlan, got <class '...ScenarioPlan'>`.

- [ ] **Step 4: Sostituisci il ramo generico in `plan_all()`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, trova:

```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

Sostituisci con:

```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "k3s-junit-curl":
                    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
                    plans.append(K3sJunitCurlPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "helm-stack":
                    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
                    plans.append(HelmStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli-stack":
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

**Nota:** `steps` è generato da `_planner.vm_backed_steps()` — uguale a prima. Cambia SOLO il tipo del wrapper. L'ottimizzazione `include_bootstrap` è preservata.

- [ ] **Step 5: Verifica che i 3 nuovi test passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "k3s_junit_curl or helm_stack or cli_stack" -q 2>&1 | tail -5
```
Expected: i 3 nuovi test passano.

- [ ] **Step 6: Verifica nessuna regressione nei test esistenti di `plan_all()` e `run_all()`**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "plan_all or run_all or bootstrap or teardown" -v 2>&1 | tail -15
```
Expected: tutti passano. I test esistenti accedono `plans[0].steps`, `plans[0].request`, `plans[0].scenario.name` — disponibili su tutti i builder.

**Se `test_run_all_bootstraps_vm_once_and_reuses_it` fallisce:** questo test usa `runner._planner._k3s_curl_runner`. Il builder avvolge gli step da `_planner.vm_backed_steps()` che internamente usa la closure di `_k3s_curl_runner` — monkeypatchato prima della chiamata. Deve funzionare. Se fallisce, leggi l'errore e verifica che `plan.run()` è ancora corretto.

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/tests/test_e2e_runner.py && git commit -m "$(cat <<'EOF'
feat: plan_all() returns typed builders for k3s-junit-curl, helm-stack, cli-stack

Wraps _planner.vm_backed_steps() output in typed builders, preserving the
include_bootstrap optimization. plan_all() now returns typed builders for
all 5 VM scenarios.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `run_all()` dispatcha builder plans via `plan.run()`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:660–661`
**Test:** `tools/controlplane/tests/test_e2e_runner.py`

**Contesto:** Dopo Task 1, tutti i piani VM sono builder. `run_all()` chiama ancora `_execute_steps(plan)` per tutti — funziona via duck typing ma bypassa `plan.run()`. Questo task lo allinea con `run()`.

- [ ] **Step 1: Aggiungi test che verifica il dispatch**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_run_all_dispatches_builder_plans_via_plan_run(tmp_path: Path) -> None:
    """run_all() must call plan.run() for builder plans, not _execute_steps directly."""
    from unittest.mock import patch
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan

    run_called: list[str] = []

    def capturing_run(self_plan, event_listener=None):
        run_called.append(type(self_plan).__name__)

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    runner._planner._k3s_curl_runner = lambda request: type(  # type: ignore[assignment]
        "_Verifier",
        (),
        {"verify_existing_stack": staticmethod(lambda resolved: None)},
    )()

    with patch.object(K3sJunitCurlPlan, "run", capturing_run):
        runner.run_all(only=["k3s-junit-curl"])

    assert "K3sJunitCurlPlan" in run_called, (
        "run_all() must call plan.run() for K3sJunitCurlPlan, not _execute_steps directly"
    )
```

- [ ] **Step 2: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_all_dispatches_builder_plans_via_plan_run -v 2>&1 | tail -6
```
Expected: `AssertionError: run_all() must call plan.run() for K3sJunitCurlPlan`.

- [ ] **Step 3: Aggiorna il loop in `run_all()`**

In `e2e_runner.py`, trova (riga ~660):

```python
        try:
            for plan in plans:
                self._execute_steps(plan)
            succeeded = True
```

Sostituisci con:

```python
        try:
            from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
            from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
            from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
            from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
            _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)
            for plan in plans:
                if isinstance(plan, _BUILDER_TYPES):
                    plan.run()
                else:
                    self._execute_steps(plan)
            succeeded = True
```

- [ ] **Step 4: Verifica che il nuovo test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_all_dispatches_builder_plans_via_plan_run -v 2>&1 | tail -5
```
Expected: `1 passed`.

- [ ] **Step 5: Verifica che i test esistenti di `run_all()` passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "run_all or bootstrap or teardown" -v 2>&1 | tail -10
```

**`test_run_all_bootstraps_vm_once_and_reuses_it`** funziona perché:
- `plan_all()` ritorna `K3sJunitCurlPlan` con steps da `_planner.vm_backed_steps()`
- `run_all()` chiama `plan.run()` → `runner._execute_steps(legacy)` dove `legacy.steps` sono gli stessi
- La closure per `tests.run_k3s_curl_checks` usa il verifier monkeypatchato (catturato a build-time)

**`test_run_all_tears_down_vm_when_cleanup_vm_true`** funziona perché il teardown avviene nel `finally` block di `run_all()` — invariato.

- [ ] **Step 6: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1061+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/tests/test_e2e_runner.py && git commit -m "$(cat <<'EOF'
feat: run_all() dispatches builder plans via plan.run()

Aligns run_all() execution with run() — builder plans use plan.run()
instead of _execute_steps() directly. Local plans (docker, buildpack, etc.)
continue using _execute_steps() as before.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -q 2>&1 | tail -5
```

Dopo questo piano:
- `plan_all()` restituisce builder tipizzati per TUTTI e 5 gli scenari VM recipe-based
- `run_all()` usa `plan.run()` per tutti i builder (con event_listener forwarding se/quando aggiunto)
- Il `ScenarioPlanner` è ancora usato per generare i step in `plan_all()` (non rimosso in questo piano)

---

## Note su Piano 9

Piano 9 (futuro) potrà considerare:
- Rimozione di `ScenarioPlanner.vm_backed_steps()` per gli scenari recipe (k3s/helm/cli-stack) da `plan_all()` — richiede builder che supportino `include_bootstrap=False`
- Rimozione di `ScenarioPlanner` se `plan_all()` è completamente migrato ai builder
- Rimozione di `plan_recipe_steps` da `e2e_runner.py` (ancora usato dai factory dei builder internamente)
- Migrazione di `cli_stack_runner.py` da `plan_recipe_steps` diretto a builder
