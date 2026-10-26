# Generated evidence and repository contents

Git stores source code, deterministic test fixtures, experiment configuration,
compact result summaries, manifests and checksums. Execution logs, request-level
traces, profiler recordings, metric snapshots, generated HTML/JUnit reports and
code-graph dumps belong in local or CI artifact storage.

Write new evidence to a directory outside the checkout (for example, pass
`--output /tmp/nanofaas-evidence/<run>` to the logging validation scripts). Older
experiment tools may still create ignored files beside their sources. Do not
force-add those files. Before keeping a result, reduce it to the measurements,
source revision, tool/image identities, configuration and hashes needed to explain
and reproduce it. A small synthetic input required by a test is a fixture, not a
historical execution dump; keep such inputs with the tests or shared contracts.

For evidence that must survive the local machine, use the experiment's artifact
store or CI artifacts and record the immutable artifact identifier and checksum in
its report. Set an explicit retention period: routine diagnostic output can be
short-lived; evidence supporting published measurements needs retention matching
that publication. No new external archive is provisioned by this cleanup.

## Historical evidence

The 26 October 2026 cleanup removed raw output from the current tree. Existing
experiment reports, inputs, summaries and manifests remain; their raw-file paths
and checksum lists describe the original evidence bundle, which is no longer
complete in a fresh checkout. Replaying analyses that consume those files requires
restoring the evidence first. This also applies to the P19 content-addressed
dossiers and their integrity verifier.

The complete pre-cleanup tree is available at Git revision
`550d8f7a` (the reviewed `main` revision). The removal manifest
[generated-artifacts-2026-10-26.sha256](generated-artifacts-2026-10-26.sha256)
records every removed raw file and its SHA-256 digest. Recover a single file
outside the checkout with:

```sh
git show '550d8f7a:docs/testing/edge-logging/recovery/recovery-query.json' \
  > /tmp/recovery-query.json
```

Or recover an experiment's original directory into an external workspace:

```sh
mkdir -p /tmp/nanofaas-historical-evidence
git archive 550d8f7a docs/experiments/lifecycle-memory-2026-09/p19 \
  | tar -x -C /tmp/nanofaas-historical-evidence
```

Checksums should be verified from that restored repository-relative directory
layout. Historical links to removed files now point to the pinned revision.
Deleting current files does **not** shrink existing Git history or remove them
from previous commits; history rewriting is a separate repository operation.
