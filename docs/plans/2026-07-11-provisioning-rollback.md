# Provisioning Rollback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ensure failed managed-function provisioning leaves no Kubernetes or local-container resources created by the failed call.

**Architecture:** Add local compensating cleanup inside each provider. Track only resources created by the current Kubernetes call; reuse the container provider's existing replica state for cleanup.

**Tech Stack:** Java 21, Fabric8 Kubernetes Client and mock server, JUnit 5, AssertJ.

---

### Task 1: Kubernetes create-path rollback

**Files:**
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManagerTest.java`
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java`

1. Add a mock-server test that rejects Service creation after Deployment creation and asserts the Deployment is removed.
2. Run the focused test and verify RED because the Deployment remains.
3. Track resources created by the current call and clean them up in reverse order on failure.
4. Add a test proving a pre-existing Deployment is not deleted after the same Service failure.
5. Run all Kubernetes provider tests.

### Task 2: Local-container multi-replica rollback

**Files:**
- Modify: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java`

1. Add a test where replica one is ready and replica two fails readiness.
2. Assert both containers are removed, the proxy is closed, and a later retry succeeds.
3. Run the focused test and verify RED because replica one remains.
4. Remove all recorded replicas during failed provisioning and preserve cleanup errors as suppressed exceptions.
5. Cover runtime errors reported after container creation and verify the container is also removed.
6. Run all container provider tests.

### Task 3: Verification

1. Run both provider suites and control-plane tests with all modules enabled.
2. Run GitNexus change detection and inspect affected symbols and flows.
3. Run `git diff --check` and inspect the final diff.
4. Commit design, plan, tests, and provider changes.
