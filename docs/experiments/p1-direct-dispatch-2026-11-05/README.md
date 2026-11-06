# P1 direct managed dispatch — implementation validation

Base: `401391a0d39f84364570a17044dc4d8b0db23596`. Implementation, functional validation and reduced Docker performance campaign completed; original experiment logs retained in the implementation worktree.

## Implementation

Local Docker and containerd providers expose reservations backed by a shared replica pool. `ExternalDispatcher` sends directly through its existing WebClient. Unsupported providers retain their external/Service route; supported providers without a target return unavailable. Managed endpoint URLs are null. Generation and deployment-instance fences reject stale targets. Physical one-shot lease release and execution attribution require correlated SDK evidence, never an external response header. The intermediate HTTP proxy and its configuration were removed.

Independent review identified quiesce readiness pollution, ordinary failed-drain recovery, and transient physical-probe quarantine/publication issues. All were reproduced with failing tests and corrected. The reviewer found no remaining issues after inspecting the final fixes. No deferred minor findings.

## Validation

Full command outputs are compressed under `evidence/`.

| Check | Result |
| --- | --- |
| Baseline `./gradlew :container-deployment-runtime:test :control-plane:test` | Passed |
| Final runtime and control-plane suite | Passed |
| Docker provider, offload, runtime with real Rust one-shot executable | Passed |
| Containerd provider composition | Passed |
| Full default `./gradlew test` | Two WarmEchoAotTest failures; both reproduce identically on untouched base on macOS because native container recipes require a Linux host builder |
| GraalVM recipe with Linux container builder | Passed, including rebuild after review fixes |
| Native real HTTP smoke | 48 correct responses, 2 functions, 1 and 2 replicas, concurrency 4, unique execution IDs, null managed endpoint; resources removed |
| NanoLab container lifecycle | Passed, cleanup completed |
| NanoLab Kubernetes lifecycle | Passed, VM destroyed |
| NanoLab legacy edge/cloud offload scenario | Failed before invocation: obsolete `java-word-stats:e2e` image tag was not built; all VMs destroyed |
| GitNexus detect_changes, scope all | Complete response without partial/truncated markers: 225 changed symbols, 702 affected flows, critical broad-change risk |
| Performance comparison | Completed reduced paired Docker campaign: JVM/native × 1/2 replicas × before/after, one repetition |

Native image: `sha256:694fd8304dd62ab4bc68496bdbf8f457da7a1848cf9cce7e979f4f4bb67da01e`. Function smoke image: `sha256:dd10dc27202fdd7fdb20c554ace7e3a837aaeb4a0705a921382292a190207897`. The native metadata reports ARM64, Linux `7.0.14-linuxkit`, GraalVM Java 25.0.4, Substrate VM. `native-smoke.py` records the exact smoke procedure and image tags; it is a functional check, not a benchmark.

## Decisions and limits

- Provider SPI takes function name and deployment metadata rather than RegisteredFunction: ArchUnit exposed a deployment/registry dependency cycle. The coordinator owns generation validation.
- The existing dispatch ownership lease is propagated onto InvocationTask; the real managed offload regression exposed its previous absence. No new ownership system was added.
- Native images are built with `build.builder: container` because a macOS host binary cannot form a Linux image.
- NanoLab source snapshots exclude untracked files; new implementation files were staged before successful scenario execution.
- Plan task briefs were extracted manually because the skill parser did not accept the Italian numbered headings.
- NanoLab `git pull --ff-only` returned Already up to date at `9305fad27315befd3e0d759aa5de4e6c65ae37c5`. Its original checkout remains clean. An isolated worktree exists at `/private/tmp/nanolab-p1-direct-dispatch`; no runner changes were made before the user declined the lengthy campaign. Its targeted baseline had 199 passing tests and one existing faasd live-PID cleanup failure.
- The reduced campaign reuses experiment-02 with an isolated runner fixing concurrency to 4 globally, queue 200 and retry 3. Containerd performance was not measured; its provider tests passed.

The original 96-cell matrix was replaced by the user-approved reduced campaign. The legacy NanoLab offload image-tag issue remains outside this implementation; direct managed offload regression tests passed.

## Paired Spot comparison — completed 2026-11-06

Four independent stack/load-generator pairs on Azure Spot ran before and after on the same machines, alternating order across pairs. The aggregate measured deployment budget was 4 CPUs / 4096 MiB; global concurrency 4, 1 or 2 fixed replicas, Serial GC and control-plane heap 64 MiB. Offered rates were 25/50/100/300 r/s for 120 seconds each, after idle/warmup. The same frozen Rust QR image and payload were used throughout. All pairs completed and Azure inventory confirmed no remaining campaign resources.

Native before retains `TCP_NODELAY=true` for the old proxy; native after has no override because that proxy was removed. JVM retained its original flag in both recipes. Build failures and one Azure cleanup NotFound were recovered without discarding successful measurements; the failed native attempts produced no measurements.

At 300 offered requests/s:

| Configuration | Correct responses/s, before → after | p95 ms, before → after | Rejected, before → after |
| --- | ---: | ---: | ---: |
| jvm-r1 | 250.34 → 277.26 | 1634.8 → 454.2 | 5830 → 2720 |
| jvm-r2 | 248.64 → 265.59 | 1646.7 → 1611.6 | 6034 → 4001 |
| native-r1 | 261.25 → 278.75 | 1613.3 → 448.5 | 4530 → 2550 |
| native-r2 | 251.41 → 269.10 | 1648.7 → 1592.9 | 5709 → 3584 |

These are single repetitions, not estimates of run-to-run variability. All responses checked for content were correct; throughput excludes rejected requests. Two replicas share the same total resource and concurrency budget. Per-handler metrics for two replicas are omitted because the legacy sampler observes only the first replica. Full phase summaries, protocol and source/input hashes are in `evidence/spot-comparison/`; raw ledgers remain in `build/p1-spot8-rate300` in the retained worktree.

## Integration verification — 2026-11-06

`./gradlew test --continue --console=plain` completed in 3m38s. Recorded XML reports contain 2745 tests, 2 failures, 0 errors and 6 skipped; the only failing suite is WarmEchoAotTest with the two previously baseline-reproduced macOS/Linux host-builder cases. All implementation suites passed. The complete pre-commit GitNexus report covers 225 changed symbols and 696 affected flows with critical broad-change risk, without partial/truncated markers.
