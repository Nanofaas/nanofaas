# Explicit VM Provisioning and CLI Progress Design

**Date:** 2026-07-12

## Goal

Make a remote scenario reproducible with one explicit `run --provision` invocation and keep users informed while long-running workflow tasks execute.

## Decisions

The public command surface remains `run`, `plan`, `list`, `inspect`, `doctor`, and `tui`. Provisioning is an option of `run`, not a new command or an implicit side effect.

For `multipass`, provisioning creates or reuses each required VM. For `external`, it checks the configured SSH target and never creates or destroys infrastructure. The stack role receives base packages and, for Kubernetes validation, Helm, k3s, and the local registry configuration. A dedicated load-generator role receives k6. The repository is synchronized after provisioning. Operations reuse `VmOrchestrator` and bundled Ansible playbooks from `workflow-tasks`.

Provisioning is idempotent. A failure aborts the scenario and leaves the VM intact for diagnosis. Normal workflow cleanup remains unchanged. Without `--provision`, current behavior is preserved.

## Progress reporting

A small console sink consumes the existing normalized `WorkflowEvent` stream. It prints one line when a task starts and a completion/failure line with elapsed time. The workflow and task libraries remain UI-independent; Typer output stays in the control-plane tool. Captured command output continues to appear in exceptions, avoiding duplicate successful build logs.

Provisioning operations run inside the same event context, so users see one ordered sequence before scenario tasks. Tests use recording orchestrators and sinks; the real gate is a Multipass k3s E2E.

