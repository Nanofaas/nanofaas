# Native Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transition the GitOps pipeline to produce GraalVM native images for the control-plane and Java function examples using Cloud Native Buildpacks.

**Architecture:** Replace manual Docker builds with Spring Boot's `bootBuildImage` task configured for native output (`BP_NATIVE_IMAGE=true`). Publish generated images to GHCR.

**Tech Stack:** GitHub Actions, GraalVM, Spring Boot Buildpacks, GHCR.

---

## Phase 1: Workflow Update

### Task 1: Update gitops.yml to use bootBuildImage for Control Plane

**Files:**
- Modify: `.github/workflows/gitops.yml`

**Step 1: Replace Docker build for control-plane**
Instead of using `docker/build-push-action`, we will call:
```bash
./gradlew :control-plane:bootBuildImage 
  -PcontrolPlaneImage=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/control-plane:${{ github.ref_name }} 
  -PimagePlatform=linux/amd64
```
And then push the image manually or via a docker command.

### Task 2: Configure native build for Java examples

**Files:**
- Modify: `examples/java/word-stats/build.gradle`
- Modify: `examples/java/json-transform/build.gradle`

**Step 1: Add GraalVM plugin to examples**
**Step 2: Configure bootBuildImage in examples**

---

## Phase 2: Docker Image Optimization

### Task 3: Cleanup Dockerfiles (Optional/Reference)
- Update `control-plane/Dockerfile` with a comment that it's for JVM-only development.

---

## Phase 3: Validation

### Task 4: Local test of native buildpack command
- Run `./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=test-native:latest` locally (if Docker is available).

### Task 5: Finalize and Commit
