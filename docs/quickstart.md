# Quickstart

Build the Java platform:

```bash
./gradlew build
```

Inspect a workflow before running it:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

For k3s in Multipass:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
```

For an SSH-only remote VM, copy `tools/controlplane/environments/external.yaml.example`, set its host/user/home, provision the checkout with Ansible, then use the same `plan` or `run` command.

Azure and Proxmox use the same workflow. Copy the matching example from
`tools/controlplane/environments/`, fill in provider values, and run with
`--provision`. The tool deletes managed VMs when the workflow exits; use `--keep`
only when they must remain available for inspection. External VMs are never deleted.

Use `scripts/controlplane.sh tui` for the interactive client.
