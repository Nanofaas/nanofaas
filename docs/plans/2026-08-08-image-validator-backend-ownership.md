# Image Validator Backend Ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make image availability fail during function registration for both Kubernetes and Docker, while each backend owns its validator and configuration.

**Architecture:** Move the Kubernetes validator into the Kubernetes deployment-provider module, alongside `KubernetesProperties`. Add a Docker validator to the container deployment-provider module; it delegates the image pull to `ContainerRuntimeAdapter`, so CLI and docker-java implementations retain their own runtime details. The core continues to expose only `ImageValidator` and its fallback.

**Tech Stack:** Java 25, Spring Boot, ArchUnit, Fabric8 Kubernetes client, docker-java, JUnit 5, Mockito.

---

### Task 1: Move the Kubernetes validator to its backend module

**Files:**
- Move: `platform/modules/image-validator/src/main/java/.../KubernetesImageValidator.java`
- Move: `platform/modules/image-validator/src/main/java/.../ImageValidatorConfiguration.java`
- Move tests: `platform/modules/image-validator/src/test/java/.../KubernetesImageValidatorTest.java`, `ImageValidatorConfigurationTest.java`
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/.../KubernetesDeploymentProviderConfiguration.java`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/.../architecture/ArchitectureTest.java`

**Step 1: Write failing ownership tests**

Assert that the Kubernetes module creates `KubernetesImageValidator` and that its R5 rule is active.

**Step 2: Run the targeted Kubernetes test**

Run: `./gradlew :control-plane-modules:k8s-deployment-provider:test`

Expected: FAIL because the implementation remains outside the module.

**Step 3: Move the implementation and tests**

Place the implementation and Spring configuration under `modules.k8s.imagevalidation`; keep `KubernetesProperties` in the Kubernetes module and enable the configuration through its component scan.

**Step 4: Remove the obsolete image-validator module**

Delete the now-empty source module and its Gradle project directory.

**Step 5: Run the targeted Kubernetes test**

Run: `./gradlew :control-plane-modules:k8s-deployment-provider:test`

Expected: PASS, including active R5.

### Task 2: Add Docker image validation at registration

**Files:**
- Modify: `platform/modules/container-deployment-provider/src/main/java/.../ContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/.../CliContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/.../DockerJavaContainerRuntimeAdapter.java`
- Create: `platform/modules/container-deployment-provider/src/main/java/.../DockerImageValidator.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/.../ContainerDeploymentProviderConfiguration.java`
- Create/modify tests under `platform/modules/container-deployment-provider/src/test/java/...`

**Step 1: Write failing validator tests**

Assert that deployment registration validation calls `pullImage`, skips non-deployment specs, and maps a pull failure to `ImageValidationException`.

**Step 2: Run the targeted container test**

Run: `./gradlew :control-plane-modules:container-deployment-provider:test --tests '*DockerImageValidatorTest'`

Expected: FAIL because no validator or adapter operation exists.

**Step 3: Add `pullImage` to the adapter and its two implementations**

Use `runtime pull <image>` in the CLI adapter and docker-java's pull command in the Java adapter. Surface adapter failures as `IllegalStateException`.

**Step 4: Add the validator and Spring bean**

Implement the existing core SPI. Translate pull failures to `ImageValidationException.registryUnavailable`; the successfully pulled image is reused by provisioning.

**Step 5: Run the targeted container test**

Run: `./gradlew :control-plane-modules:container-deployment-provider:test`

Expected: PASS.

### Task 3: Verify module isolation and the complete regression suite

**Files:**
- Modify: `settings.gradle` only if the removed module requires an explicit reference cleanup.

**Step 1: Run active architecture checks**

Run: `./gradlew :control-plane-modules:k8s-deployment-provider:test :control-plane-modules:container-deployment-provider:test :control-plane:test`

Expected: PASS with no `@ArchIgnore` for R5.

**Step 2: Run the complete Java suite and Python suite**

Run: `./gradlew test` and `uv run pytest`.

Expected: both PASS.

**Step 3: Review diff and commit**

Run: `git diff --check`, `git status --short`, and `gitnexus_detect_changes`. Commit only the backend-ownership refactor and tests.
