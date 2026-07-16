# Azure Image Release Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task in the current session.

**Goal:** Build, benchmark, and publish every NanoFaaS AMD64/ARM64 image from a controlled Azure run, with an AMD64 performance gate before QEMU ARM64 builds and any GHCR release update.

**Architecture:** Restore `controlplane-tool images` using the current `workflow-tasks` primitives and the existing function catalog. Add a small `controlplane-tool release` coordinator that provisions the established Azure stack/loadgen topology, keeps candidates in the stack-local registry, runs three AMD64 benchmarks, then builds ARM64 under QEMU and promotes the exact artifacts to GHCR. Local, Multipass, and Proxmox remain non-publishing experiment backends.

**Tech Stack:** Python 3.11+, Typer, Pydantic, `workflow-tasks`, Docker Buildx/BuildKit, QEMU/binfmt, Spring Boot Buildpacks, k3s, k6, Prometheus, Azure VMs, GHCR, skopeo, syft, cosign.

---

## Review corrections incorporated

This revision replaces the first plan at commit `4f067158` and fixes these execution problems:

- large orchestration tasks are split at phase boundaries so each implementation agent has one bounded responsibility;
- GHCR and signing secrets are transferred with existing provider file-transfer APIs, consumed through files/stdin, and removed in always-run cleanup tasks;
- release-only packages are installed by a release-builder playbook on the stack VM, not by the shared base provisioning used by loadgen and ordinary scenarios;
- artifact promotion, SBOM generation, and signing are separate tasks with separately testable failure behavior;
- source tests, AMD64 build, benchmark, ARM64 build, and publication are distinct gates;
- a digest-verified phase journal permits safe resume without trusting stale or mismatched artifacts;
- global atomicity across many registry tags is not claimed: immutable architecture tags are uploaded first, version manifests next, and mutable aliases last;
- performance history is updated only after images, signatures, and attestations have all verified;
- v0.18.0 performance records describe only the pinned Azure AMD64-native profile; QEMU ARM64 results are functional, never performance data.

## Execution rules

- Execute with `superpowers:subagent-driven-development`: one fresh implementation agent and one review pass per task.
- The subagent API has no model selector. Difficulty labels control task size and review depth, not the actual model; never claim otherwise.
- Each agent reads this plan and `docs/plans/2026-07-16-azure-image-release-design.md` before acting.
- Before editing an existing symbol, run `gitnexus_impact(target, direction="upstream")`. Warn before HIGH/CRITICAL edits.
- Before each commit, run `gitnexus_detect_changes(scope="staged")`; after the commit, refresh GitNexus without deleting existing embeddings.
- Follow TDD: failing focused test, minimal implementation, focused pass, affected-suite pass, commit.
- Do not run the paid Azure acceptance release without separate user authorization.

## Known blast radius

GitNexus reports HIGH risk for `build_loadtest_plan`, `ValidateWorkflowRequest`, and `k8s_deployment_specs`. Task 4 changes them additively with defaults that preserve current CLI, TUI, validation, and load-test task lists. Those direct and transitive suites must pass before its commit.

## Resulting commands

```bash
# Portable and non-publishing
controlplane-tool images plan --version v0.18.0 --arch all --flavor all
controlplane-tool images build --version v0.18.0 --arch amd64 \
  --environment environments/multipass.yaml

# Version preparation; review and commit its output before release
controlplane-tool release prepare v0.18.0

# Azure-only authoritative flow
controlplane-tool release plan v0.18.0 \
  --environment environments/azure-release.yaml
controlplane-tool release run v0.18.0 \
  --environment environments/azure-release.yaml \
  --ghcr-token-file /secure/ghcr-token \
  --cosign-key-file /secure/cosign.key \
  --cosign-password-file /secure/cosign-password \
  --provision
```

### Task 1: Implement deterministic version preparation

**Difficulty:** Medium

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/release/versioning.py`
- Create: `tools/controlplane/tests/release/test_versioning.py`

**Steps:**

1. Write tests for `normalize_version`, `read_project_version`, consistency checking, exact replacement counts, invalid versions, non-incrementing versions, and a mismatched source tree.
2. Run `uv run pytest -q tests/release/test_versioning.py` from `tools/controlplane`; expect import failure.
3. Implement:

   ```python
   def normalize_version(value: str) -> tuple[str, str]: ...
   def read_project_version(repo_root: Path) -> str: ...
   def verify_version_consistency(repo_root: Path) -> str: ...
   def prepare_version(repo_root: Path, requested: str) -> tuple[Path, ...]: ...
   ```

   Build and validate every rewritten content string before writing any file. Update only curated version locations; do not parse and re-emit YAML.
4. The curated release-time outputs are `build.gradle`, Helm chart/values, the k8s deployment, watchdog Cargo files, Python SDK project/locks, Roman Numeral's Python lock, fn-init's published SDK default, and the CLI version assertion.
5. Regenerate lockfiles with `cargo check`/`uv lock` after primary version edits; compare the resulting diff with the curated set.
6. Run focused tests, `./gradlew verifyHelmVersionSync :nanofaas-cli:test`, and both `uv lock --check` commands.
7. Run staged GitNexus detection and commit `Add release version preparation`.

### Task 2: Restore the current 52-cell image matrix

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/images/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/images/plan.py`
- Create: `tools/controlplane/tests/images/test_plan.py`
- Modify: `functions/java/roman-numeral/build.gradle`
- Test: `tools/controlplane/tests/test_function_catalog.py`

**Steps:**

1. Read the recoverable implementation and tests from `ee47d9a2^`; do not restore the deleted `shellcraft` dependency.
2. Write failing tests asserting:
   - `control-plane`, `java-warm-echo`, `watchdog`, and every non-fixture `list_functions()` entry are present;
   - 52 cells result from two architectures and applicable flavors;
   - Spring Java targets have JVM/native, Java Lite has native, and other runtimes have default flavor;
   - tags are `v0.18.0-<arch>-<flavor>` or `v0.18.0-<arch>`;
   - AMD64 cells always precede ARM64 cells;
   - every discovered function Dockerfile maps to exactly one target.
3. Implement immutable `ImageTarget`, `ImageCell`, and `ImagePlan` dataclasses using `CommandTaskSpec`, not a new execution abstraction.
4. Dockerfile cells use single-platform `docker buildx build --load`. Native Spring cells reuse existing `bootBuildImage` and `imagePlatform`; JVM Spring cells run `bootJar` then their Dockerfile.
5. Add Roman Numeral's missing GraalVM/`bootBuildImage` configuration by matching word-stats/json-transform. Rerun impact first.
6. Run focused tests and `./gradlew :functions:java:roman-numeral:tasks --all`; require `bootBuildImage` and `nativeCompile`.
7. Run staged detection and commit `Restore portable image matrix planning`.

### Task 3: Add the portable `images` command group and private transport

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli/images.py`
- Create: `tools/controlplane/tests/cli/test_images_command.py`
- Modify: `tools/controlplane/src/controlplane_tool/app/main.py`

**Steps:**

1. Write CLI tests for `images plan` and `images build`, selectors, environment loading, dry-run rendering, unknown targets, and failure propagation.
2. Assert the portable command exposes no GHCR publication option and rejects a `ghcr.io/miciav/nanofaas` build registry.
3. Register a Typer sub-app with only `plan` and `build`.
4. Build role bindings with existing `build_role_bindings`; convert matrix commands with `workflow_from_specs`.
5. Default the build role to stack. When an explicit builder role differs from the benchmark stack, use Docker image archives, relay them through existing provider `transfer_from`/`transfer_to`, load them on stack, and verify image IDs/digests. Do not add a format-conversion dependency or use an external registry for this transfer.
6. Test archive and remote cleanup on success, transfer failure, load failure, and digest mismatch.
7. Allow an explicit stack-local registry push because k3s needs candidates; never allow release tags on GHCR from this group.
8. Run the new CLI tests plus `tests/cli/test_command_surface.py` and the full 303-test controlplane suite.
9. Run staged detection and commit `Add image matrix commands`.

### Task 4: Let load testing deploy exact prebuilt images

**Difficulty:** High — HIGH blast radius

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/validate.py`
- Modify: `tools/controlplane/src/controlplane_tool/plans/loadtest.py`
- Modify: `tools/workflow-tasks/tests/workflows/test_validate.py`
- Modify: `tools/controlplane/tests/plans/test_loadtest.py`

**Steps:**

1. Rerun impact for `ValidateWorkflowRequest`, `k8s_deployment_specs`, and `build_loadtest_plan`; report all d=1 dependants before editing.
2. Add tests showing ordinary task IDs are byte-for-byte unchanged with defaults.
3. Add tests for prebuilt mode: no Gradle/image build/push tasks, exact control-plane image in Helm values, exact function image in registration.
4. Add only defaulted fields:

   ```python
   build_images: bool = True
   control_plane_image: str | None = None
   ```

   Require the override only when `build_images=False`.
5. Add keyword-only `prebuilt_control_plane_image` and `prebuilt_function_images` to `build_loadtest_plan`; default `None` preserves every caller.
6. Run focused suites, all workflow-tasks tests, and all controlplane tests. Inspect CLI `plan_command` and TUI `run_current_workflow` flows.
7. Run staged detection and commit `Allow load tests to use prebuilt images`.

### Task 5: Add release metrics and regression policy

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/metrics.py`
- Create: `tools/controlplane/tests/release/test_metrics.py`
- Create: `tools/controlplane/release.yaml`
- Create: `docs/performance/history.md`
- Create: `docs/performance/releases/.gitkeep`
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py`
- Modify: `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py`

**Steps:**

1. Add a failing test requiring k6 `--summary-trend-stats avg,min,med,max,p(50),p(90),p(95),p(99)`; then minimally add it to `_build_k6_argv`.
2. Create three fixture summaries and test per-metric medians for throughput, error rate, p50/p95/p99, selected Prometheus aggregates, and peak replicas.
3. Test that comparisons reject different provider/VM/architecture/flavor/scenario profiles.
4. Store policy in `tools/controlplane/release.yaml`:

   ```yaml
   schemaVersion: 1
   benchmark:
     scenario: scenarios-v2/loadtest.yaml
     runs: 3
     profile: azure-d4s-v5+d2s-v5-amd64-native-loadtest-v1
     regression:
       throughputMaxLossPercent: 10
       p95MaxIncreasePercent: 15
       errorRateMax: 0.30
   ```

5. The first version must pass k6/autoscaling gates and becomes the baseline. Later versions compare with the newest identical-profile record.
6. Implement deterministic rendering for the version JSON and `history.md`, but keep benchmark output in the run directory until the whole release verifies. Do not mutate published performance history at this gate.
7. Run focused tests, both package linters/type checks, staged detection, and commit `Add release performance aggregation`.

### Task 6: Pin the Azure release environment and prepare only its stack

**Difficulty:** Medium

**Files:**
- Create: `tools/controlplane/environments/azure-release.yaml.example`
- Create: `tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/provision-release-builder.yml`
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible.py`
- Modify: `tools/workflow-tasks/tests/infra/test_ansible.py`
- Create: `tools/controlplane/tests/release/test_environment.py`

**Steps:**

1. Resolve an exact Ubuntu image version with `az vm image list ... --all`; store the exact URN, never `latest`.
2. Configure stack `Standard_D4s_v5` with 128 GB disk and loadgen `Standard_D2s_v5` with 30 GB disk in West Europe.
3. Write guard tests rejecting non-Azure providers, missing stack/loadgen roles, `latest` URNs, burstable VM sizes, and an unprepared project version.
4. Add a release-only Ansible adapter/playbook installing `skopeo` and transport utilities on stack only. Let the digest-pinned binfmt container install QEMU handlers; do not also manage host binfmt packages.
5. Keep shared `provision-base.yml` unchanged.
6. Run config/Ansible tests, all workflow-tasks tests, staged detection, and commit `Add pinned Azure release environment`.

### Task 7: Implement secure release credentials and cleanup

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/secrets.py`
- Create: `tools/controlplane/tests/release/test_secrets.py`

**Steps:**

1. Test that token, signing key, and password paths must be regular local files with restrictive permissions; never accept secret values as CLI arguments.
2. Reuse the selected Azure provider's `transfer_to` for three temporary remote files under a mode-0700 release directory.
3. Authenticate with `docker login ghcr.io --password-stdin < token-file`; commands/events must contain paths only, never contents.
4. Expose the cosign key/password paths to the later signing task without reading them into Python strings.
5. Implement always-run remote deletion and local in-memory metadata cleanup. Test cleanup after success, phase failure, transfer failure, and authentication failure.
6. Test rendered plans for absence of fixture secret contents.
7. Run focused tests, staged detection, and commit `Transfer release credentials securely`.

### Task 8: Add a durable, verified release journal

**Difficulty:** Medium

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/state.py`
- Create: `tools/controlplane/tests/release/test_state.py`

**Steps:**

1. Define an append-only JSON journal under `runs/releases/<version>/state/` with schema version, source commit, prepared version, normalized release-config digest, environment digest, phase, artifact references, digests, timestamps, and outcome.
2. Write tests for atomic entry creation, interrupted writes, corrupt entries, unknown schema versions, and phase-order violations.
3. Add `--resume` validation tests: reuse a completed phase only when commit/version/config/environment match and every referenced local or remote artifact still has the recorded digest.
4. Never resume from a mere success flag. On missing or mismatched evidence, invalidate that phase and every downstream phase, then rerun from the earliest invalid phase.
5. Make journal entries contain paths, image references, and digests only; reject credentials and known fixture secret contents.
6. Keep the journal after cleanup as the local audit record; remote candidate cleanup must not delete it.
7. Run focused tests, staged detection, and commit `Add verified release resume state`.

### Task 9: Build the Azure release coordinator through the AMD64 gate

**Difficulty:** Very high

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/run.py`
- Create: `tools/controlplane/tests/release/test_run_amd64.py`
- Create: `tools/controlplane/src/controlplane_tool/cli/release.py`
- Create: `tools/controlplane/tests/cli/test_release_command.py`
- Modify: `tools/controlplane/src/controlplane_tool/app/main.py`

**Steps:**

1. Test `release prepare`, offline `release plan`, and `release run` command surfaces.
2. Test the hard guard: clean Git tree, requested/prepared version match, Azure pinned profile, complete roles, and explicit credential files. Expose `--resume`; without it, reject an existing journal for the same version.
3. After ordinary provisioning, invoke the Task 6 release-builder adapter against the stack role only.
4. Compose sequential phases: source tests → full AMD64 matrix → local-registry push → benchmark 1/2/3 → aggregate → regression gate.
5. Create the source bundle with `git archive` from the guarded commit and synchronize it to stack through the existing provider transfer API. This includes only tracked content and excludes `.git`, credentials, worktrees, build outputs, and prior run artifacts; verify its checksum before running tests.
6. Run source tests on stack from that verified archive. Reuse Gradle/uv; run Go, Node, Rust, and Bash tests in digest-pinned Docker toolchain images rather than installing mutable host toolchains.
7. Invoke `build_loadtest_plan` three times with the exact AMD64-native control-plane/function references and `runs/releases/<version>/run-{1,2,3}` directories.
8. Let each existing load-test workflow clean Helm/functions before the next run. Never rebuild candidates during benchmark.
9. Record and verify Task 8 journal evidence at each boundary. Test fresh execution, safe resume, invalidation after digest mismatch, and refusal to cross a failed gate.
10. With recording executors, inject failures into every phase and assert no ARM or GHCR command appears.
11. Validate local credential-file metadata at startup, but defer transfer to the publication/signing phases so secrets do not remain on the VM during builds and benchmarks.
12. Run focused tests and both full Python suites. Request a correctness review of gate ordering and resume behavior.
13. Run staged detection and commit `Add AMD64-gated Azure release workflow`.

### Task 10: Add QEMU ARM64 build and functional smoke gate

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/arm.py`
- Create: `tools/controlplane/tests/release/test_arm.py`
- Modify: `tools/controlplane/src/controlplane_tool/release/run.py`

**Steps:**

1. Test that QEMU preparation and ARM tasks are unreachable before a passed aggregate gate.
2. Register binfmt from a digest-pinned `tonistiigi/binfmt` image and create/reuse a named `docker-container` Buildx builder.
3. Require `linux/arm64` in `docker buildx inspect --bootstrap` before building.
4. Build and locally push every ARM64 cell; fail on a missing matrix cell.
5. Inspect each architecture. Start server/function images with `--platform linux/arm64`, wait for their health endpoint, and always remove containers.
6. For the watchdog scratch image, execute its binary and explicitly reject `exec format error`; document the expected missing-child exit.
7. Assert no GHCR command runs after any ARM build/smoke failure.
8. Run focused tests, the images plan tests, staged detection, and commit `Add QEMU ARM64 release gate`.

### Task 11: Promote exact artifacts and create manifests

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/publish.py`
- Create: `tools/controlplane/tests/release/test_publish.py`
- Modify: `tools/controlplane/src/controlplane_tool/release/run.py`

**Steps:**

1. Test the complete tag set for flavored/default images and `v0.18.0` as native alias.
2. Require evidence for all 52 cells plus AMD64 benchmark and ARM smoke gates before planning publication.
3. Transfer the GHCR token only now, authenticate through stdin, and register its deletion as always-run cleanup.
4. Copy local-registry architecture images with `skopeo copy --preserve-digests --src-tls-verify=false` to immutable GHCR architecture tags.
5. Compare source/destination digests. Stop before manifests on any mismatch.
6. Create flavor/default manifests with `docker buildx imagetools create`; inspect and require exactly AMD64 and ARM64.
7. Create version aliases only after every version manifest verifies. Do not update `latest` in v0.18.0.
8. Do not claim cross-repository transactional publication. A release is complete only after the final verification record is written; immutable partial uploads are safe to rerun.
9. Run focused tests with failures at copy, digest, manifest, and alias phases; staged detection; commit `Publish verified release image manifests`.

### Task 12: Generate SBOMs, sign evidence, and finalize records

**Difficulty:** High

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/attest.py`
- Create: `tools/controlplane/tests/release/test_attest.py`
- Modify: `tools/controlplane/src/controlplane_tool/release/run.py`

**Steps:**

1. Test a release predicate containing schema version, source commit, Azure profile, benchmark record digest, and every final image digest.
2. Transfer the signing key/password only now and register their deletion as always-run cleanup.
3. Generate SPDX JSON with a digest-pinned syft container against final image digests.
4. Sign/attach predicates and SBOMs with a digest-pinned cosign container, mounting only temporary secret files and Docker auth.
5. Read the cosign password inside the remote shell from its file; never place it in argv, task env, logs, or metadata.
6. Verify signatures/attestations before marking the release complete.
7. Only after verification succeeds, atomically write `docs/performance/releases/<version>.json`, regenerate `history.md`, and append the final journal record. A signing failure must leave published performance history unchanged.
8. If writing either documentation file fails, do not append the final journal record; `--resume` must retry finalization without rebuilding verified images.
9. Always delete remote signing material, including when signing or verification fails.
10. Run focused secret/redaction/failure/finalization tests, staged detection, and commit `Attest and finalize released artifacts`.

### Task 13: Make Azure authoritative, document, and verify

**Difficulty:** High

**Files:**
- Modify: `.github/workflows/gitops.yml`
- Create: `scripts/tests/test_release_authority.py`
- Create: `docs/operations/image-releases.md`
- Modify: `README.md`
- Modify: `docs/performance/history.md`
- Modify: `tools/controlplane/README.md` only if it already exists

**Steps:**

1. Remove the GitHub tag-publish job and package-write permission; retain test jobs.
2. Add a contract test rejecting `docker push`, release `bootBuildImage`, or `packages: write` in the GitHub workflow.
3. Document preparation, version commit, dry run, paid Azure run, credentials, costs, 52 cells, tag policy, run/report paths, verified `--resume`, recovery, cleanup, and the AMD64-only performance claim.
4. Document that local/Multipass/Proxmox builds cannot promote and GitHub Actions only tests.
5. Run static checks:

   ```bash
   cd tools/workflow-tasks && uv run ruff check src tests && uv run basedpyright
   cd ../controlplane && uv run ruff check src tests && uv run basedpyright
   uv run controlplane-quality
   ```

6. Run all Python suites from their own package directories, `./gradlew build -PcontrolPlaneModules=all`, `functions/contract-tests/run.sh`, watchdog tests, and `git diff --check`.
7. Render the 52-cell plan and an Azure release plan. Confirm order: tests → AMD64 → three benchmarks → gate → QEMU/ARM64 → smoke → copy/manifests → attest → cleanup.
8. Run a small Multipass AMD64 experiment and verify GHCR remains unchanged.
9. Run `gitnexus_detect_changes(scope="compare", base_ref="main")`; inspect ordinary CLI/TUI load-test flows.
10. Request final code review and use `superpowers:verification-before-completion` before claiming completion.
11. Commit `Document and verify Azure image releases`.

## Paid acceptance release

Run only after code review and explicit user authorization:

```bash
controlplane-tool release prepare v0.18.0
# Review, test, and commit the version change.
controlplane-tool release run v0.18.0 \
  --environment tools/controlplane/environments/azure-release.yaml \
  --ghcr-token-file /secure/ghcr-token \
  --cosign-key-file /secure/cosign.key \
  --cosign-password-file /secure/cosign-password \
  --provision

# After an interruption, only verified matching phases may be reused
controlplane-tool release run v0.18.0 \
  --environment tools/controlplane/environments/azure-release.yaml \
  --ghcr-token-file /secure/ghcr-token \
  --cosign-key-file /secure/cosign.key \
  --cosign-password-file /secure/cosign-password \
  --resume
```

Acceptance requires:

- all source tests pass on the Azure stack;
- the exact AMD64-native candidates complete three benchmark runs and pass policy;
- all 52 cells exist and ARM64 cells pass QEMU smoke tests;
- GHCR architecture digests match their Azure-local sources;
- each final manifest contains exactly AMD64 and ARM64;
- `v0.18.0` and `v0.18.0-native` resolve to the same native descriptors;
- SBOM and signed predicate verification succeeds;
- `docs/performance/releases/v0.18.0.json` and `history.md` are produced;
- the final journal record matches the source commit, configuration digests, published image digests, and performance-record digest;
- all remote secret files and Azure resources are cleaned unless `--keep` was explicit.
