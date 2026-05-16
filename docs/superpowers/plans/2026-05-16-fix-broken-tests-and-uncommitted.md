# Fix Broken Tests and Uncommitted Changes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correggere 2 test rotti in `test_e2e_runner.py` che aspettano il vecchio step ID `cli.fn_apply_selected.*` invece del nuovo `functions.register`; committare le modifiche pendenti a `recipes.py` e `test_scenario_flows.py`.

**Architecture:** Solo aggiornamento di asserzioni nei test esistenti — nessun codice di produzione cambia. Le asserzioni ora riflettono il comportamento post-Piano 2 (`RegisterFunctions` al posto della CLI).

**Tech Stack:** pytest, `tools/controlplane/`.

---

## File Structure

**Modificati:**
- `tools/controlplane/tests/test_e2e_runner.py:249` — `cli.fn_apply_selected.echo-test` → `functions.register`
- `tools/controlplane/tests/test_e2e_runner.py:262` — summary `"Apply selected function 'echo-test'"` → `"Register selected functions via REST API"`
- `tools/controlplane/tests/test_e2e_runner.py:402–414` — logica che cerca step `cli.fn_apply_selected.*` → cerca `functions.register`

**Committati (già modificati, uncommitted):**
- `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py` — fix azure-vm-loadtest recipe (già corretto nella sessione precedente)
- `tools/controlplane/tests/test_scenario_flows.py` — test `test_azure_vm_loadtest_recipe_reuses_helm_stack_platform_prefix` (già aggiunto nella sessione precedente)

---

## Task 1: Commit le modifiche pendenti

- [ ] **Step 1: Verifica le modifiche uncommitted**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git diff --stat
```
Expected: `recipes.py` e `test_scenario_flows.py` mostrano modifiche.

- [ ] **Step 2: Verifica che i test esistenti passino con le modifiche correnti**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_flows.py -q 2>&1 | tail -5
```
Expected: tutti i test in `test_scenario_flows.py` passano.

- [ ] **Step 3: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/components/recipes.py \
        tools/controlplane/tests/test_scenario_flows.py
git commit -m "fix(azure-vm-loadtest): align recipe phases with two-vm-loadtest, add verification test"
```

---

## Task 2: Fix `test_two_vm_loadtest_plan_uses_recipe_step_ids`

**File:** `tools/controlplane/tests/test_e2e_runner.py`

**Contesto:** Il test (riga 224) verifica i `step_id` del piano prodotto da `E2eRunner.plan()` per `two-vm-loadtest`. Dopo Piano 2, `cli.fn_apply_selected.echo-test` è stato sostituito da `functions.register`. Il summary corrispondente è cambiato da `"Apply selected function 'echo-test'"` a `"Register selected functions via REST API"`.

- [ ] **Step 1: Leggi il test corrente**

```bash
sed -n '224,271p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/tests/test_e2e_runner.py
```

- [ ] **Step 2: Modifica le asserzioni**

In `test_two_vm_loadtest_plan_uses_recipe_step_ids` (riga ~249), cambia `step_ids[9:]` da:

```python
    assert step_ids[9:] == [
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "cli.fn_apply_selected.echo-test",
        "loadgen.ensure_running",
        "loadgen.provision_base",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    ]
```

a:

```python
    assert step_ids[9:] == [
        "k3s.install",
        "k3s.configure_registry",
        "namespace.install",
        "helm.deploy_control_plane",
        "helm.deploy_function_runtime",
        "cli.build_install_dist",
        "functions.register",
        "loadgen.ensure_running",
        "loadgen.provision_base",
        "loadgen.install_k6",
        "loadgen.run_k6",
        "metrics.prometheus_snapshot",
        "loadtest.write_report",
        "loadgen.down",
        "vm.down",
    ]
```

E nella stessa funzione (riga ~260), cambia `[step.summary for step in plan.steps[-10:]]` da:

```python
    assert [step.summary for step in plan.steps[-10:]] == [
        "Build nanofaas-cli installDist in VM",
        "Apply selected function 'echo-test'",
        "Ensure loadgen VM is running",
        "Provision loadgen base dependencies",
        "Install k6 on loadgen VM",
        "Run k6 from loadgen VM",
        "Capture Prometheus query snapshots",
        "Write two-VM loadtest report",
        "Tear down loadgen VM",
        "Teardown VM",
    ]
```

a:

```python
    assert [step.summary for step in plan.steps[-10:]] == [
        "Build nanofaas-cli installDist in VM",
        "Register selected functions via REST API",
        "Ensure loadgen VM is running",
        "Provision loadgen base dependencies",
        "Install k6 on loadgen VM",
        "Run k6 from loadgen VM",
        "Capture Prometheus query snapshots",
        "Write two-VM loadtest report",
        "Tear down loadgen VM",
        "Teardown VM",
    ]
```

- [ ] **Step 3: Esegui il test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_two_vm_loadtest_plan_uses_recipe_step_ids -v
```
Expected: `1 passed`

---

## Task 3: Fix `test_two_vm_loadtest_applies_functions_before_running_k6`

**File:** `tools/controlplane/tests/test_e2e_runner.py`

**Contesto:** Il test (riga 387) verifica che le funzioni vengano applicate prima di k6 e che abbiano `NANOFAAS_ENDPOINT` nell'env. Dopo Piano 2:
- Non ci sono step con `step_id.startswith("cli.fn_apply_selected.")` — c'è un solo step `functions.register`
- Lo step `functions.register` non ha `NANOFAAS_ENDPOINT` nell'env (usa REST diretto)

Il test deve essere aggiornato per verificare il nuovo comportamento: esiste uno step `functions.register` che precede `loadgen.run_k6`.

- [ ] **Step 1: Leggi il test corrente**

```bash
sed -n '387,415p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/tests/test_e2e_runner.py
```

- [ ] **Step 2: Sostituisci il corpo del test**

Sostituisci il corpo di `test_two_vm_loadtest_applies_functions_before_running_k6` con:

```python
def test_two_vm_loadtest_applies_functions_before_running_k6(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        resolved_scenario=load_scenario_file(
            Path("tools/controlplane/scenarios/two-vm-loadtest-java.toml")
        ),
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )

    plan = runner.plan(request)

    step_ids = [step.step_id for step in plan.steps]
    assert "functions.register" in step_ids
    assert "cli.build_install_dist" in step_ids
    register_index = step_ids.index("functions.register")
    k6_index = step_ids.index("loadgen.run_k6")
    assert register_index < k6_index, (
        f"functions.register (idx {register_index}) must precede loadgen.run_k6 (idx {k6_index})"
    )
    register_step = next(s for s in plan.steps if s.step_id == "functions.register")
    assert register_step.action is not None
    assert register_step.summary == "Register selected functions via REST API"
```

- [ ] **Step 3: Esegui entrambi i test riparati**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_two_vm_loadtest_plan_uses_recipe_step_ids tests/test_e2e_runner.py::test_two_vm_loadtest_applies_functions_before_running_k6 -v
```
Expected: `2 passed`

- [ ] **Step 4: Esegui la suite completa per verificare nessuna regressione**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=no 2>&1 | tail -5
```
Expected: gli unici failure sono `test_default_two_vm_k6_script_reads_payload_in_init_context` (pre-esistente, path relativo sbagliato — non causato da noi).

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/tests/test_e2e_runner.py
git commit -m "fix(tests): update e2e_runner tests for RegisterFunctions replacing cli.fn_apply_selected"
```
