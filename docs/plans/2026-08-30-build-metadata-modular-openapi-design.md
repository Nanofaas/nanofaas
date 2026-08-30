# Build metadata and modular OpenAPI design

## Goal

Make each control-plane artifact identify the code, modules, container bases,
and runtime actually under test. At the same time, make the OpenAPI contract
match the modules selected for that artifact without coupling the core to
module-specific controllers.

## Build metadata contract

`GET /modules/build-metadata` returns a typed payload:

```json
{
  "version": "0.4.0",
  "revision": "a1b2c3d4",
  "dirty": false,
  "modules": ["async-queue", "autoscaler", "build-metadata"],
  "build": {
    "type": "jvm",
    "variant": "jvm-g1-c2",
    "optimization": "c2",
    "baseImages": {
      "builder": "eclipse-temurin:25-jdk",
      "runtime": "gcr.io/distroless/base-debian13:nonroot"
    }
  },
  "runtime": {
    "architecture": "arm64",
    "kernelVersion": "6.8.0-52-generic",
    "javaVersion": "25.0.1",
    "vm": "OpenJDK 64-Bit Server VM",
    "garbageCollectors": ["G1 Young Generation", "G1 Concurrent GC"]
  }
}
```

Gradle generates `META-INF/nanofaas-build.properties` with version, Git
revision, dirty state, resolved module selection, build type, variant, and
optimization. The Dockerfiles declare their builder and runtime images through
build arguments, use those arguments in `FROM`, and copy the same references
into the final image as environment variables. Runtime values come from Java
system properties and management beans. Architecture names are normalized to
`arm64`, `x86_64`, or left absent when unknown.

Missing data is represented by JSON `null`, not a sentinel string. Missing or
malformed diagnostics never prevent the control plane from starting. Raw JVM
arguments are not exposed because they may contain secrets. Timestamps,
hostname, pod identity, hardware metrics, and the resulting image tag are out
of scope. Base-image digests can be added when Dockerfiles pin them explicitly.

## API ownership and dispatch

The core owns stable transport concerns for core operations: HTTP binding,
validation, response mapping, and common errors. A module changes their
behavior through a typed core SPI. For example, `:invoke` remains a core route,
while the selected queue implementation handles the invocation through the
existing queue extension points. No generic route dispatcher is introduced.

Multiple modules may affect one core operation through distinct typed
extension points. Their OpenAPI overlays may compose when they touch different
responses, headers, or descriptions; conflicting changes to the same element
fail the build. Runtime behavior never uses an implicit priority. Competing
implementations of one exclusive SPI are invalid, while intentional cooperation
is represented by separate extension points or an explicit typed chain.

The current offload behavior is preserved. `offload.mode=always` works without
a queue module, while pressure-triggered offload integrates with `sync-queue`.
Making offload depend on `async-queue`, or introducing a generic invocation
handler hierarchy, is outside this change and will be reconsidered with the
offload design itself.

A route that does not exist in the core belongs directly to its module. The
module contributes a Spring controller through auto-configuration, so requests
reach that controller without a core controller knowing about it. If the module
is absent, both the route and its OpenAPI operation are absent. Duplicate HTTP
method and path declarations are errors; there is no implicit ordering or
last-writer-wins behavior.

## Composable OpenAPI contract

The existing contract is split into a core document and module-owned fragments:

```text
openapi/core.yaml
platform/modules/build-metadata/openapi.yaml
platform/modules/runtime-config/openapi.yaml
                         |
                         v
            Gradle module selection and merge
                         |
                         v
             artifact-specific openapi.yaml
```

Gradle composes the contract after the module plugin resolves the selected
modules. New module routes add paths and schemas. A module may augment a core
operation with responses, headers, or descriptions that apply when that module
is selected. Such changes must be declared as explicit operation overlays;
silent replacement is forbidden. Incompatible overlays or duplicate component
names fail the build.

"Active" means included in the resolved build. Runtime-conditional endpoints
remain documented with their enabling condition, keeping the artifact contract
stable and usable for client generation. Each operation is keyed by HTTP method
and normalized path. The composed contract is packaged with the control plane
and becomes the authoritative contract for that artifact.

## Verification

Tests cover generated build properties, resolved modules, architecture
normalization, runtime metadata, nullable missing values, and the complete HTTP
payload. JVM and native builds verify their respective build type.

OpenAPI verification scans core controllers and controllers from every selected
module. It checks that each public route exists in the composed contract, that
each fragment belongs to a selected module, and that additions and overlays do
not conflict. A focused integration test calls the build-metadata endpoint and
proves that it returns real identity data rather than the current placeholder.

No new runtime documentation framework, central controller registry, or Docker
and Kubernetes API access is introduced.
