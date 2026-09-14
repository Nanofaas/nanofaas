# P24 - legacy evidence and profile-aware requalification

P24 now has two clearly different bodies of work: a completed legacy loadtest
campaign and a future profile-aware requalification workflow. The legacy runs
remain useful evidence, but they did not execute the new qualification contract.

## Completed legacy loadtest evidence

The completed historical campaign used NanoLab's older `loadtest` workflow. Its
two arms selected prebuilt control-plane images from `e35405ee` and the candidate,
while both arms used Java and JavaScript function images built from the
**candidate** checkout. That controlled the SDK workload across control-plane
revisions, but it was not a single-version NanoFaaS run in the sense of the new
reusable workflow.

The baseline arm ran 90 minutes of constant unkeyed SYNC traffic, served 881,849
requests, and observed a 35-minute drain. The control plane grew from about
508 MB to 739 MB early and then remained around 739-750 MB; the Java function
grew from about 334 MB to 980 MB and then flattened. The JavaScript function
grew from about 29 MB to 1,194 MB without a plateau, exposing a separate SDK
retention defect. These measurements and the completed candidate arm remain
legacy comparison evidence.

The old artifacts did **not** execute the criteria-only policy now checked into
NanoLab. In particular, they did not enforce post-drain RSS returning to the
same run's baseline with zero tolerance, did not select `advanced`/`soak`
profiles through the new workflow, and did not evaluate the new owner-settlement
matrix. They must not be cited as proof that the profile-aware P24 contract has
passed.

The legacy baseline also ran while four unrelated Multipass VMs held about
814 MiB; they were removed before the candidate arm. The host has 121 GiB, but
the historical comparison must retain this environment caveat.

## Future single-version requalification workflow

Each reusable NanoLab soak invocation measures exactly one NanoFaaS revision or
image set. Comparison happens only after independent run artifacts exist.

| Purpose | NanoFaaS revision | NanoLab preset | Metrics profile |
|---|---|---|---|
| Historical requalification baseline | `e35405ee` | `memory-soak-sync-container.yaml` | `advanced` |
| Historical requalification candidate | candidate revision | `memory-soak-sync-container.yaml` | `advanced` |
| Ownership diagnosis | candidate revision only | `memory-soak-sync-candidate-diagnostic-container.yaml` | `soak` |

Both presets resolve the checked-in criteria-only
`memory-soak-policy.yaml`. For every process role it freezes a steady-state
cgroup ceiling and this drain requirement:

```text
post-drain RSS <= baseline RSS
```

Both absolute and relative positive tolerance are zero. The historical image is
not modified or backported, so the `advanced` runs require no candidate-only
owner gauges. The candidate `soak` run additionally activates only the
role- and coverage-applicable owner-settlement gates. `metric_series` is
optional diagnostic information and never a qualification criterion. The
JavaScript SDK adds no new SOAK metric.

The exact profile hierarchy and all nine owner-gauge semantics are documented
in [`docs/observability.md`](../../../observability.md).

The native scenario has been removed and remains out of scope.

## Current status

The profile hierarchy, owner gauges, prerequisite normalization, checked-in P24
policy, and shipped preset resolution have focused implementation/test evidence.
This is not final integration validation.

The NanoFaaS module gate remains inconclusive after one SDK shutdown scenario
failed in the combined run but passed in isolation. The complete NanoLab soak
test directory remains inconclusive because its run was terminated after
hanging, and the GitNexus change-risk gate remains `UNKNOWN` after two bounded
timeouts. None of those gates was rerun for this review fix.

No long profile-aware soak has been run or passed as part of this implementation.
The next operational step is to build the selected revision's images and execute
the appropriate single-version preset, starting with the candidate-only
`soak` diagnostic if owner attribution is required.
