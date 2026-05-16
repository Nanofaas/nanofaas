# Workflow Task Composition — Piano 7: Event Listener Forwarding

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correggere il gap funzionale per cui `E2eRunner.run()` chiama `plan.run()` senza inoltrare `event_listener`, rendendo la TUI cieca ai progressi di passo per tutti i 5 scenari recipe (two-vm-loadtest, azure-vm-loadtest, k3s-junit-curl, helm-stack, cli-stack).

**Architecture:** Due cambiamenti coordinati: (1) tutti e 5 i builder acquisiscono un parametro opzionale `event_listener=None` che passano a `runner._execute_steps(legacy, event_listener=event_listener)`; (2) `E2eRunner.run()` cambia da `plan.run()` a `plan.run(event_listener=event_listener)`. Il Protocol `ScenarioPlan` viene aggiornato per riflettere la nuova firma. Nessun altro file cambia.

**Tech Stack:** Python 3.11+, dataclasses, `ScenarioPlan` Protocol. 7 file toccati (5 builder + `e2e_runner.py` + `scenarios/__init__.py`).

---

## Background — il gap

In `E2eRunner.run()` (riga 624–625):

```python
if isinstance(plan, (TwoVmLoadtestPlan, ...)):
    plan.run()                              # event_listener silenziosamente scartato
else:
    self.execute(plan, event_listener=event_listener)  # solo il vecchio ScenarioPlan lo riceve
```

I builder (`TwoVmLoadtestPlan`, ecc.) hanno `run(self) -> None` — non accettano `event_listener`. Quindi la TUI (che passa `event_listener=events.append`) non riceve alcun evento per i 5 scenari recipe.

Dopo il fix, la catena diventa:
```
E2eRunner.run(request, event_listener=listener)
  └─ plan.run(event_listener=listener)          ← fix Task 1
       └─ runner._execute_steps(legacy, event_listener=listener)   ← fix Task 1
            └─ runner._execute_step(...)         (già emette eventi)
```

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py` — Protocol aggiornato: `run(self, event_listener=None) -> None`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py:31` — `run()` + forwarding
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py:31` — stesso
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py:31` — stesso
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py:31` — stesso
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py:31` — stesso
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:625` — `plan.run()` → `plan.run(event_listener=event_listener)`

**Test:**
- `tools/controlplane/tests/test_scenario_builders.py` — test di forwarding su TwoVmLoadtestPlan
- `tools/controlplane/tests/test_e2e_runner.py` — test end-to-end forwarding via E2eRunner.run()

---

## Task 1: Aggiungi `event_listener` ai builder e aggiorna il Protocol

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py`
- Modify: tutti e 5 i file builder in `scenario/scenarios/`
- Test: `tools/controlplane/tests/test_scenario_builders.py`

- [ ] **Step 1: Leggi i file rilevanti**

```bash
cat tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py
sed -n '31,38p' tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py
```

- [ ] **Step 2: Aggiungi test che fallisce (TypeError prima del fix)**

Leggi `tools/controlplane/tests/test_scenario_builders.py`, poi aggiungi in fondo:

```python
def test_two_vm_loadtest_plan_run_forwards_event_listener() -> None:
    """Builder run() must accept and forward event_listener to _execute_steps."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep
    from unittest.mock import MagicMock

    captured: dict = {}

    mock_runner = MagicMock()
    mock_runner._execute_steps.side_effect = (
        lambda plan, event_listener=None: captured.update({"event_listener": event_listener})
    )

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="test.step")
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(), request=MagicMock(), steps=[step], runner=mock_runner
    )
    listener = lambda event: None

    plan.run(event_listener=listener)

    assert captured["event_listener"] is listener
```

- [ ] **Step 3: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_two_vm_loadtest_plan_run_forwards_event_listener -v 2>&1 | tail -8
```
Expected: `TypeError: TwoVmLoadtestPlan.run() got an unexpected keyword argument 'event_listener'`.

- [ ] **Step 4: Aggiorna il Protocol in `scenarios/__init__.py`**

Sostituisci:
```python
    def run(self) -> None: ...
```
con:
```python
    def run(self, event_listener=None) -> None: ...
```

- [ ] **Step 5: Aggiorna `run()` in tutti e 5 i builder**

Per ciascuno dei 5 file (`two_vm_loadtest.py`, `azure_vm_loadtest.py`, `k3s_junit_curl.py`, `helm_stack.py`, `cli_stack.py`), sostituisci:

```python
    def run(self) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy)
```

con:

```python
    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy, event_listener=event_listener)
```

**Nota:** L'unica differenza tra i 5 file è la riga `def run(self) -> None:` → `def run(self, event_listener=None) -> None:` e l'aggiunta di `event_listener=event_listener` nella chiamata `_execute_steps`. Il blocco `legacy = ScenarioPlan(...)` rimane identico.

- [ ] **Step 6: Verifica che il test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_two_vm_loadtest_plan_run_forwards_event_listener -v 2>&1 | tail -5
```
Expected: `1 passed`.

- [ ] **Step 7: Verifica tutti i test esistenti sui builder**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -v 2>&1 | tail -12
```
Expected: tutti passano (incluso il nuovo).

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
feat: builder run() methods accept and forward event_listener to _execute_steps

All 5 scenario builders now forward event_listener so the TUI can receive
step progress events. Protocol updated to reflect new optional parameter.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Aggiorna `E2eRunner.run()` per inoltrare `event_listener`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:625`
**Test:** `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Leggi il contesto**

```bash
sed -n '618,630p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
```

Verifica di vedere `plan.run()` senza argomenti.

- [ ] **Step 2: Aggiungi test che fallisce**

Leggi le ultime righe di `tools/controlplane/tests/test_e2e_runner.py`, poi aggiungi in fondo:

```python
def test_e2e_runner_run_forwards_event_listener_to_builder_plan(tmp_path: Path) -> None:
    """E2eRunner.run() must forward event_listener when dispatching to a builder plan."""
    from unittest.mock import patch

    captured: dict = {}
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    listener = lambda event: None  # noqa: E731

    with patch.object(
        runner,
        "_execute_steps",
        side_effect=lambda plan, event_listener=None: captured.update({"event_listener": event_listener}),
    ):
        runner.run(request, event_listener=listener)

    assert captured.get("event_listener") is listener, (
        "event_listener was not forwarded to _execute_steps — run() must call plan.run(event_listener=event_listener)"
    )
```

- [ ] **Step 3: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_e2e_runner_run_forwards_event_listener_to_builder_plan -v 2>&1 | tail -8
```
Expected: `AssertionError: event_listener was not forwarded` (perché `plan.run()` viene chiamato senza `event_listener`, e `_execute_steps` riceve `None`).

- [ ] **Step 4: Applica il fix in `e2e_runner.py`**

Trova (riga ~624–625):
```python
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)):
            plan.run()
```

Sostituisci con:
```python
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)):
            plan.run(event_listener=event_listener)
```

**Un solo carattere in più: `(event_listener=event_listener)`.**

- [ ] **Step 5: Verifica che il test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_e2e_runner_run_forwards_event_listener_to_builder_plan -v 2>&1 | tail -5
```
Expected: `1 passed`.

- [ ] **Step 6: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1057+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
fix: E2eRunner.run() now forwards event_listener to builder plan.run()

Fixes silent event_listener loss for all 5 recipe scenarios.
TUI step progress events now fire for two-vm-loadtest, azure-vm-loadtest,
k3s-junit-curl, helm-stack, and cli-stack.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_e2e_runner.py -q 2>&1 | tail -5
```

Dopo questo piano:
- `plan.run(event_listener=listener)` è supportato da tutti e 5 i builder
- `E2eRunner.run()` lo inoltra correttamente
- Il Protocol riflette la nuova firma
- La TUI riceve eventi per tutti gli scenari recipe

---

## Note su Piano 8

Piano 8 (futuro) potrà considerare:
- Rimozione di `plan_recipe_steps` da `e2e_runner.py` (ancora usato dai builder factory internamente)
- Migrazione di k3s-junit-curl / helm-stack / cli-stack in `plan_all()` ai builder tipizzati (richiede supporto `include_bootstrap=False` nei builder)
- Rimozione di `ScenarioPlanner.vm_backed_steps` per gli scenari recipe (k3s, helm, cli-stack) una volta migrato `plan_all()`
