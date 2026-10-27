# Offload Compose Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the local offload load-test persist edge registrations on a fresh named volume and prevent the cloud control plane from rejecting initial offloaded traffic through sync-queue admission.

**Architecture:** Seed `/var/lib/nanofaas` in the shared Compose image with `nonroot` ownership, matching the existing JVM and native image pattern, so a fresh Docker volume inherits writable ownership without running the edge as root. Keep `sync-queue` compiled into both control planes, but disable it at runtime only on the cloud service so offloaded synchronous calls dispatch directly; the edge retains its depth-based pressure behavior.

**Tech Stack:** Docker Compose, multi-stage Dockerfile, Spring Boot relaxed environment binding, pytest contract tests, NanoLab local offload scenario.

---

### Task 1: Seed a writable edge registry directory

**Files:**
- Modify: `deploy/compose/Dockerfile:17-46`
- Modify: `scripts/tests/test_offload_loadtest_compose.py`

**Step 1: Write the failing ownership contract test**

Add the Dockerfile path and a focused test:

```python
DOCKERFILE = COMPOSE.parent / "Dockerfile"


def test_offload_image_seeds_registry_directory_for_nonroot_runtime():
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")

    assert "mkdir -p /var/lib/nanofaas" in dockerfile
    assert (
        "COPY --from=build --chown=nonroot:nonroot "
        "/var/lib/nanofaas /var/lib/nanofaas"
    ) in dockerfile
```

This tests the image contract rather than adding `user: "0:0"` to the edge. The cloud remains root because it needs the Docker socket; the edge does not.

**Step 2: Run the test and verify RED**

Run:

```bash
pytest -q scripts/tests/test_offload_loadtest_compose.py::test_offload_image_seeds_registry_directory_for_nonroot_runtime
```

Expected: FAIL because `deploy/compose/Dockerfile` does not yet create or copy `/var/lib/nanofaas`.

**Step 3: Implement the minimal image fix**

In the build stage, after the Gradle build, create a non-empty directory so Docker seeds a fresh named volume:

```dockerfile
RUN mkdir -p /var/lib/nanofaas && touch /var/lib/nanofaas/.keep
```

In the runtime stage, copy it with the runtime user's ownership:

```dockerfile
COPY --from=build --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas
```

Do not change either service user.

**Step 4: Run the focused test and verify GREEN**

Run:

```bash
pytest -q scripts/tests/test_offload_loadtest_compose.py::test_offload_image_seeds_registry_directory_for_nonroot_runtime
```

Expected: PASS.

**Step 5: Commit the isolated fix**

```bash
git add deploy/compose/Dockerfile scripts/tests/test_offload_loadtest_compose.py
git commit -m "Make offload registry volume writable"
```

### Task 2: Disable the cloud sync queue

**Files:**
- Modify: `deploy/compose/offload-loadtest.yaml:32-39`
- Modify: `scripts/tests/test_offload_loadtest_compose.py`

**Step 1: Write the failing cloud configuration test**

Add a test scoped to the cloud service block:

```python
def test_offload_cloud_disables_sync_queue():
    compose = COMPOSE.read_text(encoding="utf-8")
    cloud = compose.split("  cloud-control-plane:", 1)[1].split("\nnetworks:", 1)[0]

    assert 'SYNC_QUEUE_ENABLED: "false"' in cloud
```

**Step 2: Run the test and verify RED**

Run:

```bash
pytest -q scripts/tests/test_offload_loadtest_compose.py::test_offload_cloud_disables_sync_queue
```

Expected: FAIL because the cloud currently inherits `sync-queue.enabled=true` from `application.yml`.

**Step 3: Implement the minimal cloud-only override**

Add this entry under `cloud-control-plane.environment`:

```yaml
SYNC_QUEUE_ENABLED: "false"
```

Do not remove `sync-queue` from `NANOFAAS_CONTROL_PLANE_MODULES`, and do not change the edge queue settings. Disabling the cloud gateway causes synchronous invocations to bypass queue admission and dispatch directly, matching `OffloadPressureE2eTest`.

**Step 4: Validate the Compose model and verify GREEN**

Run:

```bash
pytest -q scripts/tests/test_offload_loadtest_compose.py::test_offload_cloud_disables_sync_queue
docker compose -f deploy/compose/offload-loadtest.yaml config --quiet
```

Expected: test PASS and Compose validation exit 0.

**Step 5: Commit the isolated fix**

```bash
git add deploy/compose/offload-loadtest.yaml scripts/tests/test_offload_loadtest_compose.py
git commit -m "Disable cloud queue in offload loadtest"
```

### Task 3: Verify the complete local workflow

**Files:**
- No production changes expected.

**Step 1: Run all Compose contract tests**

```bash
pytest -q scripts/tests/test_offload_loadtest_compose.py scripts/tests/test_docker_compose_deployment.py
```

Expected: all tests PASS.

**Step 2: Run the existing offload module regression test**

```bash
./gradlew :control-plane-modules:offload:test --tests '*OffloadPressureE2eTest'
```

Expected: PASS; the cloud bypasses sync queue while the edge still offloads on depth pressure.

**Step 3: Run the real local NanoLab scenario from the NanoLab checkout**

```bash
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas \
  ./nanolab.sh run packages/nanolab/scenarios-v2/edge-cloud-offload-policy.yaml \
  --environment packages/nanolab/environments/local.yaml
```

Expected: the workflow registers functions on fresh volumes, produces offloaded cloud successes, and passes the conservation gate without startup 429/502 failures.

**Step 4: Check change scope before any final commit or handoff**

Run `gitnexus_detect_changes({scope: "all", repo: "nanofaas"})`. Confirm that only the Compose image ownership contract, the cloud runtime setting, and their tests are affected. Then run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no unrelated files staged. Preserve the existing user changes in `AGENTS.md` and `CLAUDE.md`.
