# Control-plane operation

The Java control plane is built directly with Gradle and deployed to Kubernetes with Helm:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
helm upgrade --install nanofaas deploy/helm/nanofaas
```

The Python tool coordinates validation and experiments without owning task implementations. Scenarios under `tools/controlplane/scenarios-v2` compose tasks from `workflow-tasks`; environment files bind host, stack, and load-generator roles.

```bash
scripts/controlplane.sh inspect tools/controlplane/scenarios-v2/validate-k8s.yaml
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

Remote commands run through SSH and assume the repository at `<home>/nanofaas`. VM provisioning remains an explicit Ansible/provider concern.
