# V2 Direction: Packaging and Release

## Summary

This direction focuses on distribution rather than functionality. The JavaScript SDK already works inside
the monorepo, but the current setup is still biased toward local `file:` dependencies and repo-internal
examples. V2 packaging would make the SDK easier to consume, version, and release in a repeatable way.

## Why It Matters

Local development is solved, but external adoption is not. If JavaScript support is meant to be part of
the product rather than just an internal capability, the repository needs a clear story for versioned SDK
artifacts, release flow integration, and reproducible image builds.

## Main Scope

- define the official release shape for `nanofaas-function-sdk`
- decide whether the SDK is published to npm, packed from CI, or both
- integrate JavaScript into `scripts/build-push-images.sh` and release-manager flows if that is still the canonical release path
- ensure examples and scaffolds no longer depend on accidental local build state

## Success Criteria

- a clean checkout can produce the JavaScript SDK artifact deterministically
- release tooling knows how to version and publish or package the SDK
- demo images can be built and published as part of the standard release flow

## Out of Scope

- VM-backed controlplane scenario integration
- adding new user-facing runtime features
- redesigning the scaffolding tool UX
