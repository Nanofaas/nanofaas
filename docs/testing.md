# Testing

Run Java tests with `./gradlew test`. Run the portable workflow libraries and tool independently:

```bash
cd tools/workflow-tasks && uv run pytest -q
cd ../controlplane && uv run pytest -q
```

Plan tests do not need Docker or a VM:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

Actual container validation needs Docker. With `--provision`, Kubernetes validation
can prepare Multipass, Azure, or Proxmox VMs; an external SSH host remains
user-managed. Load tests additionally need provider credentials and network access.
Managed VMs are deleted after the run unless `--keep` is set.

Use `--only`, `--from`, and `--until` to isolate tasks. Use `--keep` only when infrastructure must remain available for investigation.
