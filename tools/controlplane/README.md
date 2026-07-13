# NanoFaaS control-plane tool

The tool has one product model: a scenario says what to execute and an environment says where each role runs. Task implementations live in `tools/workflow-tasks`.

## Commands

The public surface is intentionally limited to:

- `run` — execute a scenario;
- `plan` — print the ordered tasks without executing them;
- `list` — list bundled scenarios;
- `inspect` — print validated scenario data;
- `doctor` — check required host commands;
- `tui` — use the same plan/run path interactively.

From the repository root:

```bash
scripts/controlplane.sh list
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh tui
```

## Environments

Local execution is the default. VM-backed workflows bind the `stack` and optional `loadgen` roles:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision
```

`--provision` creates or reuses Multipass, Azure, and Proxmox VMs, or reuses an
external SSH host. It then runs the shared Ansible bootstrap tasks and synchronizes
the repository. Managed VMs are deleted when the run finishes, including after a
failure; pass `--keep` to preserve them. External hosts are never deleted. Commands
run from `<home>/nanofaas` on remote machines. Copy `azure.yaml.example` or
`proxmox.yaml.example` to configure those providers; Proxmox reads its password from
the environment variable named by `password_env`.

Load testing uses the same command:

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision \
  --run-dir tools/controlplane/runs/experiment-1
```

The load test deploys the stack with Helm, registers its function, runs k6 with
autoscaling observation, captures Prometheus data and removes the Helm releases.
The stack address is discovered from the environment; URL flags are only needed
to override it. Use an environment with a `loadgen` role to place k6 on a dedicated
VM.

Task subsets are selected with `--only`, `--from`, or `--until`; `--keep` preserves
managed VMs and acquired platform infrastructure while still cleaning transient
processes.

## Development

```bash
cd tools/controlplane
uv sync --dev --locked
uv run pytest -q
uv run ruff check .
uv run basedpyright
uv run lint-imports
uv run controlplane-package-report
uv run pydeps controlplane_tool
```

GitNexus impact analysis is required before symbol changes and change detection before commits.
