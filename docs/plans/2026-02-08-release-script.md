# Release Script Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create an interactive Python script (`scripts/release.py`) that manages the version bump, updates project files, generates release notes from git history, and automates git operations (commit, tag, push).

**Architecture:** A standalone Python script using `questionary` for UI and `git` commands for automation. It will update both Gradle and Python project files.

**Tech Stack:** Python 3.11+, `uv`, `questionary`, `rich`, `semver`.

---

## Phase 1: Script Setup

### Task 1: Create script structure and dependencies

**Files:**
- Create: `scripts/release.py`

### Task 2: Implement version detection and bumping logic

---

## Phase 2: Git and Release Notes

### Task 3: Implement git log extraction for release notes

### Task 4: Implement file updating and Git automation

---

## Phase 3: Validation

### Task 5: Local dry-run verification

---
