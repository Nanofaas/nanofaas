# NanoFaaS

NanoFaaS is a research FaaS platform with a Java 21/Spring Boot control plane, Java and Python function runtimes, container and Kubernetes deployment providers, and a CLI.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew :control-plane:bootRun
```

Docker-backed and Kubernetes tests require their respective runtimes. Kubernetes deployment is performed through the Helm chart in `deploy/helm/nanofaas`.

## Control-plane workflows

The Python control-plane tool reads versioned YAML scenarios and environment bindings. Its only commands are `run`, `plan`, `list`, `inspect`, `doctor`, and `tui`.

```bash
scripts/controlplane.sh list
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

The external environment is suitable for a remote VM reachable through SSH and provisioned separately with Ansible. See `tools/controlplane/README.md` and `docs/quickstart.md`.
