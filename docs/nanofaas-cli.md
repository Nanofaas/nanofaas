# NanoFaaS CLI validation

The platform CLI lifecycle is represented by `tools/controlplane/scenarios-v2/cli.yaml` and uses the same workflow engine as platform validation.

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/cli.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/cli.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

The scenario builds the CLI, applies selected function manifests, lists functions, invokes them, and deletes them during cleanup. The environment decides whether those commands run on the host or stack VM.
