# Control-Plane Staging Campaign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement a version-driven staging and comparison system (baseline vs candidate) with global benchmark parity, multi-run median reporting, per-version image cache, and SSH-only VM command execution.

**Architecture:** Add a Python `staging-manager` orchestration layer (version registry, scaffolding, cache, campaign, promotion) and adapt existing E2E scripts to support version snapshot roots and external SSH VM lifecycle. Keep current scripts reusable; add version-aware orchestration above them.

**Tech Stack:** Python 3 (argparse/dataclasses/pathlib/json), Bash E2E scripts, pytest, Docker image metadata (`docker inspect`), existing k6/Helm/k3s scripts.

---

### Task 1: Bootstrap `staging-manager` CLI skeleton

**Files:**
- Create: `experiments/staging_manager.py`
- Test: `experiments/tests/test_staging_manager_cli.py`

**Step 1: Write the failing test**

```python
def test_staging_manager_help_lists_commands():
    proc = subprocess.run(
        ["python3", "experiments/staging_manager.py", "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "create-version" in proc.stdout
    assert "build-images" in proc.stdout
    assert "run-campaign" in proc.stdout
    assert "promote" in proc.stdout
```

**Step 2: Run test to verify it fails**

Run: `python3 -m pytest -q experiments/tests/test_staging_manager_cli.py::test_staging_manager_help_lists_commands`  
Expected: FAIL (missing script/commands).

**Step 3: Write minimal implementation**

Create CLI with argparse subcommands (`create-version`, `build-images`, `run-campaign`, `promote`) returning 0.

**Step 4: Run test to verify it passes**

Run: `python3 -m pytest -q experiments/tests/test_staging_manager_cli.py::test_staging_manager_help_lists_commands`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging_manager.py experiments/tests/test_staging_manager_cli.py
git commit -m "Add staging-manager CLI skeleton"
```

### Task 2: Add staging filesystem/metadata primitives

**Files:**
- Create: `experiments/staging/model.py`
- Create: `experiments/staging/io.py`
- Test: `experiments/tests/test_staging_model_io.py`

**Step 1: Write failing tests**

- parse/save `version.yaml`
- validate required fields (`slug`, `kind`, `status`, `parent`, `created_at`)
- reject unknown status.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_model_io.py`  
Expected: FAIL (module not implemented).

**Step 3: Implement minimal code**

- dataclass-like model helpers
- YAML read/write (via `yaml` if present; fallback: JSON-compatible parser is acceptable only if tests use YAML serializer)
- status enum validation.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_model_io.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/model.py experiments/staging/io.py experiments/tests/test_staging_model_io.py
git commit -m "Add staging version metadata IO and validation"
```

### Task 3: Implement `create-version` scaffolding with lineage

**Files:**
- Modify: `experiments/staging_manager.py`
- Create: `experiments/staging/scaffold.py`
- Test: `experiments/tests/test_staging_create_version.py`

**Step 1: Write failing tests**

- `--from baseline` copies baseline snapshot into new slug
- `--from version:<slug>` copies source version snapshot
- `--from none` creates standalone empty snapshot scaffold
- always creates `version.yaml` + `hypothesis.md`
- `kind` is always `generic-service`.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_create_version.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- add command handler
- copy directory trees
- create template `hypothesis.md` sections:
  - Context
  - Differences from parent
  - Hypotheses
  - Risks
  - Expected impact.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_create_version.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging_manager.py experiments/staging/scaffold.py experiments/tests/test_staging_create_version.py
git commit -m "Implement create-version scaffolding with lineage modes"
```

### Task 4: Add immutable benchmark loader and validator

**Files:**
- Create: `experiments/staging/benchmark.py`
- Test: `experiments/tests/test_staging_benchmark_validation.py`

**Step 1: Write failing tests**

- load benchmark from `experiments/control-plane-staging/benchmark/benchmark.yaml`
- require `function_profile` (`all` default accepted)
- require `platform_modes` contains `jvm` and `native`
- reject malformed benchmark.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_benchmark_validation.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- strict validator with clear error messages
- normalized benchmark object for campaign runner.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_benchmark_validation.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/benchmark.py experiments/tests/test_staging_benchmark_validation.py
git commit -m "Add benchmark contract validation for staging campaigns"
```

### Task 5: Add per-version image cache contract and force rebuild policy

**Files:**
- Create: `experiments/staging/image_cache.py`
- Modify: `experiments/staging_manager.py`
- Test: `experiments/tests/test_staging_image_cache.py`

**Step 1: Write failing tests**

- cache hit when manifest + docker image id match
- cache miss when fingerprint/image missing/id mismatch
- `--force-rebuild-images` bypasses cache
- mode-level force (`--force-rebuild-mode`).

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_image_cache.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- `manifest.json` schema per mode (`jvm`, `native`)
- fingerprint helpers (`snapshot_fingerprint`, `build_fingerprint`)
- dry command builder for build execution.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_image_cache.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/image_cache.py experiments/staging_manager.py experiments/tests/test_staging_image_cache.py
git commit -m "Implement per-version image cache with force rebuild controls"
```

### Task 6: Make E2E scripts version-root aware

**Files:**
- Modify: `scripts/e2e-k3s-helm.sh`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Test: `scripts/tests/test_e2e_k3s_helm_control_plane_native.py`
- Test: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`

**Step 1: Write failing tests**

- support `E2E_REMOTE_PROJECT_DIR`/dynamic remote root
- no hardcoded `/home/ubuntu/.kube/config`
- external lifecycle messages + ssh checks.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- root path indirection for all remote build/deploy commands
- lifecycle-aware behavior in common lib.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e-k3s-helm.sh scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_k3s_common_external_ssh_mode.py
git commit -m "Make E2E stack version-root aware and SSH lifecycle safe"
```

### Task 7: Implement `run-campaign` matrix orchestration (N runs)

**Files:**
- Modify: `experiments/staging_manager.py`
- Create: `experiments/staging/campaign.py`
- Test: `experiments/tests/test_staging_campaign_matrix.py`

**Step 1: Write failing tests**

- matrix expansion per run: baseline/candidate × jvm/native
- run count parameter respected (`--runs 10`)
- campaign directories and run logs created deterministically
- benchmark copied and hash pinned in campaign output.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_campaign_matrix.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- orchestrator with pluggable command executor (for tests)
- write campaign metadata JSON
- call existing deployment/loadtest flows with version root and mode variables.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_campaign_matrix.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/campaign.py experiments/staging_manager.py experiments/tests/test_staging_campaign_matrix.py
git commit -m "Add campaign runner with baseline/candidate jvm-native matrix"
```

### Task 8: Implement aggregate reporting with medians and variability

**Files:**
- Create: `experiments/staging/report.py`
- Modify: `experiments/staging_manager.py`
- Test: `experiments/tests/test_staging_campaign_aggregate.py`

**Step 1: Write failing tests**

- aggregate median/mean/min/max from campaign run outputs
- include per-metric baseline/candidate/delta
- deterministic markdown table generation.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_campaign_aggregate.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- JSON aggregator + markdown writer
- explicit metrics list (`p95`, `p99`, `fail_rate`, `throughput`, `heap_peak`, `gc_pause`).

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_campaign_aggregate.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/report.py experiments/staging_manager.py experiments/tests/test_staging_campaign_aggregate.py
git commit -m "Add campaign aggregate reporting with median-first outputs"
```

### Task 9: Implement `promote` state transition and baseline rollover

**Files:**
- Modify: `experiments/staging_manager.py`
- Create: `experiments/staging/promotion.py`
- Test: `experiments/tests/test_staging_promotion.py`

**Step 1: Write failing tests**

- promote candidate to baseline
- archive previous baseline
- reject invalid transitions (`staging -> baseline` without promotion path)
- keep campaign reference in promotion metadata.

**Step 2: Run tests to verify failure**

Run: `python3 -m pytest -q experiments/tests/test_staging_promotion.py`  
Expected: FAIL.

**Step 3: Implement minimal code**

- transactional metadata updates (write temp + atomic rename)
- explicit error messages.

**Step 4: Run tests to verify pass**

Run: `python3 -m pytest -q experiments/tests/test_staging_promotion.py`  
Expected: PASS.

**Step 5: Commit**

```bash
git add experiments/staging/promotion.py experiments/staging_manager.py experiments/tests/test_staging_promotion.py
git commit -m "Implement promotion workflow and baseline rollover states"
```

### Task 10: End-to-end verification and docs

**Files:**
- Modify: `experiments/tests/test_e2e_memory_ab_batch_cli.py`
- Modify: `experiments/tests/test_e2e_loadtest_cli.py`
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/testing.md`

**Step 1: Add/adjust failing integration tests**

- CLI smoke:
  - `create-version`
  - `build-images --force-rebuild-images`
  - `run-campaign --runs 10`
- verify no docs recommend `multipass exec/shell` for command execution.

**Step 2: Run tests to verify failure**

Run:  
`python3 -m pytest -q experiments/tests/test_e2e_memory_ab_batch_cli.py experiments/tests/test_e2e_loadtest_cli.py`  
Expected: FAIL.

**Step 3: Implement docs + final wiring**

- update docs to SSH-first execution model
- add examples for `E2E_VM_LIFECYCLE=external`.

**Step 4: Full verification**

Run:

```bash
bash -n scripts/lib/e2e-k3s-common.sh scripts/e2e-k3s-helm.sh experiments/e2e-loadtest.sh experiments/e2e-memory-ab.sh experiments/e2e-memory-ab-batch.sh
python3 -m pytest -q scripts/tests experiments/tests
```

Expected: PASS.

**Step 5: Commit**

```bash
git add docs/e2e-tutorial.md docs/testing.md experiments/tests/test_e2e_memory_ab_batch_cli.py experiments/tests/test_e2e_loadtest_cli.py
git commit -m "Document and verify staging campaign workflow end to end"
```

## Rollout Notes

- Start with local lifecycle (`E2E_VM_LIFECYCLE=multipass`) for deterministic CI reproduction.
- Use external SSH lifecycle for remote clusters without changing campaign semantics.
- Keep benchmark immutable within a campaign by copying file + hash at campaign start.
