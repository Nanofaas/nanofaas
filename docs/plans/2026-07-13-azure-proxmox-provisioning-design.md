# Azure and Proxmox Provisioning Design

## Goal

Restore Azure and Proxmox as first-class providers in the simplified control-plane tool without restoring the removed profile and scenario-adapter architecture.

## Provider contract

`EnvironmentConfig` remains the single source of provider and role configuration. A small mapper builds the existing `VmRequest` model for each role. Multipass keeps using `VmOrchestrator`; Azure and Proxmox use their existing provider implementations; external hosts use SSH and are never owned by the tool.

Execution and artifact download use the provider-native `exec_argv` and `transfer_from` interfaces. This preserves Azure key handling and Proxmox SSH NAT handling instead of duplicating them in the CLI.

## Provisioning lifetime

Provisioning is a context manager around the complete product workflow. Managed VMs (Multipass, Azure and Proxmox) are destroyed in reverse acquisition order on success and failure. `--keep` explicitly preserves them. External hosts are bootstrapped but never destroyed because the tool does not own them.

If provisioning fails after a VM has been acquired, that VM is still released. Cleanup errors are reported without silently replacing the original workflow failure.

## Connectivity

Bootstrap planners receive the resolved SSH endpoint and provider key without changing the widely shared `VmRequest` schema. Azure load-test NodePorts are opened when the stack VM is created. Proxmox publishes Prometheus through its existing routing manager; the load generator reaches the control plane through the guest network as in the previous working implementation.

Explicit control-plane and Prometheus URLs always take precedence over provider discovery. Dry-run planning uses stable placeholders and performs no cloud calls.

## Configuration

Azure configuration includes resource group, location, image, SSH key, and separate default sizes for stack and load-generator VMs. Proxmox reads its password from the configured environment-variable name and never stores the secret in YAML. Role configuration continues to own VM names, CPU, memory, disk, user, home and kubeconfig paths.

## Verification

Unit tests cover request mapping, provider-native execution, URL resolution, bootstrap endpoints, cleanup order, cleanup after failure, `--keep`, and external ownership. Existing Multipass/external tests remain regression coverage. Real provider E2E is run only when the required credentials and environment files are available.
