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

`--provision` is an explicit, idempotent first-run step for Multipass and external SSH environments. It creates or reuses the Multipass VM, or reuses the configured external host, then runs the separately maintained Ansible tasks and synchronizes the repository. It never destroys a VM. Omit the flag on later runs. Azure and Proxmox provisioning remains provider-specific. Commands run from `<home>/nanofaas` on remote machines.

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
to override it. On later runs, omit `--provision`. Use
`environments/multipass-two-vm.yaml` to place k6 on a dedicated VM.

Task subsets are selected with `--only`, `--from`, or `--until`; `--keep` preserves acquired infrastructure while still cleaning transient processes.

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
