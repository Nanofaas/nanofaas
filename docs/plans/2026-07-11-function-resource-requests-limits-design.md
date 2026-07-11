# Per-function Resource Requests and Limits

## Goal

Expose one backend-neutral resource contract for individual functions and apply it consistently to Kubernetes and the local Docker/Podman provider.

## Contract

Replace the existing Kubernetes-shaped `ResourceSpec(cpu, memory)` with nested requests and limits:

```yaml
resources:
  requests:
    cpu: 0.25
    memoryMiB: 256
  limits:
    cpu: 1
    memoryMiB: 512
```

The Java model uses `BigDecimal` for CPU cores and `Integer` for memory in MiB. Sections and individual values are optional. The old string format (`cpu: 250m`, `memory: 256Mi`) is removed rather than retained as a compatibility layer.

Validation happens at the control-plane boundary before registry or provider effects. Values must be positive, CPU accepts at most three decimal places, and a request must not exceed its corresponding limit. Invalid input returns HTTP 400 with a field-specific message.

## Provider Mapping

Kubernetes maps CPU cores and MiB to native quantities. For example, `0.25` CPU becomes `250m`, while `256 memoryMiB` becomes `256Mi`. Requests and limits remain separate in `ResourceRequirements`.

The local container provider passes the same model to every replica. Docker and Podman receive:

- request CPU as `--cpu-shares`, using `1 CPU = 1024 shares`;
- limit CPU as `--cpus`;
- request memory as `--memory-reservation`;
- limit memory as `--memory`.

CPU shares are a relative weight under contention, not an absolute reservation. No local capacity scheduler or host-resource accounting is added. If memory request equals memory limit, the adapter omits the redundant reservation flag.

## Updates and Failure Handling

Changing Kubernetes resources patches the Deployment pod template and triggers the normal rolling update. Changing resources for an existing local deployment recreates its containers so that the runtime flags take effect. Failed recreation must use the existing compensating cleanup and must not leave partially provisioned containers.

No resource flags are emitted for omitted values. Helm resource values for the control plane and supporting services remain unchanged because they are separate from per-function resources.

## Verification

Tests cover common-model serialization and validation, exact Kubernetes resource generation, exact Docker/Podman CLI flags, omitted values, propagation to every local replica, and resource updates. `openapi.yaml`, active YAML examples and fixtures, and Kubernetes documentation are updated with the new contract.
