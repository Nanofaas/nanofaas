# Issue #237 verification

Verified on 2026-10-03 against implementation commit `b659ae15d99866c9ba53beca447afda8e63a2159`, based on `ef856960e99a6c56b93be6a978965535f7a3eba7`. Subsequent changes to this report and the completed plan are documentation only.

All six findings in [issue #237](https://github.com/miciav/nanofaas/issues/237) have regression coverage and passing acceptance checks. The original checkout and its existing local changes were preserved; implementation is on `codex/issue-237` in a managed worktree.

## Automated checks

| Check | Result |
| --- | --- |
| `./gradlew :control-plane:test :execution-runtime:test :control-plane-modules:autoscaler:test :control-plane-modules:concurrency-control:test --offline --console=plain` | 1,282 passed, 6 skipped, no failures/errors |
| `cargo test --manifest-path runtimes/watchdog/Cargo.toml --offline --locked` | 25 passed |
| Watchdog `tests/integration/run_all.sh` in an isolated Linux Docker container | 82 passed: HTTP 17, STDIO 23, FILE 17, callback 17, warm 7, metrics 1 |
| `go test ./... -count=1 -timeout=90s` from `sdks/go` | Passed, no exclusions |
| `go test -race ./... -count=1 -timeout=90s` from `sdks/go` | Passed, no exclusions |
| `uv run --project tools/fn-init --group dev python -m pytest scripts/tests tools/fn-init/tests -q` | 163 passed |
| `helm lint deploy/helm/nanofaas` | Passed; only optional icon recommendation |
| Actual Spring bootJar with env extracted from rendered custom-port chart | API 200 on 18080, readiness/liveness UP on 18081 |
| Git diff whitespace check | Passed |

Java totals are control plane 793 passed/3 skipped, execution runtime 351 passed, autoscaler 73 passed, concurrency control 65 passed/3 skipped. The execution-runtime task was up to date in the final invocation; its unchanged sources had already been tested in the baseline run. Three control-plane tests require the core-only module composition (`P07ConfiguredHttpCalibrationTest` and two `CoreOnlyApiTest` cases). Three `ConcurrencyGovernorE2eTest` cases require the async-only queue composition and are disabled in the default composition selecting sync-queue. These composition-specific tests were not run separately.

The new B1, B2, B3, B4, B5 and B6 regressions were observed failing against the original behavior before their fixes. The watchdog descendant-cleanup regression was also observed failing with process-group termination deliberately removed and passing when restored.

## Kubernetes acceptance

NanoLab checkout: `/Users/micheleciavotta/Downloads/nanolab`. Scenario: `packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml`, selecting `word-stats-java`. Environment: Multipass VM `nanofaas-issue237`, 4 CPUs, 6 GiB RAM, 30 GiB disk, Ubuntu 26.04, k3s, Helm `v3.16.4`. NanoLab's 20 workflow tasks all passed, including builds, installation, managed-function resources/invocation and the synchronous queue burst.

The installed NanoLab catalog rejected the newer, unselected `functions/rust` directory. A temporary Python entrypoint added only `rust` to its ignored discovery directories before invoking the original CLI. No NanoLab source files or tested NanoFaaS sources were changed by this adapter. The selected Java scenario ran in full. This does not qualify a Rust-function Kubernetes scenario.

Additional acceptance ran in namespace `nanofaas-issue237-upgrade`, Helm release `issue237-upgrade`, using the same temporary VM and images built from the implementation:

1. Installed the chart extracted from baseline `ef856960` with demos/Prometheus disabled and persistence enabled. The live Deployment had the default `RollingUpdate` strategy. Upgraded to the fixed chart: the live strategy was exactly `{"type":"Recreate"}`, with no leftover `rollingUpdate` field. Modern Helm handled this migration without an explicit null field.
2. Enabled two managed warm-echo demos, `issue237-a` and `issue237-b`, with fixed one-pod scaling. Set B's concurrency to 0 and hook backoffLimit to 0. The upgrade failed as expected on B's HTTP 400 after A had registered; API reads confirmed A existed and B did not.
3. Corrected B's concurrency to 2 and repeated the upgrade. The hook preserved A and registered B. Patched A's timeout to 12345 through the API, then repeated the same upgrade successfully: A's timeout remained 12345.
4. Upgraded to a different control-plane image tag while issuing invocation requests continuously and sampling control-plane pod container states/readiness. The original NanoLab image contains `k8s-deployment-provider,sync-queue`; the new image adds `async-queue` so actual asynchronous callbacks can be exercised. It was built from the same source with `:control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider,sync-queue,async-queue --offline`, replacing the application jar over the existing JVM image.
5. Verified a managed asynchronous execution completed with status `success` and the expected echo output after the image upgrade.
6. Upgraded again with `controlPlane.service.ports.http=18080` and `.actuator=18081`. The hook succeeded, API reads returned 200, readiness and liveness returned UP, and another managed asynchronous execution completed with the expected output through a real callback.

The PVC UID remained `b7c16d6c-2d95-4ae2-becb-3516a6a627d8` through all these upgrades; the persisted user edit also survived the image and port changes.

During the image upgrade, 139 pod samples over 23.83 seconds observed at most one running container and one Ready control-plane pod. Both old and new pod UIDs were observed (`917e490e-fc15-4a7b-a8b7-10245e7a7fc8`, `4f797b9e-b965-489a-a7fe-d5e65e70a9e9`). Load observed 38 successful synchronous requests, 113 accepted asynchronous requests with successful execution-status reads, and 281 unavailable attempts during replacement. Execution-status reads alone are not completion proof; the separate output assertions in steps 5–6 establish completed callbacks. Sampling provides acceptance evidence for this upgrade, not proof of general fencing. Downtime and loss of in-memory execution state remain documented limitations of Recreate.

The VM and all its cluster/image resources were removed after verification. NanoLab `--teardown` rejected the validate scenario because it supports release scenarios only; targeted `multipass delete --purge nanofaas-issue237` completed cleanup. A subsequent `multipass list --format json` was empty.

## Implementation decisions and review

- Reused existing synchronized `removeRegistered(name)` for the named durable delete instead of adding an equivalent `commitRemoval` API. Save-before-publication plus the new recovery/interleaving tests establish the boundary.
- Watchdog regressions exercise both entrypoints directly, supplemented by the full existing shell integration suite. The existing warm timeout contract remains HTTP 500 with `Process timed out`.
- Go envelope preparation publishes response metadata only after encoding and callback acceptance; valid marked non-2xx function results retain their existing contract.
- A quoted heredoc preserves literal demo JSON, including apostrophes and shell syntax. A duplicate is accepted only after a successful GET; other HTTP and transport failures fail the hook.
- Any `SERVER_PORT` or `MANAGEMENT_SERVER_PORT` entry in extraEnv is rejected, including a redundant identical value. Chart service ports are the single supported source.
- Repaired the pre-existing Go bind-failure test's macOS portability: occupy the same wildcard address used by the runtime. Its IPv4-only listener left an IPv6 bind available and caused two baseline timeouts. This is a test-only change; the final full Go suite has no exclusion.
- Corrected test-harness expectations for warm HTTP 500, public lowercase execution status and env keys containing digits. These corrections do not change product behavior.
- No OpenAPI schema changes are needed: these fixes restore existing response/lifecycle contracts. Operational documentation and deploy configuration were updated.
- NanoLab's process-only Rust discovery adapter and the sync-to-async test image composition are verification accommodations described above; neither changes the repository's production behavior.

GitNexus upstream impact was checked before symbol edits. FunctionRegistry had CRITICAL shared impact, and run_stdio_warm had HIGH impact; both were reported before editing and their dependent paths were tested. Duplicate `applyEnvelope` symbols and chart filenames required file-specific context plus source inspection because name-only impact resolution was ambiguous. Pre-commit change detection covered the expected 19 files and reported medium overall risk, with no partial/truncated result. The index was refreshed after the implementation commit without changing generated project instructions.

A fresh-context independent reviewer inspected the complete branch diff, plan, tests and concurrency/failure boundaries. Verdict: approved, with no actionable Critical, Important or Minor findings. Its outstanding request for live B4/B5 acceptance evidence is fulfilled above. No review findings are deferred.
