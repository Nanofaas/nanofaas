# Function Resource Requests and Limits Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the backend-specific function resource strings with validated numeric requests and limits applied consistently by Kubernetes and Docker/Podman.

**Architecture:** Define one nested resource model in `common`, validate it at the HTTP boundary, and translate it independently in each deployment provider. Kubernetes emits native resource requests/limits; the CLI container adapter emits soft and hard runtime flags without adding a local scheduler.

**Tech Stack:** Java 21, Jakarta Bean Validation, Spring Boot, Fabric8 Kubernetes Client, Docker/Podman-compatible CLI, JUnit 5, AssertJ.

---

### Task 1: Common resource contract

**Files:**
- Create: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/ResourceQuantity.java`
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/ResourceSpec.java`
- Test: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java`

1. Add failing serialization/model tests for nested requests and limits with numeric CPU and `memoryMiB`.
2. Run `./gradlew :common:test` and verify RED.
3. Implement the two records using `BigDecimal` and `Integer`, removing the legacy string fields.
4. Run `./gradlew :common:test` and verify GREEN.

### Task 2: Boundary validation

**Files:**
- Modify: common resource model validation types as needed.
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionControllerTest.java` or the existing request-validation test class.

1. Add failing HTTP tests for non-positive values, CPU scale above three decimals, and requests above limits.
2. Run the focused control-plane test and verify RED.
3. Add the minimum Jakarta validation annotations and one cross-field validator.
4. Run common and focused control-plane tests and verify GREEN.

### Task 3: Kubernetes translation

**Files:**
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilder.java`
- Test: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilderTest.java`

1. Add failing tests for distinct CPU/memory requests and limits and omitted values.
2. Run the focused test and verify RED.
3. Convert CPU cores to Kubernetes quantities and memory MiB to `Mi` quantities.
4. Run the Kubernetes provider suite and verify GREEN.

### Task 4: Container runtime translation

**Files:**
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerInstanceSpec.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/CliContainerRuntimeAdapter.java`
- Test: corresponding provider and adapter test classes.

1. Add failing adapter tests for `--cpu-shares`, `--cpus`, `--memory-reservation`, `--memory`, omitted values, and equal memory request/limit.
2. Add a failing provider test proving every replica receives the resource model.
3. Run focused tests and verify RED.
4. Pass resources through `ContainerInstanceSpec` and emit only applicable flags. Convert CPU request with `cores * 1024`, bounded to the runtime minimum.
5. Run the container provider suite and verify GREEN.

### Task 5: Contract migration and documentation

**Files:**
- Modify: `openapi.yaml`
- Modify: `docs/k8s.md`
- Modify: all active Java fixtures and YAML/JSON examples constructing the legacy `ResourceSpec`.

1. Update OpenAPI to the nested numeric contract and document provider semantics.
2. Migrate compilation fixtures and active examples; do not modify historical experiment snapshots.
3. Run `rg` to confirm no production or active-test use of legacy `ResourceSpec(String, String)` remains.

### Task 6: Verification

1. Run common, control-plane, Kubernetes provider, and container provider suites with all modules.
2. Run the complete build with `-PcontrolPlaneModules=all --rerun-tasks`.
3. Stage intended files and run GitNexus change detection.
4. Run `git diff --check`, inspect the final diff, and commit.
