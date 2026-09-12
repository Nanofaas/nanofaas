# P24 — controlled soak: capability built and validated, measurement not yet run

P24 asks for roughly ninety minutes of steady load per revision, comparing the
historical baseline `e35405ee` with the final candidate, and then observing the drain
past the retention windows. NanoLab could not express that experiment, so this task
built the capability first. **The two measured arms have not been run.** What is
recorded here is the capability, its validation on a real packaged run, and the
decisions that shape the measurement.

## Why a new capability was needed

The soak crossing the retention windows was deferred to NanoLab and issue #207 and
never executed, so there was nothing to reuse. Every existing load scenario ramps —
they ask what the tail does as demand moves — and a retention question needs the
opposite, demand that never changes, so that a population which grows can only be the
system and not the load. Nothing collected what the control plane still held after the
traffic stopped, which is the only condition under which retention is visible at all:
under load every population is legitimately non-empty and a leak looks like a busy
system.

Added in nanolab (`4f77779`, `3f1e7c3`): `soakMinutes` holds the mixed generator flat,
`drainMinutes` keeps observing afterwards, `ObserveDrainTask` samples the control
plane's own metrics at 30 seconds, 5 minutes and 30 minutes after the stop, and
`controlPlaneImage` names a build a variant key cannot describe — one of a *different*
revision.

## The design constraint that shaped the experiment

The function SDKs changed substantially between `e35405ee` and the candidate: the Java
SDK by +663/−63 production lines, the JavaScript SDK by +951/−326. Those SDKs are
compiled into the function images. An arm that built its own functions would therefore
vary the function runtime as well as the control plane, and the plan is explicit that a
difference in useful work makes a memory comparison non-equivalent.

Both arms therefore build their functions from the **candidate** checkout and differ
only in the control-plane image. The two images are already built and their identity is
frozen in `image-identity.txt`.

## What the validation run proves, and what it exposed

A two-minute soak with a two-minute drain, on the container backend, ran the full plan:
registry, compose stack, function build and push, registration, k6, snapshot, report.
`validation-drain-populations.json` is its drain observation — samples at 0, 30 and 120
seconds with live threads falling 81 → 82 → 66 as the system drained.

It also exposed three defects in what had just been written, each fixed in `3f1e7c3`:
the population series were guessed and matched nothing the control plane exports; the
prebuilt control-plane flag was coupled to prebuilt functions, which on this backend
left the run's registry empty and failed every registration with a 503; and a named JVM
control-plane image was ignored by compose, which rebuilt from a jar that prebuilt mode
had deliberately not built.

## What remains before the arms can run

The run still fails at threshold evaluation: 73 required Prometheus queries return no
data. They are queue and scheduler series, which need a queue module this scenario does
not select, and cAdvisor container series, which the compose stack does not run. The
comparison profile's required-query set was written for the Kubernetes matrix, where
both exist.

That is a decision, not a bug to paper over. Either the soak selects a queue module and
a cAdvisor sidecar so the existing gate applies unchanged, or the soak declares its own
required set — which is honest only if it states what it no longer checks. Whichever is
chosen, it must be the same for both arms.

After that: roughly four and a half hours of machine time for two arms of ninety minutes
plus thirty-five minutes of drain each, then the retainer analysis the plan asks for if
any population fails to fall.

## Status

**Incomplete.** The capability is built, tested and validated; the measurement that
answers the historical RAM question has not been made, and no attribution is claimed.
