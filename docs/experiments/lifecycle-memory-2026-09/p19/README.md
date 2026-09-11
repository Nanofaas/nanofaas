# P19 corrected control — fix round 3

The immutable dossier named in `baseline.json` records the P23 control and its
acceptance verdict. Round 3 fixes the remaining managed pool-registry retention:
Netty disposed the physical pool, but resolved/unresolved address inequality left
its metrics registry entry retained. Registry key normalization repairs that owner;
the original managed profile now drains to zero without a tolerance change.
Native minimal and managed invocation/replay/shutdown and all bounded owner profiles
are repeated on final B. Earlier failed dossiers are preserved unchanged.
The corrected product source is `d93b68cdf1cca6e7af17e1c641c8e236c80d0fca`,
including the preceding two native reflection metadata repairs. P00
(`61d72e73528db62cf8ca465c6a037981d7ec13b0`) remains a
known-defective diagnostic comparator, never a correctness acceptance control.

`protocol.json` freezes the actual common HTTP surface and denominators. The
server runs a real packaged Spring application, HTTP codecs, configured admission,
dispatch, backend transport and completion ownership. The deterministic EXTERNAL
backend and Python generator have separate processes. This is not LOCAL dispatch
and not a peak-throughput benchmark. The P07 configured LOCAL tests remain separate
fresh correctness/profile evidence.

Compile `P19Probe.java` with the recorded JDK into an isolated directory. Launch
the recorded jar through Spring Boot's `PropertiesLauncher` with `loader.main`
set to `P19Probe` and `loader.path` set to that directory. The launcher calls the
same `SpringApplication.run(ControlPlaneApplication.class, args)` as production;
it replaces no beans and transforms no classes. Its loopback-only observation
server is a test fixture, not a production endpoint. Do not deploy it.

The collector uses the JDK-wide cumulative allocation counter, validated against
64 MiB of escaping allocations on both platform and virtual threads after their
exit. Allocation includes the observer's allocations and ordinary natural GC;
it does not include the separate backend/generator or Gradle. No wall-clock
threshold is imposed in CI. `test_*.py` guards unique-success accounting, missing
work, wire-state classification and non-equivalent comparisons.

ASYNC observation needs special care: R1 intentionally declines deep/wide
outcomes because they exceed the conservative weigher's depth/element limits.
Absence of a polled outcome therefore does not imply failure or incomplete
execution. For ASYNC only, the probe installs a bounded terminal listener on the
existing best-effort observer hook, after terminal invariants hold. The listener
retains scalar identity/state/output-id/timestamp, never the record or payload;
the generator consumes each event. The ordinary ASYNC latency includes observer
polling and the initial outcome visibility check. The exact server terminal
observation timestamp and the admission acknowledgement are stored separately.
The probe's event population must be zero at quiescent checkpoints. A separate
check proves a deep outcome is conservatively absent (404), and the same still
protected key returns 410 without another backend attempt. Actual capacity
eviction and its replay race are covered by fresh R2. Exploratory HTTP probes of
previously-readable flat outcomes were nondeterministic and are not acceptance.

Snapshots read public counters and scalar/container-size fields from existing
singletons. They are independent observations, not an atomic snapshot. Repeated
field paths can refer to the same owner: never sum aliases. Counter names ending
in `nextId`, `nextGeneration`, durations or totals are sequences/cumulative work,
not retained populations. Missing reflection mappings are explicitly unavailable.
`observer-dictionary.json` in the dossier maps the semantic inventory to snapshots,
Prometheus metrics, focused tests or explicit exclusions.

Existing store `size()` accessors invoke cache maintenance. GC checkpoints first
run those accessors, then request GC and verify the GC-count increase before
sampling used heap. This is an observer-assisted post-policy checkpoint, not
proof that untouched idle caches automatically sweep at exactly the same instant.
Ordinary windows exclude warm-up, explicit GC, class histograms, registration,
policy waits, removal and shutdown. Histograms request `-all` after verified GC
and can contain small observer-created garbage; they do not assign dominators.
Process RSS/PSS, sockets, buffers and thread counts are separate from Java heap.

The full G1–G18 selection catalog is executed by `verification.py`; the protected
untracked test is excluded from compilation by `verification.init.gradle`.
`extra_gates.py`, `sdk_gates.py`, `package_smoke.py` and `native_smoke.py` cover
the additional selectors, SDKs, packaging and available native startup. Exact
commands, exits, test methods and skips are in the dossier; previous campaign
counts in fix round 1 are explicitly inherited from the preceding dossier, not rerun.
Failed exploratory runs remain in its pilot/aborted
evidence, and cannot satisfy acceptance.

P23 must verify the acceptance verdict, manifest and
manifest and every file hash first, extract the frozen measured
jars and recompile the archived probe. Use `executed-common-harness.tar.gz` for
the exact common SYNC runner; `harness.tar.gz` contains the final ASYNC refinement
and dossier tools. Fix round 1 remeasured all six common runs and three ASYNC runs
with fresh-process validation and owned process-group supervision. Round 2 reran
the same six plus three processes on the new B, without native/profile overlap.
Round 3 repeats them again against final `d93b68cd`; it never relabels older runs.
To run the frozen Python tools, extract `harness.tar.gz` under
`docs/experiments/lifecycle-memory-2026-09/p19` within a repository-layout directory:
the tools resolve the repository root four levels above that directory.
`profiles.init.gradle` compiles measurement-only launchers; `sdk_profiles.py` and
`owner_profiles.py` exercise actual SDKs and the existing provider/proxy seams.
Commands, classpath identities, selected numeric owner fields and unavailable
observations are retained. None of these loopback observation endpoints is for deployment.
Run B and C using the
same protocol and environment, with fresh alternating processes. A harness or
configuration change requires a new protocol identity and remeasurement of B.
Create a new dossier referencing this digest; never overwrite the P19 directory.
Git and content hashes detect changes; they are not a write-once storage service.
Three repeats and a short profile do not prove statistical equivalence or replace
P24 soak. Unexplained repeatable >5% structural regressions remain a P23 gate.
