# Control-plane Tool Simplification Design

## Goal

Turn the pre-release control-plane tool into a small scenario runner with one execution model, while preserving the research capabilities that matter: local execution, Multipass, remote SSH/Ansible, Azure, Proxmox, Kubernetes, the local container provider, CLI validation, and reproducible load tests.

The tool is not required to preserve old command names, saved profiles, deprecated scenarios, or compatibility paths.

## Product boundary

The user-facing product has six commands:

```text
controlplane-tool run <scenario.toml> --environment <environment.toml>
controlplane-tool plan <scenario.toml> --environment <environment.toml>
controlplane-tool list
controlplane-tool inspect <scenario.toml>
controlplane-tool doctor
controlplane-tool tui
```

`run` supports task selection for research and debugging:

```text
--only <task-id>
--from <task-id>
--until <task-id>
--keep
```

Build, image, VM, CLI-test, and load-test operations are not separate public APIs. They are tasks selected through a scenario plan. `doctor` checks local prerequisites, provider credentials, SSH reachability, and required tools without mutating infrastructure.

## Workflows

Only three workflow families remain:

1. `validate`: build, deploy, register, invoke, scale where applicable, verify resources, and clean up. It supports POOL, the local container deployment provider, and Kubernetes.
2. `cli`: exercise the CLI lifecycle. Configuration decides whether the CLI runs on the host or stack role.
3. `loadtest`: register functions, run k6, collect Prometheus data, evaluate gates, verify autoscaling, and write artifacts. Configuration selects one- or two-machine topology.

Build strategy, deployment backend, execution location, VM provider, and topology are parameters, not scenario names.

The following scenarios are deleted without aliases or deprecation shims:

- `validate-docker-pool`
- `validate-buildpack-pool`
- `validate-container-local`
- `cli-suite`
- `cli-stack`
- `cli-host`
- `validate-deploy-host`
- `loadtest-helm-legacy`
- `loadtest-one-vm`
- `loadtest-two-vm`
- `loadtest-azure`
- `loadtest-proxmox`

Their useful behavior is represented by parameters of the three workflow families. The deploy-host stub path and legacy Helm path have no replacement.

## Scenario and environment

A scenario describes what to execute and remains safe to commit:

```toml
workflow = "validate"
backend = "container"
build = "docker"
functions = ["word-stats-java"]

[resources.word-stats-java.requests]
cpu = 0.25
memoryMiB = 256

[resources.word-stats-java.limits]
cpu = 0.5
memoryMiB = 512
```

An environment describes where roles execute:

```toml
provider = "external"

[roles.stack]
host = "research-vm.example"
user = "ubuntu"
home = "/srv/nanofaas"
```

Environment providers are `local`, `multipass`, `external`, `azure`, and `proxmox`. Provider credentials remain outside scenarios and may be supplied by environment variables or ignored local environment files.

Saved profiles are deleted because they duplicate scenarios. Existing Azure and Proxmox infrastructure profiles become environment files.

## Task and execution model

Task definitions live in `workflow-tasks`. The control-plane tool parses configuration, composes plans, and renders events; it does not own provisioning or execution logic.

Every task has:

- a stable ID;
- a logical execution role: `host`, `stack`, or `loadgen`;
- a command or a narrowly scoped lifecycle operation;
- explicit retry policy when convergence is expected;
- optional artifact declarations;
- cleanup behavior.

Tasks never select SSH, Multipass, Azure, or Proxmox themselves. An environment binds roles to executors. The stack role may resolve to the host for local validation, to a Multipass VM, or to an SSH endpoint. Load generation may share the stack executor or use a second executor.

The workflow is an ordered list, not a general DAG. Current platform workflows are sequential, and a DAG scheduler would add complexity without demonstrated benefit.

## Failure and cleanup semantics

Execution stops at the first failed main task. Cleanup tasks always run in reverse acquisition order. Cleanup failures are reported alongside the primary failure without hiding it.

Errors identify task ID, role, resolved endpoint, command, exit code, stdout, and stderr. Output is streamed to CLI/TUI events and retained as run artifacts.

`--keep` prevents destruction of research infrastructure and deployed stacks. It does not skip safety cleanup such as terminating owned processes, closing port forwarding, or removing temporary secrets.

Retries are explicit and limited to readiness, convergence, and eventually consistent deletion. Mutating commands are not retried implicitly.

## Removed architecture

The migration deletes:

- Prefect runtime integration, deployment YAML, event bridges, scenario metadata, tests, and dependency;
- saved profile reading and writing;
- deprecated aliases and compatibility re-exports;
- deploy-host and mock Kubernetes runtimes used only by that compatibility path;
- legacy Helm load-test scenario and runner;
- monolithic container, K3s curl, CLI, and load-test runners after their tasks replace them;
- recipe/composer/planner transition layers after all workflows use direct task factories;
- duplicate CLI groups and milestone tests that freeze deleted shell-script behavior;
- Grafana runtime unless a retained workflow demonstrates a requirement beyond the generated HTML report and Prometheus artifacts;
- development console entry points from the runtime package.

## CLI and TUI

CLI and TUI consume the same immutable `WorkflowPlan`. Dry-run rendering never invokes provider APIs. Live execution emits one normalized event stream. The TUI becomes a renderer and configuration assistant instead of a second orchestration implementation.

The current monolithic TUI is split by view responsibility only after the new application API exists. It does not receive workflow-specific branches.

## Testing strategy

Tests focus on contracts rather than historical command snapshots:

- scenario and environment parsing;
- role-to-executor binding;
- exact task order for the three workflow families;
- command rendering for local and external environments;
- reverse cleanup and primary-error preservation;
- provider adapter contract suites;
- one real local container E2E;
- one Multipass or external K3s E2E;
- CLI lifecycle E2E;
- one- and two-role load-test E2E;
- Kubernetes and container resource request/limit inspection.

Each old path is deleted immediately after its replacement passes the corresponding contract and E2E tests. There is never a permanent compatibility bridge.
