# Sub-5/6-b1-i — Unified `controlplane-tool images` command (Design)

**Status:** approved (design), pending implementation plan
**Date:** 2026-05-31
**Roadmap:** band-B of the bash-elimination effort. First slice of "consolidate the
three image-build implementations". This slice builds the single non-interactive
image build+push command and retires two of the three implementations; a follow-on
slice (b1-ii) repoints `release.py` at it.

## Problem

There are **three overlapping implementations** of "build and push the nanofaas OCI
images":
1. `scripts/build-push-images.sh` (210 LOC, non-interactive bash).
2. `scripts/image-builder/image_builder.py` (378 LOC, interactive `questionary`
   wizard, a standalone `uv` project with its own tests/venv/pyproject) — the
   richest matrix (16 targets, multi-arch buildx, native-image args, disk-retry).
3. `scripts/release-manager/release.py` → `build_and_push_arm64()` (~120 LOC, a
   third copy of the build matrix embedded in the release wizard).

This violates "one way to do things". This slice (b1-i) collapses (1) and (2) into a
single `controlplane-tool images` command; the next slice (b1-ii) makes (3) call it.

## Goal

Add `controlplane-tool images` — a non-interactive, flag-driven, **tested** command
that builds and (optionally) pushes the nanofaas OCI image matrix — and delete both
`scripts/build-push-images.sh` and the standalone `scripts/image-builder/` project.

## Architecture

- **CLI command** `images` in `tools/controlplane/src/controlplane_tool/cli/commands.py`
  (next to the existing singular `image`, which only builds the control-plane image).
  Non-interactive: all selection via flags. The interactive surface remains the TUI
  (not in scope here); the `questionary` wizard in `image_builder.py` is dropped.
- **New module** `tools/controlplane/src/controlplane_tool/building/image_matrix.py`
  (controlplane — the matrix is product-specific):
  - `IMAGE_MATRIX`: the canonical 16-target catalog, ported verbatim from
    `image_builder.py::IMAGES` (each: `type` gradle|docker, `task`/`dockerfile`,
    `image_param`, `group`, optional `wrapper_action`/`wrapper_profile`).
  - `resolve_current_version(repo_root)` — `version = '...'` from `build.gradle`.
  - `resolve_native_image_build_args()` — `NATIVE_IMAGE_BUILD_ARGS` env override, else
    `-H:+AddAllCharsets -J-Xmx{NATIVE_IMAGE_XMX|8g} -J-XX:ActiveProcessorCount={N}`
    with `N` from `NATIVE_ACTIVE_PROCESSORS` env or `os.cpu_count()` (floor 1, default 4).
  - `image_reference(name, tag, arch, use_arch_suffix)` →
    `ghcr.io/miciav/nanofaas/{name}:{tag}[-{arch}]` (no suffix for `multi`).
  - `plan_build_command(name, full_image, arch)` — returns the planned argv:
    - gradle targets → `bootBuildImage` task with `-P{image_param}={full_image}
      -PimagePlatform=linux/{arch|arm64,amd64}` and env
      `NATIVE_IMAGE_BUILD_ARGS=...` (+ `BP_OCI_SOURCE` for the control-plane wrapper
      target); `arm64` adds `-PimageBuilder=dashaun/builder:tiny
      -PimageRunImage=paketobuildpacks/run-jammy-tiny:latest`.
    - docker targets → `docker build --platform linux/{arch} -t {full_image}
      -f {dockerfile} {context}`; `multi` → `docker buildx build --platform
      linux/arm64,linux/amd64 ...`; include `--label org.opencontainers.image.source=
      https://github.com/miciav/nanofaas`.
  - `plan_push_command(full_image)` → `docker push {full_image}`.
  - An orchestration entry (`run_image_matrix(...)` / a small planner+runner) that, for
    the selected targets, plans build → (optional) push, honoring
    `--only/--no-push/--arch/--arch-suffix/--tag`, with the disk-retry-on-build wrapper
    ported from `image_builder.py` (`prune_docker_build_caches` + one retry).
- **Reuse:** the existing `building/gradle_ops.py` / `gradle_planner.py` for the gradle
  `bootBuildImage` invocation pattern and `building/image_ops.py::ImageOps` for the
  docker build/push commands. **Extend `ImageOps.build`** with optional `platform: str |
  None` and `labels: dict[str,str] | None` (the docker targets need `--platform` and the
  OCI-source `--label`, which the current `build()` does not emit). Prefer reusing/
  extending these ops over re-deriving argv where it keeps behavior identical.
- **Shell execution:** reuse the controlplane shell backend (`SubprocessShell` /
  `RecordingShell`) the other build commands already use, so dry-run + tests assert the
  planned commands rather than executing docker/gradle.

## CLI surface

```
controlplane-tool images [OPTIONS]
  --tag TEXT            Image tag (default: vVERSION resolved from build.gradle... see note)
  --only TEXT           Comma-separated target names or "all" (default: all)
  --arch [amd64|arm64|multi]   Target architecture (default: amd64 / native)
  --arch-suffix / --no-arch-suffix   Append "-{arch}" to the tag (default: off)
  --push / --no-push    Push after build (default: push)
  --runtime TEXT        Container runtime CLI (default: docker)
  --dry-run             Print the planned build/push commands only (NEW — no bash equivalent)
```

Note on tag: `image_builder.py` uses the raw `VERSION` (no `v` prefix) while
`build-push-images.sh` uses `vVERSION`. The two impls disagree. **Decision:** default to
the raw `get_current_version()` value (image_builder's behavior, since it is the richer/
tested impl); `--tag` overrides. This is called out so the plan/tests pin one behavior.

## Testing

- Port `scripts/image-builder/tests/test_image_builder.py` into
  `tools/controlplane/tests/test_image_matrix.py`, adapted to the new module:
  catalog completeness (all 16 target names + groups), `image_reference` formatting
  (single arch, `-arch` suffix, `multi` no-suffix), `resolve_native_image_build_args`
  (env override + computed default), and `plan_build_command` for representative gradle
  and docker targets across `amd64`/`arm64`/`multi`.
- Add command-level tests: `controlplane-tool images --dry-run --only ... --arch ...
  --no-push` emits the expected ordered build/push command list via a `RecordingShell`.
- Safety net: controlplane suite green, `lint-imports` 0 broken, `ruff` clean. No
  workflow-tasks change (this is product-specific controlplane tooling).

## Deletion / migration

- Delete `scripts/build-push-images.sh`.
- Delete the entire standalone `scripts/image-builder/` project (image_builder.py,
  its tests, pyproject, uv.lock, venv, egg-info).
- Update live docs that point at either (e.g. AGENTS.md / docs that mention image
  build) to `scripts/controlplane.sh images ...`. Historical plan archives
  (`docs/plans/*`) and frozen snapshots are NOT edited.
- `release.py::build_and_push_arm64` still has its own copy — **left untouched in this
  slice**; b1-ii repoints it. Note this explicitly so reviewers know the duplication is
  only partially resolved here.

## Out of scope

- `release.py` (the release wizard) — its image-build repoint is slice b1-ii; its
  semver/git/npm/PR logic is never in scope for band-B.
- Band-B siblings `native-build.sh`, `experiments/k6/run-all.sh` (later slices).
- The interactive wizard UX (the controlplane TUI is the interactive surface).
- CI changes: `.github/workflows/gitops.yml` uses `controlplane.sh image` (singular,
  control-plane only) and is unaffected; verify no CI references the deleted scripts.

## Success criteria

- `controlplane-tool images --dry-run` plans the full 16-target build+push matrix;
  `--only`, `--arch`, `--no-push`, `--arch-suffix`, `--tag` behave as specified.
- `scripts/build-push-images.sh` and `scripts/image-builder/` are gone; no live
  reference remains.
- Controlplane suite green (with the ported + new tests), lint-imports + ruff clean.
