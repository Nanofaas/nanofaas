# Weekly review corrections

> **For agentic workers:** execute the independent areas with regression tests and a final integrated review.

**Goal:** address the approved 19–25 October review and stop tracking raw execution evidence.

**Architecture:** preserve the existing execution and deployment contracts. Keep transient admission checks outside cached activation results, render collector configuration once for both installation and rollout identity, and remove unused wrapper APIs rather than introducing abstractions.

**Tech stack:** Java 25/Reactor, Helm, Python, Rust and TypeScript.

**Spec:** the weekly review and the user's instruction to address all findings, including repository logs and dumps.

## Constraints and review focus

- Run GitNexus impact before symbol edits; report HIGH/CRITICAL risk.
- Preserve activation idempotency, lifecycle checks and lease ownership.
- Preserve callback deadlines and physical release semantics in SDK production code.
- Keep deterministic fixtures, experiment sources/configuration, summaries and provenance.
- Remove generated evidence only from the current tree; do not rewrite Git history.
- Verify IPv6 defaults/explicit ports and rollout changes for all collector configuration.

## Tasks

- [x] Reproduce transient activation rejection, fix its cached-error poisoning, and run logical-operation tests.
- [x] Extract readable actuation phases without changing operation order; diagnose the fixed-round protocol test failure and run the offload suite.
- [x] Add failing Helm rollout/IPv6 regressions, fix rendering and run chart tests.
- [x] Remove unused upper-layer quiesce/resume wrappers while retaining meaningful lower-layer fencing tests; remove duplicate pool validation and run provider/control-plane tests.
- [x] Diagnose Rust/JavaScript corpus timing failures, correct their root causes and run both complete SDK suites.
- [x] Inventory generated artifacts, preserve provenance and inputs, remove raw output, document retrieval/retention and add ignore rules; verify no source/fixture was removed.
- [x] Review the integrated diff, run relevant regression checks and report remaining environment limitations.

Changes remain on `fix/october-review` for review; no publication or history rewrite is part of this work.

## Verification record

- Activation regression failed with the cached `preparation active` error before the fix and passes afterward.
- Helm rollout and IPv6 regressions failed before the template fixes and pass afterward.
- Raw evidence: 3,160 files / 443,955,516 bytes removed, including profiler results embedding object censuses or large failure traces; every SHA-256 verified against `550d8f7a`, every removed path covered by ignore rules. No experiment source, input configuration or Markdown file was removed. The obsolete `.gitattributes` file, which only exempted generated JUnit XML whitespace, was removed separately.
- Experiment tooling: P19 27 tests, archived dispatch analysis 41 tests, baseline reporting 2 tests passed after cleanup.
- ReplicaSlots validation simplification: 31 targeted container-runtime tests passed.
- Full offload suite: 159 tests / 40 suites passed. Protocol fixture now reuses its codec and captured batch; the original three-second budget is unchanged. READY confirmation ordering remains insertion-ordered after the actuator refactor.
- Provider changes: full container-runtime suite 70 passed / 3 skipped (external Rust workload prerequisite); control-plane registry package 146 passed.
- Scripts suite: 132 passed / 1 skipped; strict Helm lint passed.
- Standard SDK commands: `npm test` 96 passed (including TypeScript compilation); `cargo test` in `sdks/rust` 110 passed. Production budgets and shared corpus expectations are unchanged; fixture readiness precedes lifecycle timing and JavaScript files run serially while invocation concurrency tests remain enabled.
- Independent final review found no Critical, Important or Minor issues. Manifest membership and ignore coverage were independently checked. `git diff --check` passed.
- Final GitNexus `detect-changes --scope all --limit 2000`: 85 indexed symbols and 9 affected processes, HIGH risk, with no partial/truncated warning. The high-impact paths were covered by the offload/provider/registry regression suites; raw graph output remains outside the repository.

Verification limits: no live Kubernetes rollout/IPv6 connectivity or native campaign was run; the four skipped tests require external runtime prerequisites. Those deployment checks remain environment validation, not claimed by the unit/integration results above. Git history size reduction is outside this change.

Decisions: keep lower-level strict resume/fencing behavior and its tests while removing unused upper-layer wrappers. Keep historical evidence recoverable through Git and the removal manifest; current P19 dossiers intentionally require restoration before full integrity verification.
