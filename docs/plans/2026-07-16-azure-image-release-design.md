# Azure Image Release Design

**Date:** 2026-07-16
**Status:** Approved

## Goal

Provide one controlled release workflow for all NanoFaaS platform, service, and
function images. A release must pass source tests and an AMD64 performance gate
before any official image tag is changed. Official images are published to
GHCR only from an Azure release run.

The existing `controlplane-tool images` implementation and tests should be
restored and updated instead of recreating the image matrix in GitHub Actions
or shell scripts. Dockerfile-based cells are rendered to Docker Buildx Bake,
while Spring native cells keep using the existing Gradle buildpack tasks. The
command remains usable for experiments on the host, Multipass, and Proxmox,
but those environments cannot promote official tags.

## Execution environments

Azure is the authority for releases. The release uses a pinned VM size, OS
image, region, disk configuration, and stack topology so successive results
remain comparable. The normal topology already fits the workflow:

- the `stack` VM builds images, hosts the local registry, and runs k3s;
- the `loadgen` VM produces load independently from the system under test;
- QEMU on the stack VM builds and smoke-tests ARM64 images;
- GHCR receives images only after all release gates pass.

Building directly on the stack VM is the default because it avoids another VM
and image transfer. If a separate builder is later required, the existing
provider `transfer_from` and `transfer_to` operations move a `docker save`
archive to the stack VM. A checksum and image digest are verified before and
after transfer. The destination loads the archive and pushes it only to the
stack VM's local registry. No external candidate registry is required.

Host, Multipass, and Proxmox runs use the same image plan, generated Bake
definition, and native buildpack commands for development. They may build,
test, export, and use their own local registry, but release promotion and
stable-tag updates must fail outside a verified Azure release context.

## Release flow

The release is deliberately ordered so expensive ARM64 emulation happens only
after the source and AMD64 candidate prove viable:

1. Validate the requested version and the clean, exact source commit.
2. Run the complete Java, Python, watchdog, CLI, and workflow-task test suites.
3. Build the complete AMD64 matrix, including JVM and native flavors where
   applicable.
4. Store AMD64 images in the Azure stack VM's local registry and deploy those
   exact artifacts to k3s.
5. Run functional checks and the load-test scenario from the dedicated loadgen
   VM.
6. Collect Prometheus, k6, lifecycle, and resource metrics and evaluate the
   configured regression thresholds.
7. If the AMD64 gate passes, build the complete ARM64 matrix under QEMU.
8. Run ARM64 functional smoke tests under QEMU. Emulated results are not used
   as performance measurements.
9. Push the already-tested AMD64 and ARM64 artifacts to GHCR without rebuilding
   them.
10. Create multi-architecture manifests, attest the released artifacts, update
    the final version tags, and record the release metrics.

Any failure before step 9 leaves GHCR release tags unchanged. Failure while
publishing must not expose a partially updated release: architecture-specific
immutable tags are uploaded first, manifests are verified, and mutable aliases
are changed last.

## Image and tag policy

GHCR is the canonical public registry. Release artifacts use immutable
architecture tags followed by multi-architecture flavor manifests.

For images with JVM and native flavors at version `v0.18.0`:

- `v0.18.0-amd64-native` and `v0.18.0-arm64-native` identify architecture
  artifacts;
- `v0.18.0-native` is their multi-architecture manifest;
- `v0.18.0` is an alias of the same native manifest;
- JVM artifacts follow the equivalent `*-amd64-jvm`, `*-arm64-jvm`, and
  `v0.18.0-jvm` structure.

Images without flavors use `v0.18.0-amd64`, `v0.18.0-arm64`, and the
multi-architecture `v0.18.0` manifest. `latest` is not part of the correctness
contract and, if retained, is updated only after the versioned release is
complete.

The logical matrix is owned by `controlplane-tool images`, not workflow YAML or
a second hand-maintained target list. It discovers the current platform,
service, and function catalog and fails when an expected cell is missing. It
renders the Dockerfile subset deterministically as Buildx Bake JSON with one
group per architecture; native Spring cells remain ordinary Gradle task specs.
The command supports planning and dry runs, including Bake `--print`, so the
same matrix can be inspected without building or publishing.

## Release guard and versioning

Image building is portable; publishing stable tags is intentionally not. The
release command must require all of the following before promotion:

- an Azure environment configured as a release environment;
- the expected stack and loadgen roles;
- an explicit release mode and semantic version;
- a source commit matching the requested release;
- successful test, benchmark, and ARM64 smoke-test evidence from the current
  run;
- authenticated GHCR credentials with the minimum package-write permission.

Version preparation updates all authoritative project version locations in one
reviewable change. The run must not invent a version or silently modify source
files. Promotion consumes the prepared version and records the exact source
commit and image digests.

## Performance records

The initial comparable performance series is explicitly identified as Azure,
AMD64, native, and a versioned load-test profile. Results from local,
Multipass, Proxmox, other VM sizes, JVM flavor, ARM64, or changed scenarios are
kept in separate series and never aggregated together.

Each release retains raw k6 output, Prometheus snapshots, lifecycle evidence,
and the generated HTML report as run artifacts. A compact record committed to
`docs/performance/releases/v0.18.0.json` contains the profile identity,
source commit, image digests, run count, thresholds, and aggregates such as
throughput, error rate, p50/p95/p99 latency, queue wait, cold start, CPU, heap,
and peak replicas. `docs/performance/history.md` presents the important metrics
version by version.

The benchmark runs three times on a clean pinned Azure environment and uses the
median for the release comparison. The first accepted release establishes the
baseline. Thresholds belong to the versioned scenario configuration rather
than release implementation code.

## Validation and failure handling

Unit tests cover matrix expansion, deterministic Bake rendering, tag
construction, provider restrictions, phase ordering, version validation,
missing cells, and promotion refusal.
Workflow tests prove that ARM64 build follows the AMD64 performance gate and
that no external push occurs on earlier failure. Provider tests cover the
optional archive transfer and digest verification path.

An Azure dry run must show the complete plan without provisioning, building, or
publishing. A non-release Azure experiment remains non-publishing by default.
Interrupted runs preserve logs and metric artifacts, remove temporary local
registry content during cleanup, and never reinterpret partial evidence as a
successful gate.

ARM64 performance is explicitly out of scope for the first implementation.
QEMU provides build and functional compatibility only. A future ARM64
performance series requires a pinned native Azure ARM64 stack and its own
baseline.
