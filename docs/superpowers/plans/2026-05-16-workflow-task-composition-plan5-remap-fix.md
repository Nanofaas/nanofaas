# Workflow Task Composition — Piano 5: Fix cli.fn_apply_selected Remap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correggere il bug introdotto in Piano 2 per cui `plan_recipe_steps` rimappa `cli.fn_apply_selected` → `functions.register` per TUTTI gli scenari, incluso `cli-stack` — uno scenario progettato esplicitamente per testare la CLI.

**Architecture:** Il fix è una singola condizione in `plan_recipe_steps`: il remap deve avvenire solo quando `scenario_name in {"two-vm-loadtest", "azure-vm-loadtest"}`. Dopo il fix, `CliStackPlan.task_ids` mostrerà `cli.fn_apply_selected` (corretto), e le asserzioni di test in `test_scenario_builders.py` vanno invertite. Nessun altro file produzione cambia.

**Tech Stack:** Python 3.11+, pytest. Solo `e2e_runner.py` e `test_scenario_builders.py` sono toccati.

---

## Background — il bug

In `plan_recipe_steps` (riga 277 di `e2e_runner.py`):

```python
# PRIMA (buggy) — si applica a TUTTI gli scenari:
if component.component_id == "cli.fn_apply_selected":
    component_steps = [ScenarioPlanStep(
        summary="Register selected functions via REST API",
        step_id="functions.register",
        ...
    )]

# DOPO (corretto) — solo per i due loadtest che usano REST:
if component.component_id == "cli.fn_apply_selected" and scenario_name in {"two-vm-loadtest", "azure-vm-loadtest"}:
    component_steps = [ScenarioPlanStep(...)]
```

Conseguenza del bug: quando si esegue `cli-stack`, il passo `cli fn apply` viene silenziosamente saltato e rimpiazzato con una chiamata REST. `cli-stack` è progettato per testare la CLI — questo è un test silenziosamente sbagliato.

Il fix non rompe i two-vm e azure-vm: continuano a fare il remap correttamente.

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:277` — aggiunge `and scenario_name in {"two-vm-loadtest", "azure-vm-loadtest"}` alla condizione
- `tools/controlplane/tests/test_scenario_builders.py` — inverte le asserzioni per `cli-stack`: `cli.fn_apply_selected` deve essere presente, `functions.register` non deve esserlo

---

## Task 1: Fix remap condizionale e aggiorna i test

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:277`
- Test: `tools/controlplane/tests/test_scenario_builders.py`

- [ ] **Step 1: Leggi il contesto rilevante**

```bash
sed -n '274,290p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
```

Expected output — riga critica:
```python
        if component.component_id == "cli.fn_apply_selected":
```

- [ ] **Step 2: Scrivi il test che documenta il comportamento CORRETTO**

Leggi `tools/controlplane/tests/test_scenario_builders.py`, poi aggiungi in fondo:

```python
def test_cli_stack_plan_uses_cli_fn_apply_not_rest_api(tmp_path: Path) -> None:
    """cli-stack must use CLI fn apply, not the REST API registration.

    Regression test: plan_recipe_steps must NOT remap cli.fn_apply_selected to
    functions.register for cli-stack — that remap is only for loadtest scenarios.
    """
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_cli_stack_plan(runner, _make_cli_stack_request())

    assert "cli.fn_apply_selected" in plan.task_ids, (
        "cli-stack must use the CLI for fn apply (not REST API)"
    )
    assert "functions.register" not in plan.task_ids, (
        "functions.register must not appear in cli-stack — it's a loadtest-only step"
    )
```

- [ ] **Step 3: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_cli_stack_plan_uses_cli_fn_apply_not_rest_api -v 2>&1 | tail -10
```

Expected: `AssertionError: cli-stack must use the CLI for fn apply`.

- [ ] **Step 4: Applica il fix in `plan_recipe_steps`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, riga 277, cambia:

```python
        if component.component_id == "cli.fn_apply_selected":
```

in:

```python
        if component.component_id == "cli.fn_apply_selected" and scenario_name in {"two-vm-loadtest", "azure-vm-loadtest"}:
```

**Niente altro cambia.** Il blocco indentato (`component_steps = [ScenarioPlanStep(...)]`) rimane identico.

- [ ] **Step 5: Verifica che il nuovo test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_cli_stack_plan_uses_cli_fn_apply_not_rest_api -v 2>&1 | tail -5
```

Expected: `1 passed`.

- [ ] **Step 6: Aggiorna le asserzioni obsolete in `test_scenario_builders.py`**

Nel test `test_build_cli_stack_plan_returns_correct_type`, trova e sostituisci questo blocco:

```python
    # plan_recipe_steps remaps cli.fn_apply_selected → functions.register unconditionally
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids
```

con:

```python
    # cli-stack uses CLI fn apply, not REST API (loadtest-only remap)
    assert "cli.fn_apply_selected" in plan.task_ids
    assert "functions.register" not in plan.task_ids
```

- [ ] **Step 7: Esegui test_scenario_builders.py completo**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -v 2>&1 | tail -15
```

Expected: tutti i test passano (ora 20 incluso il nuovo).

- [ ] **Step 8: Verifica no regression su two-vm-loadtest e azure-vm-loadtest**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "two_vm_loadtest or azure_vm_loadtest" -v 2>&1 | tail -10
```

Expected: tutti passano — i due loadtest continuano a usare `functions.register`.

- [ ] **Step 9: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```

Expected: 1052+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

**Se altri test falliscono:** probabilmente sono test che si aspettavano il vecchio comportamento buggy (asserted `functions.register` per cli-stack). Aggiornali nello stesso modo di Step 6.

- [ ] **Step 10: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
fix: restrict cli.fn_apply_selected remap to loadtest scenarios only

plan_recipe_steps was unconditionally remapping cli.fn_apply_selected to
functions.register, causing cli-stack to silently bypass the CLI fn apply step.
The remap now applies only to two-vm-loadtest and azure-vm-loadtest.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_e2e_runner.py -q 2>&1 | tail -5
```

Expected: tutti passano.

Dopo questo fix:
- `cli-stack` usa la CLI per `fn apply` (corretto)
- `two-vm-loadtest` usa REST API (corretto)
- `azure-vm-loadtest` usa REST API (corretto)
- `CliStackPlan.task_ids` mostra `cli.fn_apply_selected` (coerente con `scenario_task_ids("cli-stack")`)

---

## Note su Piano 6

Piano 6 (futuro) continuerà il cleanup:
- Migrazione di `plan_all()` per usare i typed builder per i due scenari loadtest
- Aggiornamento di `run_all()` per dispatchare via `plan.run()` anche per i piani di `plan_all()`
- Valutazione della rimozione di `plan_recipe_steps` una volta che tutti i caller sono migrati
- Valutazione della rimozione di `ScenarioPlanner.vm_backed_steps` per gli scenari recipe (k3s, helm, cli-stack) che ora hanno builder dedicati
