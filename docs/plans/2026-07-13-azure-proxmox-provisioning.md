# Azure and Proxmox Provisioning Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore Azure and Proxmox provisioning and execution with automatic managed-VM cleanup.

**Architecture:** Map the simplified environment model to existing workflow-task VM providers, use their native command/file interfaces, and wrap provisioning plus product execution in one ownership scope.

**Tech Stack:** Python 3.12, Typer, Pydantic, pytest, existing `azure-vm`, `proxmox-sdk`, and `workflow-tasks` packages.

---

### Task 1: Provider request mapping

- Add failing tests for Azure and Proxmox role mapping, defaults, ports and secret lookup.
- Add only the missing environment defaults and a shared environment-to-`VmRequest` mapper.
- Run the focused configuration tests.

### Task 2: Provider-native execution and endpoints

- Add failing tests proving Azure and Proxmox commands and downloads use their existing providers.
- Add failing tests for Azure public NodePorts and Proxmox guest/NAT endpoint resolution.
- Implement provider-native bindings and URL resolution while retaining Multipass/external behavior.
- Run the focused execution tests.

### Task 3: Provider-aware bootstrap

- Add failing tests for Azure and Proxmox Ansible inventory, key and rsync endpoint selection.
- Adapt the existing bootstrap planners without extending `VmRequest`.
- Run workflow-task bootstrap tests and regressions.

### Task 4: Managed provisioning scope

- Add failing tests for reverse cleanup on success, cleanup on failure, `--keep`, and no external teardown.
- Select the provider from the environment and make provisioning a context manager around the product workflow.
- Run provisioning and CLI command tests.

### Task 5: Examples and verification

- Add minimal Azure and Proxmox environment examples and update CLI documentation.
- Run formatting, linting, control-plane and workflow-task test suites.
- Run GitNexus change detection and inspect every affected direct consumer/process.
- Run credentialed Azure/Proxmox E2E where local configuration permits, then commit and push the existing branch.
