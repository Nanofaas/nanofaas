# Go SDK Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the new Go function SDK a first-class runtime/demo in NanoFaaS packaging, Helm deployment, release tooling, and E2E flows.

**Architecture:** Extend the existing demo/runtime matrix consistently rather than bolting Go support onto one path at a time. The work should update the image catalogs, release/build scripts, Helm demo registration, k3s Helm E2E orchestration, and the documentation/runtime matrix so Go is treated like the existing Java/Python/Bash examples. Prefer reusing the current workload/runtime abstraction in shell scripts instead of introducing Go-specific branches everywhere.

**Tech Stack:** Bash, Python, Helm templates/values, Gradle wrapper orchestration, Docker image builds, pytest for script tests, Markdown docs.

---

## Scope assumptions

- The Go SDK is now intended to be an officially supported demo/runtime path, not just a repository-local example.
- The two canonical Go demos are `examples/go/word-stats` and `examples/go/json-transform`.
- Go demo images should be published to the same GHCR namespace and versioned like the other demo images.
- Helm demo registration should include Go functions by default unless the team explicitly decides otherwise.
- If Go is not yet meant to participate in load testing, document that exclusion explicitly instead of silently omitting it.

### Task 1: Add Go demo images to the image catalog and catalog tests

**Files:**
- Modify: `scripts/image-builder/image_builder.py`
- Modify: `scripts/image-builder/tests/test_image_builder.py`

**Step 1: Write the failing test**

Extend the expected image set in `scripts/image-builder/tests/test_image_builder.py` with:

```python
"go-word-stats",
"go-json-transform",
```

and increase the expected count accordingly.

**Step 2: Run test to verify it fails**

Run: `uv run pytest scripts/image-builder/tests/test_image_builder.py -q`
Expected: FAIL because `IMAGES` does not contain the Go entries.

**Step 3: Write minimal implementation**

Add two `docker` image entries in `scripts/image-builder/image_builder.py`:

```python
"go-word-stats": {
    "type": "docker",
    "dockerfile": "examples/go/word-stats/Dockerfile",
    "context": ".",
    "group": "Go Functions",
},
"go-json-transform": {
    "type": "docker",
    "dockerfile": "examples/go/json-transform/Dockerfile",
    "context": ".",
    "group": "Go Functions",
},
```

**Step 4: Run test to verify it passes**

Run: `uv run pytest scripts/image-builder/tests/test_image_builder.py -q`
Expected: PASS

**Step 5: Commit**

```bash
git add scripts/image-builder/image_builder.py scripts/image-builder/tests/test_image_builder.py
git commit -m "Add Go demo images to image builder catalog"
```

### Task 2: Extend the build/push and release flows for Go demo images

**Files:**
- Modify: `scripts/build-push-images.sh`
- Modify: `scripts/release-manager/release.py`

**Step 1: Write the failing test or executable expectation**

For shell/Python release tooling, define the desired behavior first in comments or assertions:

- `scripts/build-push-images.sh --help` must mention a Go target or a demo target that includes Go.
- `scripts/release-manager/release.py` must build and push `go-word-stats` and `go-json-transform`.

If a dedicated test file is easy to add under `scripts/tests/`, do that; otherwise verify via grep-based expectations after implementation.

**Step 2: Run verification to confirm the gap exists**

Run:

```bash
rg -n "go-word-stats|go-json-transform|go-demos" scripts/build-push-images.sh scripts/release-manager/release.py
```

Expected: no Go image build/push logic found.

**Step 3: Write minimal implementation**

In `scripts/build-push-images.sh`:

- extend `--only` help text to include Go demos
- add a Go demo build section:

```bash
for example in word-stats json-transform; do
  IMG="${BASE}/go-${example}:${TAG}${TAG_SUFFIX}"
  docker build ... -t "$IMG" -f "examples/go/${example}/Dockerfile" .
done
```

In `scripts/release-manager/release.py`:

- add a Go demo loop mirroring Python/Bash
- tag images as:

```python
f"{base_image}/go-{example}:{tag}-arm64"
```

**Step 4: Run verification**

Run:

```bash
rg -n "go-word-stats|go-json-transform|go-" scripts/build-push-images.sh scripts/release-manager/release.py
```

Expected: Go build/push logic is present in both files.

**Step 5: Commit**

```bash
git add scripts/build-push-images.sh scripts/release-manager/release.py
git commit -m "Add Go demo images to release tooling"
```

### Task 3: Add Go demos to Helm defaults and user-facing post-install guidance

**Files:**
- Modify: `helm/nanofaas/values.yaml`
- Modify: `helm/nanofaas/templates/NOTES.txt`

**Step 1: Write the failing configuration expectation**

Document the desired Helm demo entries:

- `word-stats-go`
- `json-transform-go`

with image references under `ghcr.io/miciav/nanofaas/`.

**Step 2: Verify the gap**

Run:

```bash
rg -n "word-stats-go|json-transform-go" helm/nanofaas/values.yaml helm/nanofaas/templates/NOTES.txt
```

Expected: no matches.

**Step 3: Write minimal implementation**

In `helm/nanofaas/values.yaml`, append two entries under `demos.functions`:

```yaml
- name: word-stats-go
  image: ghcr.io/miciav/nanofaas/go-word-stats:v0.16.1
  timeoutMs: 10000
  concurrency: 2
  queueSize: 50
  maxRetries: 3
  executionMode: DEPLOYMENT
  scalingConfig:
    strategy: INTERNAL
    minReplicas: 1
    maxReplicas: 5
    metrics:
      - type: in_flight
        target: "2"
```

and the analogous `json-transform-go` entry.

In `helm/nanofaas/templates/NOTES.txt`, either:

- switch the example invoke to `word-stats-go`, or
- reword it generically so it does not privilege Java.

**Step 4: Verify**

Run:

```bash
helm template nanofaas helm/nanofaas >/tmp/nanofaas-helm-render.yaml
rg -n "word-stats-go|json-transform-go" /tmp/nanofaas-helm-render.yaml helm/nanofaas/templates/NOTES.txt
```

Expected: rendered Helm output includes both Go demos and NOTES mention valid examples.

**Step 5: Commit**

```bash
git add helm/nanofaas/values.yaml helm/nanofaas/templates/NOTES.txt
git commit -m "Add Go demos to Helm defaults"
```

### Task 4: Extend k3s Helm E2E orchestration to understand the Go runtime/demo matrix

**Files:**
- Modify: `scripts/e2e-k3s-helm.sh`

**Step 1: Write the failing expectation**

Decide the required Go runtime label. Use `go` for symmetry with `java`, `java-lite`, `python`, `exec`.

Target behavior:

- `LOADTEST_RUNTIMES` default includes `go`
- `allowed_runtimes` includes `go`
- demo function naming accepts `go`
- YAML generation can emit `word-stats-go` and `json-transform-go`
- host-build and VM-build phases can build/push Go images

**Step 2: Verify the current script is missing Go**

Run:

```bash
rg -n "LOADTEST_RUNTIMES|allowed_runtimes|word-stats-go|json-transform-go|GO_" scripts/e2e-k3s-helm.sh
```

Expected: runtime matrix mentions only `java`, `java-lite`, `python`, `exec`.

**Step 3: Write minimal implementation**

Make these concrete changes:

- add image variables near the top:

```bash
GO_WORD_STATS_IMAGE="${LOCAL_REGISTRY}/nanofaas/go-word-stats:${TAG}"
GO_JSON_TRANSFORM_IMAGE="${LOCAL_REGISTRY}/nanofaas/go-json-transform:${TAG}"
```

- extend runtime normalization:

```bash
local allowed_runtimes=("java" "java-lite" "python" "exec" "go")
```

- extend `demo_function_name` case arm for `go`
- extend `build_demo_functions_yaml` with `word-stats-go` and `json-transform-go`
- extend host-build sections with:

```bash
docker build -t "${GO_WORD_STATS_IMAGE}" -f examples/go/word-stats/Dockerfile .
docker build -t "${GO_JSON_TRANSFORM_IMAGE}" -f examples/go/json-transform/Dockerfile .
```

- extend VM build sections with the same Dockerfiles
- extend image export/load/push arrays to include Go images

**Step 4: Run a targeted dry-run or shell-level verification**

Run:

```bash
DRY_RUN=true LOADTEST_RUNTIMES=go ./scripts/e2e-all.sh
rg -n "go-word-stats|go-json-transform|allowed_runtimes=.*go|LOADTEST_RUNTIMES=.*go" scripts/e2e-k3s-helm.sh
```

Expected: dry-run path accepts Go without validation errors, and the script contains Go build/demo wiring.

**Step 5: Commit**

```bash
git add scripts/e2e-k3s-helm.sh
git commit -m "Add Go runtime support to k3s Helm E2E"
```

### Task 5: Update script tests that encode the demo/runtime matrix

**Files:**
- Modify: `scripts/tests/test_e2e_k3s_helm_control_plane_native.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Optional create/modify: additional `scripts/tests/*` if you add new helper logic

**Step 1: Write the failing test**

Add assertions that the shell script now contains Go runtime/demo references, for example:

```python
assert 'local allowed_runtimes=("java" "java-lite" "python" "exec" "go")' in script
assert 'go-word-stats' in script
assert 'go-json-transform' in script
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py -q
```

Expected: FAIL before the script is updated.

**Step 3: Write minimal implementation**

Update the assertions to match the extended runtime matrix and any changed helper names/messages.

**Step 4: Run tests**

Run:

```bash
uv run pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: PASS

**Step 5: Commit**

```bash
git add scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_runtime_runners.py
git commit -m "Update script tests for Go demo matrix"
```

### Task 6: Update architecture/tutorial/load-test documentation to include Go

**Files:**
- Modify: `docs/e2e-tutorial.md`
- Modify: `docs/function-pod-architecture.md`
- Modify: `docs/observability.md`
- Modify: `docs/loadtest-payload-profile.md`

**Step 1: Write the failing documentation checklist**

Create a short checklist and verify that each file currently omits or misstates Go support:

- demo counts
- supported runtime matrix
- container/image examples
- performance tables
- observability semantics

**Step 2: Verify omissions**

Run:

```bash
rg -n "word-stats-go|json-transform-go|Go SDK|go runtime|java-lite|python|exec" docs/e2e-tutorial.md docs/function-pod-architecture.md docs/observability.md docs/loadtest-payload-profile.md
```

Expected: Go is absent or only partially mentioned.

**Step 3: Write minimal implementation**

Update:

- `docs/e2e-tutorial.md`
  - image/demo counts
  - listed registered functions
  - diagrams/tables that enumerate runtimes
  - any performance table only if Go is actually benchmarked; otherwise state it is not yet included
- `docs/function-pod-architecture.md`
  - add Go SDK runtime case
  - explain embedded HTTP runtime and callback path
- `docs/observability.md`
  - add Go runtime notes alongside Java
- `docs/loadtest-payload-profile.md`
  - if Go is not benchmarked, say so explicitly
  - if it is benchmarked, add the Go workload script references

**Step 4: Run verification**

Run:

```bash
rg -n "word-stats-go|json-transform-go|Go SDK|Go runtime" docs/e2e-tutorial.md docs/function-pod-architecture.md docs/observability.md
```

Expected: the runtime/docs matrix now includes Go intentionally.

**Step 5: Commit**

```bash
git add docs/e2e-tutorial.md docs/function-pod-architecture.md docs/observability.md docs/loadtest-payload-profile.md
git commit -m "Document Go SDK as a supported runtime path"
```

### Task 7: Run end-to-end verification of the new first-class Go matrix

**Files:**
- No code changes required unless verification exposes gaps

**Step 1: Run the focused test suites**

Run:

```bash
uv run pytest scripts/image-builder/tests/test_image_builder.py -q
uv run pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: PASS

**Step 2: Run Helm render verification**

Run:

```bash
helm template nanofaas helm/nanofaas >/tmp/nanofaas-helm-render.yaml
rg -n "word-stats-go|json-transform-go" /tmp/nanofaas-helm-render.yaml
```

Expected: both Go demo functions appear.

**Step 3: Run the existing Go SDK and example tests**

Run:

```bash
cd function-sdk-go && go test ./...
cd examples/go/word-stats && go test ./...
cd examples/go/json-transform && go test ./...
```

Expected: PASS

**Step 4: Run the relevant integration/E2E suites**

At minimum:

```bash
./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.SdkExamplesE2eTest -PrunE2e=true --rerun-tasks
```

If the Go demos are added to k3s Helm demo flows:

```bash
KEEP_VM=true LOADTEST_RUNTIMES=go ./scripts/e2e-k3s-helm.sh
```

Expected: the Go demos build, register, and invoke successfully in the targeted path.

**Step 5: Commit final verification-only fixes if needed**

```bash
git add <any files changed by verification fixes>
git commit -m "Finish Go SDK integration across release and deploy flows"
```

## Notes for the implementing engineer

- Keep the runtime token as `go` everywhere the script matrix currently uses `java`, `java-lite`, `python`, `exec`.
- Do not wire the Go module into Gradle unless you have a concrete need. The right place to integrate it is image-building and E2E orchestration, not the Java build graph.
- Favor data-driven extensions over copy-paste branches. `scripts/e2e-k3s-helm.sh` is already matrix-oriented; extend that matrix cleanly.
- Be explicit in docs when Go is supported for demos/deploy but not yet included in benchmark tables. Silent omission will look like a bug.
- Some tooling is already incomplete for non-Go demos as well (`scripts/build-push-images.sh` is narrower than the full current demo matrix). Fix the Go gap without making that inconsistency worse.

## Verification checklist

- `uv run pytest scripts/image-builder/tests/test_image_builder.py -q`
- `uv run pytest scripts/tests/test_e2e_k3s_helm_control_plane_native.py scripts/tests/test_e2e_runtime_runners.py -q`
- `helm template nanofaas helm/nanofaas >/tmp/nanofaas-helm-render.yaml`
- `rg -n "word-stats-go|json-transform-go" /tmp/nanofaas-helm-render.yaml`
- `cd function-sdk-go && go test ./...`
- `cd examples/go/word-stats && go test ./...`
- `cd examples/go/json-transform && go test ./...`
- `./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.e2e.SdkExamplesE2eTest -PrunE2e=true --rerun-tasks`
