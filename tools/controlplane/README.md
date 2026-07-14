# NanoFaaS control-plane tool

The control-plane tool is the orchestration entry point for provisioning and
validating NanoFaaS. A scenario defines *what* to execute; an environment binds
each role to a local host, a managed VM, or an external SSH host. Task
implementations live in `tools/workflow-tasks` so that this package remains the
product-facing composition layer.

It is intentionally separate from the `nanofaas` CLI: the CLI calls the
control-plane HTTP API to manage functions, while this tool creates VMs, installs
k3s and Helm, distributes images, and runs end-to-end or load-test workflows.

## Prerequisites

- [uv](https://docs.astral.sh/uv/) on the machine that runs the tool.
- Docker or a compatible runtime for container scenarios and image builds.
- Multipass for local VM-backed Kubernetes validation.
- SSH and Ansible for an external VM; provider credentials for Azure or Proxmox
  when using managed VMs.

The canonical launcher is always run from the repository root. It creates and
uses the locked uv environment automatically:

```bash
scripts/controlplane.sh --help
scripts/controlplane.sh doctor
scripts/controlplane.sh list
```

## First validation

Inspect the plan before executing it. The container scenario is the smallest
local path and does not require a Kubernetes cluster:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

The interactive UI uses exactly the same plan/run implementation:

```bash
scripts/controlplane.sh tui
```

## Commands

| Command | Purpose |
|---|---|
| `list` | List bundled scenarios. |
| `inspect <scenario>` | Print validated scenario data. |
| `plan <scenario>` | Render the ordered operations without executing them. |
| `run <scenario>` | Execute a scenario in its selected environment. |
| `doctor` | Check commands required by the local host. |
| `tui` | Select and run the same workflows interactively. |

Use `--help` after any command to see its supported options. The supported
scenario files are in `scenarios-v2/`.

## Environments and VM lifecycle

Local execution is the default. VM-backed workflows bind the `stack` and optional
`loadgen` roles through an environment file.

| Environment | Use case | Lifecycle |
|---|---|---|
| none | Local container validation | No VM is created. |
| `multipass.yaml` | Local k3s VM | Managed VM; removed after the run by default. |
| `external.yaml.example` | Existing SSH-only VM | Never created or deleted by the tool. |
| `azure.yaml.example` | Azure VM | Managed VM; removed after the run by default. |
| `proxmox.yaml.example` | Proxmox VM | Managed VM; removed after the run by default. |

For a Multipass-backed Kubernetes run:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision
```

`--provision` creates or reuses a managed VM, runs the shared Ansible bootstrap,
and synchronizes the repository. Managed VMs are deleted even after a failure;
pass `--keep` when they must remain available for inspection. External hosts are
never deleted. Remote commands run from `<home>/nanofaas`.

Copy the Azure or Proxmox example before use and fill in provider values.
Proxmox reads its password from the environment variable named by `password_env`.

## Load testing

Load testing follows the same plan-first workflow:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision \
  --run-dir tools/controlplane/runs/experiment-1
```

The workflow deploys the stack with Helm, registers the selected function, runs
k6, observes autoscaling, and captures Prometheus data. Use an environment with
a `loadgen` role when k6 must run on a dedicated VM. `--only`, `--from`, and
`--until` select task subsets.

## Development

For direct development inside this package:

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

## Related documentation

- [Repository quickstart](../../docs/quickstart.md)
- [Control-plane operation](../../docs/control-plane.md)
- [E2E tutorial](../../docs/e2e-tutorial.md)
- [NanoFaaS CLI guide](../../docs/nanofaas-cli.md)
