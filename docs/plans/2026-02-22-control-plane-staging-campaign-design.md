# Control-Plane Staging Campaign Design

## Goal

Provide a structural and repeatable system to compare many candidate control-plane versions against a baseline, using:

- staging snapshots identified by manual slugs
- one immutable benchmark definition shared by all comparisons
- multi-run campaigns (default: 10) with median-focused reporting
- per-version image cache with explicit rebuild controls
- VM command execution via SSH only (no `multipass exec/shell`)

This design replaces flag-driven "ad hoc A/B" workflows with version-driven campaigns.

## Scope and Non-Goals

### In Scope

- Version lifecycle in staging (`staging`, `candidate`, `baseline`, `rejected`)
- Scaffolding from baseline/version or standalone source
- Campaign runner for `baseline vs candidate` with parameterized run count
- Aggregate reports with medians and variability
- Reusable image cache per version and per mode
- Support for externally managed VMs over SSH

### Out of Scope

- Auto-tuning of pass/fail guardrails
- Replacing all historical scripts in one change
- Forcing one programming language: versions are `generic-service`

## Core Principles

1. A comparison is valid only if both versions run under the exact same benchmark.
2. Benchmark configuration is global and immutable for a campaign.
3. Version identity is a manual slug, not a timestamp.
4. Single-run conclusions are insufficient; campaigns are multi-run.
5. `multipass` is lifecycle-only (create/delete/purge), never command execution.
6. All in-VM commands and file transfer use SSH/SCP transport.

## Filesystem Model

Root:

- `experiments/control-plane-staging/`

Structure:

- `experiments/control-plane-staging/benchmark/benchmark.yaml`
- `experiments/control-plane-staging/versions/<slug>/`
- `experiments/control-plane-staging/campaigns/<campaign-id>/`

Version directory:

- `versions/<slug>/snapshot/`
- `versions/<slug>/version.yaml`
- `versions/<slug>/hypothesis.md`
- `versions/<slug>/images/manifest.json`
- `versions/<slug>/images/<mode>/` (optional layer for exported metadata)

Notes:

- `hypothesis.md` is mandatory and captures differences and test intent.
- `snapshot/` is the runtime truth for that version (full snapshot, no partial overlays).

## Version Metadata

`version.yaml` fields:

- `slug: <manual-id>`
- `kind: generic-service`
- `status: staging|candidate|baseline|rejected|archived-baseline`
- `parent: baseline|version:<slug>|none`
- `created_at: <iso8601>`
- `source_commit: <optional>`
- `notes: <optional>`

`kind=generic-service` allows Java, Rust, Go, and future implementations without type branching in the registry model.

## Scaffolding and Lineage

Command (conceptual):

- `staging-manager create-version --slug <slug> --from <source>`

Allowed `--from`:

- `baseline`
- `version:<slug>`
- `none` (standalone)
- optional explicit source extensions later (`path:`, `git:`)

Behavior:

1. Create `versions/<slug>/`.
2. Materialize `snapshot/` from source.
3. Create `version.yaml` with lineage and status `staging`.
4. Create `hypothesis.md` template.
5. Initialize image cache metadata empty.

For `--from none`, snapshot can be an empty scaffold or provided externally before build.

## Benchmark Contract (Global, Shared)

Single source of truth:

- `experiments/control-plane-staging/benchmark/benchmark.yaml`

Must include:

- infrastructure settings (VM resources, namespace, deploy settings)
- k6 profile (stages, payload mode/pool, invocation mode)
- function selection
  - `function_profile: all` (default)
  - `function_profile: subset` + explicit function list
- platform mode matrix
  - default: `platform_modes: [jvm, native]`

Rule:

- baseline and candidate are always run with the same benchmark file and same matrix expansion.

## Image Cache Model

Per-version cache:

- `versions/<slug>/images/manifest.json`

Manifest includes, by mode:

- `image_ref`
- `image_id`
- `built_at`
- `build_fingerprint`
- `build_toolchain` (optional)
- `snapshot_fingerprint`

Cache hit requires all of:

1. matching mode entry
2. matching fingerprints
3. `docker image inspect` confirms image exists with expected `image_id`

Force controls:

- `--force-rebuild-images` (all modes)
- `--force-rebuild-mode jvm`
- `--force-rebuild-mode native`

Behavior:

- on hit: reuse cached image
- on miss: rebuild and update manifest

## Campaign Model

Command (conceptual):

- `staging-manager run-campaign --baseline <slug> --candidate <slug> --runs <N>`

Default:

- `N=10`

Run matrix per iteration:

1. baseline+jvm
2. candidate+jvm
3. baseline+native
4. candidate+native

Each cell captures:

- deploy log
- load test log/json
- JVM/Prometheus samples
- cell summary JSON

Campaign outputs:

- `campaigns/<id>/runs/run-XXX/...`
- `campaigns/<id>/aggregate-comparison.json`
- `campaigns/<id>/aggregate-comparison.md`

## Aggregation and Reporting

For each metric:

- baseline median/mean
- candidate median/mean
- delta median/mean/min/max

Primary decision signal:

- median deltas across runs

Secondary signal:

- variability (`min/max`, optional stddev later)

`N` is parameterized; reports are stable by construction and comparable across campaigns.

## VM Execution Policy

Repository policy:

- Use Multipass only for lifecycle when VM is locally managed:
  - `launch`, `start`, `delete`, `purge`, metadata discovery
- Never use:
  - `multipass exec`
  - `multipass shell`
- Always use SSH/SCP for remote command and file transport.

External VM mode:

- `E2E_VM_LIFECYCLE=external`
- `VM_IP` or `E2E_VM_HOST`
- optional `E2E_VM_USER`, `E2E_VM_HOME`, `E2E_KUBECONFIG_PATH`, `E2E_REMOTE_PROJECT_DIR`

## Compatibility with Existing Scripts

Current scripts already provide:

- A/B single-run comparison (`experiments/e2e-memory-ab.sh`)
- Batch aggregation with median support (`experiments/e2e-memory-ab-batch.sh`)
- SSH-first command transport in common library

This design formalizes and extends them into a version-centric workflow, without breaking the existing execution path immediately.

## Incremental Implementation Plan (High Level)

1. Add `staging-manager` command group for version registry/scaffolding.
2. Add build-cache command for per-version image cache population.
3. Add campaign orchestration command using global benchmark.
4. Add promotion command with state transition and history.
5. Migrate wizard entrypoints to call manager workflows.
6. Add docs and operator examples for local multipass lifecycle and external SSH lifecycle.

## Risks and Mitigations

- Risk: snapshot drift vs expected behavior.
  - Mitigation: explicit `snapshot_fingerprint` in manifests and campaign metadata.
- Risk: accidental benchmark mutation between runs.
  - Mitigation: copy benchmark into campaign root and include hash in report.
- Risk: cache reuse of wrong artifacts.
  - Mitigation: strict fingerprint + `image_id` verification before reuse.
- Risk: external VM inconsistencies.
  - Mitigation: explicit external lifecycle mode and SSH preflight check.

## Acceptance Criteria

1. A new version can be scaffolded from:
  - baseline
  - existing version
  - none (standalone)
2. Campaign runs with `--runs 10` produce aggregate report with median deltas.
3. `function_profile=all` is default; subset can be selected explicitly.
4. jvm/native matrix is executed identically for baseline and candidate.
5. Image cache is reused when valid and rebuild can be forced.
6. No script path for in-VM execution uses `multipass exec/shell`.
