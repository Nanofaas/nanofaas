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

For a hard RAM limit without container swap, set
`nanofaas.container-local.strict-memory=true` on the control plane. This optional
policy defaults to `false` and applies to both the `docker-java` and `docker`
adapters. Every managed function must then declare a positive
`resources.limits.memoryMiB`; creation fails before allocating a port when that
limit is absent. Docker receives equal memory and memory-plus-swap limits, so the
container cannot extend its RAM allocation using swap. Requests still express
reservations and do not preallocate memory.

Experiments must also verify cgroup v2 `memory.max` in bytes,
`memory.swap.max=0`, and `cpu.max`, and record usage and OOM evidence. Docker
configuration alone does not prove kernel enforcement.
