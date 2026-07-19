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

The adapted TUI groups the four supported scenarios under **Validation**, **CLI**,
and **Load Testing**; **Tools** provides validated scenario inspection and the
same Docker/SSH prerequisite check as `doctor`. After choosing a workflow, select
an environment and whether to plan or run it. Non-local runs also ask whether to
provision the environment and whether cleanup should keep the infrastructure.

Every menu and result view keeps one invariant branded header at the top. Plans
remain in that shared frame, while runs open the live workflow dashboard with
phase state, nested verification work, errors, and command logs. Press `l` to
hide or restore the log panel while a workflow is running.

When only the provider templates are present, the TUI environment picker still
shows **Azure (setup required)** and **Proxmox (setup required)**. Selecting
either entry displays setup guidance, writes no files, and starts no workflow.
The TUI never loads or executes `.yaml.example` templates. Copy
`azure.yaml.example` to `azure.yaml` or `proxmox.yaml.example` to `proxmox.yaml`,
then fill in the provider values and configuration. Keep external authentication
outside YAML: run `az login` for Azure, and provide the Proxmox password through
the environment variable named by `password_env`. Do not store secrets in YAML.

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

Copy the Azure or Proxmox example before CLI use and fill in provider values;
pass the concrete `azure.yaml` or `proxmox.yaml` path explicitly. CLI path
selection remains the caller's responsibility.
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

## Image releases

`controlplane-tool images` renders and builds the 52-cell image matrix
anywhere without publishing. Official releases run only through
`controlplane-tool release` on the pinned Azure profile; see
[docs/operations/image-releases.md](../../docs/operations/image-releases.md).
GitHub Actions never publishes images, and local/Multipass/Proxmox builds
cannot promote to GHCR.
