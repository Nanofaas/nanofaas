# Campaign: measurement-driven tuning (plan §6, activities T1–T4)

Phase-4 campaign of the plan
`docs/plans/2026-09-05-control-plane-correctness-and-performance.md`.
It does not overwrite `overload-path-2026-09`, which remains the earlier campaign.

## What it measures, and what it does NOT

Plan §6 asks each T activity for **a profile and an isolated comparison first**;
§8 additionally asks for an end-to-end campaign on shared infrastructure, with
Prometheus collection and NanoLab's *compare* workflow.

This campaign covers **both**, in that order — but the isolated comparisons are
in-JVM on the development machine, and that is deliberate and must be stated when
reading the numbers.

| | Covered here | Where it belongs |
|---|---|---|
| Profile and isolated comparison of an intervention | yes | here |
| End-to-end matrix, 3 repetitions/arm, alternated order | yes, one scenario | NanoLab (§8) |
| Prometheus collection, client-side p99, CPU/allocations per success | partial | NanoLab (§8) |
| Soak crossing the retention windows | no | NanoLab (§8), see issue #207 |

**Correction (2026-09-07).** The first draft of this README said NanoLab was not
available, and concluded that §8 and §7's Kubernetes row were not executable
here. Both were wrong. The check was `ls ../nanolab` run with the working
directory inside the *worktree*, where it resolves to `.claude/worktrees/nanolab`;
CLAUDE.md's `../nanolab` assumes the repo root. It lives at
`/home/michele/Documenti/nanolab`, works, and exposes `compare` with
`--repetitions 3` by default — exactly what §8 prescribes. Multipass is installed
too. The error is kept here rather than quietly fixed.

A procedural note for whoever continues: `compare` varies *build flavours* of one
checkout. The baseline-versus-candidate comparison §8 asks for — two code
revisions — needs two `run` invocations instead, which is what
`compare-baseline-candidate.sh` does.

## The machine

Every measurement in this campaign comes from **one machine**, an NVIDIA DGX Spark:

| | |
|---|---|
| architecture | aarch64 (ARM) |
| CPU | 20 cores, Cortex-X925 + Cortex-A725 |
| memory | 121 GB |
| OS / kernel | Ubuntu 24.04.4 LTS, Linux 6.17.0-nvidia |
| Docker | 29.2.1 (arm64) |

Worth stating for two reasons, neither of them ceremonial.

**It is not x86.** None of these numbers transfers directly to a CI runner or an
x86 host: absolute timings change, and so does the relative cost of JIT,
allocation and syscalls.

**It is fast.** The X925 cores are high-end, and that has already changed an
outcome: `concurrency-cycle-container` could not degrade the function because the
offered work was too small for these cores (see the E2E section in RESULTS.md).
On this machine, "the platform does not degrade under load" can mean the load was
light, not that the platform is robust.

## How to read the numbers

Each arm runs `REPS` **alternated** repetitions (A,B,A,B,…) in the same process,
after warm-up, and reports the median and spread — not the mean, which a single
GC pause moves. An intervention is adopted only when the improvement exceeds the
measured dispersion of the baseline; otherwise the negative result is documented
and the previous implementation kept (§6).

§8's thresholds (10% on the target metric, no worsening beyond 5%) are
experimental criteria, not CI gates: **no measurement in this campaign becomes a
wall-clock test**. Structural guarantees stay in the test suite.

## Layout

- `bench/` — benchmark sources, one per activity
- `raw/` — raw output per run, as JSON
- `run.sh` — runs a benchmark against the control-plane classpath
- `compare-baseline-candidate.sh` — the §8 driver: two revisions, alternated
- `RESULTS.md` — outcome and decision for each activity
