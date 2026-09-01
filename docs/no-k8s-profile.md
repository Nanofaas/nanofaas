# Container-only validation

Kubernetes is optional for the container deployment provider. Build the selected control-plane module directly:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
```

With Docker running, execute the portable validation scenario:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
```

The scenario builds the required artifacts and images, starts the local control plane, registers and invokes the selected functions, verifies Docker soft/hard resource limits, and performs cleanup.
