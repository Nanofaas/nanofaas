# Alternative Deployment Providers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the Kubernetes and local-container deployment providers mutually exclusive at build configuration time, with Kubernetes selected by defaults and by `all`.

**Architecture:** Reuse the existing `module.properties` conflict model and `defaultEnabled` priority. Do not change `ControlPlaneModulesPlugin`, `ModuleConstraintResolver`, Spring auto-configurations, or `DeploymentProviderResolver`; declare the relationship in the two provider descriptors and make container workflows select their provider explicitly.

**Tech Stack:** Gradle settings plugin, Java 25, JUnit 5/AssertJ, NanoLab/Python/pytest, Docker Compose, Kubernetes.

---

## Decisions and expected behavior

- `k8s-deployment-provider`: `defaultEnabled=true`, conflicts with `container-deployment-provider`.
- `container-deployment-provider`: `defaultEnabled=false`, conflicts with `k8s-deployment-provider`.
- No selector and `-PcontrolPlaneModules=all` select Kubernetes and exclude the container provider.
- Explicitly selecting both providers fails during Gradle settings configuration.
- Explicitly selecting either provider alone succeeds.
- Container-specific workflows must name `container-deployment-provider`; Kubernetes and release workflows may keep `all`.
- Mixed-provider binaries are intentionally no longer supported. Runtime provider resolution remains unchanged.

### Task 1: Declare and lock the repository-level provider contract

**Files:**
- Modify: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RepositoryModuleDescriptorsTest.java:17-52`
- Modify: `platform/modules/k8s-deployment-provider/module.properties:3-6`
- Modify: `platform/modules/container-deployment-provider/module.properties:3-6`

**Step 1: Refresh code intelligence before editing**

Check `.gitnexus/meta.json` and refresh the feature-worktree index with `npx gitnexus analyze`, preserving embeddings with `--embeddings` if `stats.embeddings` is non-zero. Run upstream impact analysis for `RepositoryModuleDescriptorsTest` and `ControlPlaneModulesPlugin`.

Expected: no HIGH/CRITICAL warning for the descriptor test or plugin. If one appears, stop and report it before editing. Do not edit `DeploymentProviderResolver`; its already-measured impact is HIGH (69 affected symbols, 22 direct).

**Step 2: Add failing assertions for the real descriptors**

In `everyImmediateGradleModuleHasExactlyOneValidDescriptor`, locate the two descriptors from the existing `descriptors` list and assert:

```java
ModuleDescriptor kubernetes = descriptors.stream()
        .filter(descriptor -> descriptor.id().equals("k8s-deployment-provider"))
        .findFirst().orElseThrow();
ModuleDescriptor container = descriptors.stream()
        .filter(descriptor -> descriptor.id().equals("container-deployment-provider"))
        .findFirst().orElseThrow();

assertThat(kubernetes.defaultEnabled()).isTrue();
assertThat(container.defaultEnabled()).isFalse();
assertThat(kubernetes.conflicts()).containsExactly("container-deployment-provider");
assertThat(container.conflicts()).containsExactly("k8s-deployment-provider");
```

**Step 3: Run the focused test and confirm RED**

Run:

```bash
./gradlew -p platform/gradle-plugin test \
  --tests 'it.unimib.datai.nanofaas.gradle.RepositoryModuleDescriptorsTest'
```

Expected: FAIL because the container provider is still default-enabled and both conflict lists are empty.

**Step 4: Apply the minimal descriptor changes**

Set the Kubernetes descriptor to:

```properties
defaultEnabled=true
requires.strong=
requires.weak=
conflicts=container-deployment-provider
```

Set the container descriptor to:

```properties
defaultEnabled=false
requires.strong=
requires.weak=
conflicts=k8s-deployment-provider
```

**Step 5: Run focused and plugin tests**

Run:

```bash
./gradlew -p platform/gradle-plugin test \
  --tests 'it.unimib.datai.nanofaas.gradle.RepositoryModuleDescriptorsTest'
./gradlew -p platform/gradle-plugin test
```

Expected: both PASS.

**Step 6: Verify the selection matrix**

Run:

```bash
./gradlew :control-plane:printSelectedControlPlaneModules
./gradlew :control-plane:printSelectedControlPlaneModules -PcontrolPlaneModules=all
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
./gradlew help -PcontrolPlaneModules=k8s-deployment-provider,container-deployment-provider
```

Expected:

- The first two commands include `k8s-deployment-provider` and exclude `container-deployment-provider`.
- Each single-provider `bootJar` succeeds.
- The last command fails before task execution with a provider conflict.

Do not weaken the test by accepting either provider: Kubernetes is the deliberate default.

### Task 2: Keep container workflows explicit and document the choice

**Files:**
- Modify: `docs/control-plane.md:44-68`
- Modify: `docs/local.md:39-45`
- Verify only: `deploy/compose/compose.yaml:7-11`
- Verify only: `deploy/compose/Dockerfile:1-9`

**Step 1: Update module documentation**

In `docs/control-plane.md`, state that the two deployment providers conflict, Kubernetes is default-enabled, and `all` therefore selects Kubernetes. Add explicit examples for each provider and one invalid combined selection.

**Step 2: Correct the local Docker command**

In `docs/local.md`, replace:

```text
-PcontrolPlaneModules=all
```

with:

```text
-PcontrolPlaneModules=container-deployment-provider
```

This is required because `all` will now build the Kubernetes provider.

**Step 3: Confirm existing Compose defaults need no code change**

Run:

```bash
rg -n 'NANOFAAS_CONTROL_PLANE_MODULES.*container-deployment-provider' \
  deploy/compose/compose.yaml deploy/compose/Dockerfile
```

Expected: both Compose files already select the container provider explicitly.

**Step 4: Run documentation-sensitive script tests**

Run:

```bash
uv run pytest scripts/tests/test_docker_compose_deployment.py
git diff --check
```

Expected: PASS and no whitespace errors.

**Step 5: Check scope and commit NanoFaaS changes**

Run GitNexus `detect_changes(scope: "all")`. Expected changed scope: the descriptor contract test, two descriptors, and two documentation files; no runtime execution flow or Spring class should change.

Commit:

```bash
git add \
  platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RepositoryModuleDescriptorsTest.java \
  platform/modules/k8s-deployment-provider/module.properties \
  platform/modules/container-deployment-provider/module.properties \
  docs/control-plane.md docs/local.md
git commit -m "Make deployment providers mutually exclusive"
```

### Task 3: Make NanoLab's container validation select the container provider

**Repository:** `/Users/micheleciavotta/Downloads/nanolab/.worktrees/async-module-selector`

**Files:**
- Modify: `packages/nanolab/tests/plans/test_validate.py:385-423`
- Modify: `packages/nanolab/src/nanolab/plans/validate.py:51`
- Verify only: `packages/nanolab/src/nanolab/plans/validate.py:211-220`

**Step 1: Write the failing workflow assertion**

Change the expectation in `test_async_load_enables_async_modules_on_the_compose_control_plane` to:

```python
assert compose.env["NANOFAAS_CONTROL_PLANE_MODULES"] == (
    "container-deployment-provider,async-queue"
)
```

Do not add a selector builder: this is one fixed workflow constant.

**Step 2: Run the focused test and confirm RED**

Run from the NanoLab worktree:

```bash
uv run --package nanolab pytest \
  packages/nanolab/tests/plans/test_validate.py::test_async_load_enables_async_modules_on_the_compose_control_plane
```

Expected: FAIL showing actual value `all`.

**Step 3: Make the minimal production change**

Set:

```python
_ASYNC_CONTROL_PLANE_MODULES = "container-deployment-provider,async-queue"
```

Leave Kubernetes `additional_modules=("async-queue",)` unchanged: the Kubernetes build path already supplies its provider, and adding the container provider would be wrong.

**Step 4: Run NanoLab tests**

Run:

```bash
uv run --package nanolab pytest packages/nanolab/tests/plans/test_validate.py
uv run --package nanolab pytest
git diff --check
```

Expected: all PASS.

**Step 5: Commit NanoLab changes**

```bash
git add packages/nanolab/src/nanolab/plans/validate.py \
  packages/nanolab/tests/plans/test_validate.py
git commit -m "Select container provider for local validation"
```

### Task 4: End-to-end verification across both providers

**Files:** No source changes expected.

**Step 1: Validate the container path**

From the NanoLab worktree, point `NANOFAAS_ROOT` at the NanoFaaS feature worktree and run:

```bash
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas/.worktrees/module-constraints \
  ./nanolab.sh run packages/nanolab/scenarios-v2/validate-async-container.yaml \
  --environment packages/nanolab/environments/local.yaml
```

Expected: PASS; the control-plane image is built with `container-deployment-provider,async-queue` and no Kubernetes provider.

**Step 2: Validate the Kubernetes path**

Run:

```bash
NANOFAAS_ROOT=/Users/micheleciavotta/Downloads/nanofaas/.worktrees/module-constraints \
  ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
```

Expected: PASS; the control-plane build contains the Kubernetes provider and excludes the container provider.

**Step 3: Final repository checks**

In both worktrees run `git status --short`. Expected: clean. In NanoFaaS, refresh GitNexus after the commit, preserving embeddings if present.

## Explicitly out of scope

- No changes to runtime provider resolution or function API contracts.
- No new Gradle plugin feature or provider abstraction.
- No automatic environment detection in `all`.
- No mixed Kubernetes/container control-plane binary.
- No edits to release/native workflows that use `all`; Kubernetes is their intended provider after this change.
