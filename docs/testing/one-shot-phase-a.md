# NanoFaaS one-shot: phase A handoff

Phase A is implemented on `codex/one-shot-nanofaas`, created from main aligned
with origin at `a62a743f16205b3d4d3ce2945f857a88c559d83e`. The verification record below distinguishes the local functional gate from
qualification required for a scientific campaign.
NanoLab, Sonata workflows, Multipass provisioning and Azure experiments are
outside this change.

## Runtime and contracts

The algorithm implements base decentralized auction without PG/local search or
hierarchy. The pinned read-only reference is DFaaSOptimizer branch
`feat/uv-migration-and-extended-tests`, commit
`71899f720a4ffffd070ebf7ddc5b74afddfde9c5`. Checked-in fixture bytes and source
hashes are in the [reference manifest](../../platform/modules/offload/src/test/resources/one-shot/reference/manifest.json).
Java tests consume 80 frozen LSP/LSPr_x cases plus independent exhaustive cases;
Python is not a runtime dependency. The helper transcript covers its declared
feasible starting allocation; it does not claim full simulator-run equivalence.

The public boundary uses [schemas v1](../contracts/one-shot/README.md), the
composed OpenAPI, and [one-shot administration and routing](../one-shot.md).
P2P identity, incarnation and invocation endpoint come from `p2p-api`;
`forecasting-api` expresses future external arrivals in requests/s. Optional
implementations do not depend on each other's implementation classes.

Forecasts freeze by configuration/profile/trace revision and function generation.
Oracle traces must match the explicit q grid; EWMA residues go to terminal cloud.
Profiles match image, input, CPU/RAM, runtime, backend, co-location and environment.
Synthetic `workflow-validation` profiles are not scientific calibration.

Desired replicas never imply ready capacity. Each Rust replica has one physical
handler slot, with positive release proof tied to execution, attempt and runtime
incarnation. HTTP timeout does not release physical occupancy; uncertain release
quarantines the slot. Function-wide concurrency must cover all eligible replicas;
SDK handler concurrency and STATIC_PER_POD target remain one. Generation-scoped
ownership excludes other replica writers and protects memory during transitions.
Expiry alone never hands occupied resources to another owner.

Only ready, acknowledged grants can route traffic. Origin choices are local,
neighbor or cloud; a forwarded request can never forward again. Trusted execution
node attribution survives idempotent replay separately from handler headers.
Infrastructure errors do not invent an execution destination or trigger a second
remote destination after sending.

## Local process gate

The recipes select Docker deployment rather than the Kubernetes provider selected
by `controlPlaneModules=all`. The explicit task boots three edge processes and a
terminal cloud, with separate main/management ports and isolated container labels.
Both Docker CLI and Docker Java adapters are exercised. Missing Docker, image or
native executable is a failed prerequisite, never a skipped pass.

```sh
docker build -f functions/rust/one-shot-workload/Dockerfile -t nanofaas-one-shot-workload .
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew :control-plane:nativeCompile -Precipe=recipes/one-shot-local-native.yaml -PnativeParallelism=2 -PnativeBuildMemory=4g
scripts/assert-native-executable.sh platform/control-plane/build/native/nativeCompile/control-plane
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-native.yaml -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"
```

Use JDK 25, the GraalVM pins in `gradle.properties`, and an accessible Docker
context. An explicit nativeCompile produces a host binary; assembleRecipe uses
the native recipe's container builder for Linux packaging. Spring AOT chooses
conditional beans at build time; the enabled native recipe includes one-shot and
forecasting. Runtime disable fences their lifecycles, routing and administrative
API on the cloud. The baseline native composition is verified separately.

The fixed Rust input is `iterations=500000`, `working_set_bytes=4096`, `seed=7`.
The synthetic D=0.5 s, utilization=0.8, RAM=128 MiB, q=1 requests/s and R≤1
exercise allocation; they are not measured service-model values. The diagnostic
period is 120 s with a 5 s auction budget and 5 s preparation budget, satisfying
the configured small operational fraction. These values do not qualify Azure.

The first oracle epoch offers [6,0,0] requests/s and checks all four destinations.
The second offers [0,6,0], observes a paused replica as unready before the auction,
and removes another peer before traffic. Results compare 30 unique logical
invocations with 30 physical occupancy completions despite repeated idempotency
keys, and retain censored degraded events. Plans, responses, events and both
runtime/control-plane metrics remain under
`platform/modules/offload/build/test-diagnostics/`; CI uploads them on failure too.

## Verification record

Verified locally:

- A1–A13 are committed through `e0c874b9`; A14 boundary and packaging fixes
  are fixed at `c96c7bab`. Each task has its own verification.
- Real JVM and host-native process gates were refreshed after the final fixes
  and passed in 3m46s and 3m41s. Both
  conserved 30 logical / 30 physical completions and retained degraded events.
- Full P2P suite: 147 tests, no failures or skips. Core-only HTTP checks passed
  separately from the composition with optional modules.
- Required release checks passed with the pinned containerd dependencies in an
  explicitly selected local Maven repository. Their source revisions and JAR
  hashes match `deploy/containerd-rootless/dependencies.env` and the bootstrap
  receipt; the runtime does not acquire an unpinned dependency.
- Rust SDK: 95 unit and 6 integration tests, no failures or skips; fmt and Clippy
  with warnings denied passed. The diagnostic function's bounded deterministic
  workload test passed. The Java physical proxy test ran against the real host
  Rust executable and passed, with no environment-based skip.
- JSON contract suite: 18 tests passed; frozen solver parity and independent
  exhaustive cases passed in the Java suite.
- Recipe plugin: 233 tests passed on Linux, without exclusions. Its shell/native
  fixtures require Linux and Git. Real staging ownership and supported Rust report
  regressions passed; safety checks still refuse unrelated output directories.
- Boundary regressions reproduced then fixed numerical fixed-flow admission,
  bounded EWMA catalog turnover, timing-policy qualification, duplicate hop
  metadata, mutation of archived replay headers and mismatched peer flow units.
- All protocol phases carry explicit frozen q; different peer grids fail closed.
  Shared fractional-grid convergence and mixed-grid censorship are tested.

Final whole-branch review found two Important issues and no Critical/Minor
issues. Both were reproduced with failing regressions and fixed in one pass at
`cf8bf68f`: fractional JSON values cannot populate integer fields, and actual
handler failures retain their execution node only with matching physical runtime
evidence. Forged backend headers, admission refusals and stale attempts do not
invent execution evidence. Integer/boolean/string capability metadata is also
strictly checked before admitting a physical single-handler runtime.

Post-fix `releaseChecks` and full affected suites passed in 3m14s: common 43,
execution 353, container runtime 43, core 812 (three existing composition skips),
offload 86 and forecasting 16; zero failures/errors and no physical Rust skip.
Neighbor error attribution survives archived idempotent replay over real HTTP.
Core-only HTTP/composition regressions passed again in 16s. No second reviewer
was dispatched; the reproduced tests and green suites verify the single fix pass.

The numerical solver rejects utilization >1, nonintegral fixed flows and
capacities >=1e9 flow-grid units per replica, where the reference absolute
rounding tolerance can otherwise allocate zero replicas to positive traffic.
Rescale q or use an eligible service model; no partial allocation is published.

Static checks remain active. Narrow exclusions name exact classes, fields and
methods for live injected collaborators, defensive immutable copies, existing
execution monitor ownership, serialized lifecycle increments and intentional
integer RAM flooring. They do not disable bug categories or whole modules.

The all-module native compiler needs more heap than the smaller one-shot recipe
in this environment. A 4 GiB attempt was stopped after measured full-GC thrash;
the retry uses 8 GiB compiler heap, without changing runtime resource settings.
After final fixes, all-module native compilation passed in 3m55s and actual
artifact/HTTP 404/400 checks passed; one-shot native compilation passed in 2m15s.

## Limits and phase B responsibilities

CONVERGED refers to closed local participating neighborhoods; it is not an
unobserved global termination proof. The orchestrator must check every node's
outcome. Missing messages, changed incarnations, deadlines, round limits,
unhealthy clocks and partial readiness fail closed or explicitly degrade.

Scheduled execution requires at least 20 complete successful operational samples,
a bounded p99 and factor-two margin against both lead time and the declared
period fraction. Solver time, auction duration and full physical preparation are
separate observations. Censored samples never qualify the schedule. The local
process gate is functional evidence, not timing qualification for another host.

Phase B can consume these contracts to implement and execute Sonata workflows on
Multipass: provisioning, real local service calibration, complete auction timing
qualification, oracle campaigns, collection and cleanup. Scientific Azure work is
phase C, a separate future task requiring new target calibration, environment
fingerprints and timing qualification before choosing T. Neither B nor C is
needed to supply a missing NanoFaaS runtime feature.

## Local timing diagnostics

These observations are from node B in the refreshed two-epoch synthetic process
scenario at code commit `cf8bf68f`, not a calibrated benchmark or Azure
qualification. Evidence directories:

- JVM: `platform/modules/offload/build/test-diagnostics/nf-one-shot-6004377846393148900`
- Host native: `platform/modules/offload/build/test-diagnostics/nf-one-shot-5905483229604603210`

| Build | Solver max | Auction max | Qualified operational duration |
| --- | --- | --- | --- |
| JVM | 0.845458 ms | 79.554833 ms | 351.236916 ms |
| Host native | 0.006041 ms | 9.882375 ms | 200.126166 ms |

Each run records two solver/auction samples and only one qualified operational
sample: the readiness-degraded epoch is censored. Twenty successful samples remain
required for a real scheduled campaign; these two runs cannot select its period.

Packaged startup smoke checks run with the image default user, a loopback-published
port and no host socket/network mounts. The native/JVM host process scenario tests
the physical Docker Java path. Actual daemon permissions and deployment networking
must still be verified in phase B on its target machines.

## Repeatable verification commands

Run suites sequentially in one checkout. Build the host Rust workload and export
`NANOFAAS_ONE_SHOT_WORKLOAD_BINARY` to its absolute executable path so the physical
timeout/drain test runs. Use the repository bootstrap script and its pinned
receipt when enabling the containerd provider's local Maven dependencies.

```sh
./gradlew releaseChecks -PcontrolPlaneModules=all --continue
./gradlew :execution-runtime:test :container-deployment-runtime:test :control-plane-modules:offload:test :control-plane-modules:forecasting:test :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=all
./gradlew :control-plane:test -PcontrolPlaneModules=none --tests '*CoreOnlyApiTest' --tests '*InvocationControllerTest'
./gradlew -p platform/gradle-plugin test
cargo test --locked --manifest-path sdks/rust/Cargo.toml
cargo fmt --manifest-path sdks/rust/Cargo.toml --check
cargo clippy --locked --manifest-path sdks/rust/Cargo.toml --all-targets -- -D warnings
./gradlew assembleRecipe -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew assembleRecipe -Precipe=recipes/one-shot-local-native.yaml -PnativeParallelism=2 -PnativeBuildMemory=8g
python3 scripts/one-shot/smoke_packaged.py nanofaas/one-shot-local-jvm/control-plane-one-shot-jvm:local
python3 scripts/one-shot/smoke_packaged.py nanofaas/one-shot-local-native/control-plane-one-shot-native:local
```

The recipe plugin's complete shell/native fixture suite runs on Linux with Git
installed. Local images are built, not registry-published. Assembly reports retain
real source revision and dirty state; a dirty worktree is never relabeled clean.
All executable runtime changes were committed at `cf8bf68f` before the final image
builds; outstanding documentation and the pre-existing `AGENTS.md` difference
account for the recorded dirty state. Final documentation commits do not alter
those binaries.

## Packaged image record

Both distributions were built locally from executable code commit
`cf8bf68f4945096fc1dfc27fada7a3c6f0302b1b` (reported `dirty=true` as explained above).
Image IDs are immutable local content identities, not invented registry digests.

### one-shot-local-jvm

- `nanofaas/one-shot-local-jvm/control-plane-one-shot-jvm:local`: `sha256:e7b5633a3745b0c35936b104e0d4cd52fa701c704e098cc953f2d48ae0787ef4`
- `nanofaas/one-shot-local-jvm/nanofaas-one-shot-workload:local`: `sha256:3a5cb47667dc52f773d1ba4aef35e4373720d5b427d4c38b19ecfbefdecee141`
### one-shot-local-native

- `nanofaas/one-shot-local-native/control-plane-one-shot-native:local`: `sha256:761ee6806ac6e22a987e4303efc73d634f73a372efb41275bac12a1e1e8b332b`
- `nanofaas/one-shot-local-native/nanofaas-one-shot-workload:local`: `sha256:eab5e659d5ca88042b66824add027faae8236e68d9dd00ee4a4a20a8e6bf5b63`

Assembly reports are retained under `build/recipes/<recipe>/distribution.json`.
Packaged JVM/native startup and HTTP 404/400 checks passed without host socket
mounts, root-user overrides or host networking. JVM assembly passed in 9s and
native Linux assembly in 3m10s. Final whole-branch review and its one fix pass are
complete; no Critical/Important findings remain unaddressed and no Minor findings
were deferred. The worktree retains the pre-existing `AGENTS.md` difference.

The inline task ledger, RED/GREEN logs, final review and rulings are retained in
`build/test-diagnostics/one-shot-phase-a/execution-record/`; the per-plan scratch
workspace was removed after archiving. This directory is local diagnostic output,
not a runtime dependency or a NanoLab implementation.


## Review corrections verified with TDD (2026-10-05)

These checks cover the five findings from the subsequent read-only review. Tests
were observed failing before the corresponding corrections. All changes remain
in NanoFaaS; the packaged image identities above belong to the earlier revision
and have not been rebuilt by this correction pass.

| Finding | Observed RED | Correction and GREEN evidence |
| --- | --- | --- |
| Missing tooling dependency | The CI-style isolated environment failed 14 contract tests with `ModuleNotFoundError: jsonschema`. | Declare `jsonschema` in `test-tools`; the complete tooling/experiments/runtime-contract suite passes 258 tests. |
| P2P permit ordering | Eight asynchronous requests with concurrency four failed with `P2P request limit reached` as earlier responses completed. | Eager resource cleanup through `Mono.using`; all 148 P2P tests pass, including cancellation and the eight-peer regression. |
| False handler attribution | The managed proxy attributed a 413 response with matching `RELEASED` evidence; Rust admission failures had no explicit handler-start field. | Rust records `handlerStarted` after typed deserialization, before calling the handler; Java requires boolean `true` for attribution while accepting matching release evidence independently. All 45 proxy tests pass, including three tests against the rebuilt Rust binary. |
| Retained-history overhead | The status lookup regression grew from 625 ns to 7.7 ms with 10000 records, exceeding its relative cost budget. | An ordered terminal index expires/evicts records incrementally. All 103 Rust SDK tests pass; expiry, identity reuse, active retention, errors and timeouts remain covered. |
| Invalid OpenAPI numeric limits | The new profile-schema test failed because `exclusiveMinimum: 0` is invalid for OpenAPI 3.0. | Publish `minimum: 0` with `exclusiveMinimum: true`; reject zero-valued positive quantities. Fragment validation and validation of the schema served by the native executable both pass. |

The full gate also exposed two omissions from the earlier implementation:
`sonar.sh` did not list the new workload Cargo manifest, and untyped Mockito
matchers became ambiguous after the owned-replica overload was introduced. Both
were corrected using the failing existing checks; all 73 autoscaler tests pass.
Offload's 86 tests and forecasting's 16 tests also pass. SpotBugs passes for the
changed Java runtime/P2P modules and offload; Rust formatting and Clippy pass.

A diagnostic HTTP run against the rebuilt Rust workload sent 8100 sequential
malformed typed inputs. The median of the first 100 requests was 0.080834 ms;
after 8000 retained executions it was 0.052438 ms. This is a local regression
check, not a calibration or scientific experiment.

The native recipe compiles with the pinned GraalVM and two build threads. The
initial 4 GiB heap was exhausted; repeating with 8 GiB succeeded. The rebuilt host
executable starts with explicit runtime parameters and returns the expected 404
and 400 API errors. Its published OpenAPI 3.0.3 profile schema validates. This is
host-native verification, not a new Linux packaged-image or NanoLab run.

### Whole-suite limits

`./gradlew test -PcontrolPlaneModules=all --continue` was attempted. It did not
produce an entirely green result on this macOS host. The initial autoscaler
compilation failure is resolved by the successful full module rerun. The
`ExternalDispatcherTimeoutTest.dispatch_slowServer_returnsPoolTimeout` case
returned `EXTERNAL_ERROR` rather than `EXTERNAL_TIMEOUT` in the broad run, then
passed in its isolated three-test class rerun. That intermittent broad-run result
remains recorded rather than being counted as an entirely successful root suite.

The unselected containerd module could not resolve
`io.nanofaas:containerd-java-cni:0.23.0`; its bootstrap dependency was not installed
by this correction pass. The recipe plugin's Linux shell/native fixture suite
also failed on macOS due to the Linux-host requirement and missing `sha256sum`.
The following 19 plugin cases failed in that broad run and were not relabeled
successful:

- `RecipePluginTest > v1RecipeReportsAdditiveFieldsOnly()`
- `RecipePluginTest > imageIdSurvivesPublication()`
- `RecipePluginTest > failedSecondPushKeepsTheFirstSuccessInTheReport()`
- `RecipePluginTest > multiArchPublishOfOnePlatformWithoutProvenanceRecordsItsManifest()`
- `RecipePluginTest > builderFailureFailsAssemblyWithoutReport()`
- `RecipePluginTest > assembleBuildsOnlySelectedArtifactsAndImagesWithoutPushing()`
- `RecipePluginTest > reassemblyAfterPublishForgetsPublication()`
- `RecipePluginTest > multiArchFailedSecondPushKeepsTheFirstSuccess()`
- `RecipePluginTest > multiArchPublishWithAMissingPlatformIsUnverified()`
- `RecipePluginTest > reassemblyDropsRemovedFunctionsAndStaleReport()`
- `RecipePluginTest > hostBuiltNativeImageOnTheHostPlatformPackagesTheStagedExecutable()`
- `RecipePluginTest > multiArchRejectsHostBuiltNativeImagesAndAStrayBuilder()`
- `RecipePluginTest > previewsTwoSdksOfTheSameFunctionWithoutBuilding()`
- `RecipePluginTest > multiArchPublishPushesTheAssembledBuildsAndRecordsIndexAndPlatformDigests()`
- `RecipePluginTest > reportRecordsKindsIdentityNativeOptionsAndImageIds()`
- `RecipePluginTest > servicesAndBashBuildWithTheirOwnContexts()`
- `RecipePluginTest > publishPushesAfterAllBuildsAndRecordsRegistryDigests()`
- `WarmEchoAotTest > recipeModeDecidesServiceAot(String, String, String, boolean) > control plane jvm, warm-echo native, assembleRecipe`
- `WarmEchoAotTest > recipeModeDecidesServiceAot(String, String, String, boolean) > control plane jvm, warm-echo native, publishRecipe`
