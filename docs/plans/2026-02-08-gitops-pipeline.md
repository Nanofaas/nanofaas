# GitOps Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement a complete GitHub Actions CI/CD pipeline that tests all components and publishes Docker images to GitHub Container Registry (GHCR) upon release tags.

**Architecture:** Use a multi-job workflow. `test` jobs run for every PR/push. `publish` job runs only on tags. Docker images are built for the control plane and all function examples.

**Tech Stack:** GitHub Actions, Docker, Gradle, `uv` (for Python), GHCR.

---

## Phase 1: Python Examples Dockerization

### Task 1: Create Dockerfile for Python word-stats

**Files:**
- Create: `examples/python/word-stats/Dockerfile`

### Task 2: Create Dockerfile for Python json-transform

**Files:**
- Create: `examples/python/json-transform/Dockerfile`

---

## Phase 2: GitHub Actions Workflow

### Task 3: Create GitOps workflow file

**Files:**
- Create: `.github/workflows/gitops.yml`

---

## Phase 3: Validation

### Task 4: Local dry-run of Docker builds

### Task 5: Finalize and Commit

---
