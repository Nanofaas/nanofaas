# Edge logging qualification, 2026-10-10

> Raw logs, queries and metric snapshots were removed from the current tree.
> Receipts, summaries and hashes remain; see [artifact recovery](../../generated-artifacts.md#historical-evidence)
> to restore a complete evidence bundle from revision `550d8f7a`.

This is a technology qualification, **not the heterogeneous one-shot experiment**.
The statistical campaign still requires its frozen topology/load/calibration protocol.

## Executed checks

- Shared schema: 46 tests + 11 subtests; all six SDK formatters covered, including
  the shared Java formatter's corpus. JSON escaping, optional absent IDs, reserved
  application attributes, worker reuse and context restoration are checked.
- Platform modules and execution-runtime regressions pass. Java SDK suites pass;
  Python 173 tests, Go race suite, JavaScript Node24 96 tests, Rust 99 unit + 11
  integration tests pass. Callback trace preservation and host Logback precedence
  have regression tests observed failing before their fixes.
- Helm lint and 25 enabled/disabled chart tests pass. CRI rendering is checked;
  this session does **not** claim a Kubernetes cluster deployment qualification.
- GraalVM CE 25.0.4: control-plane, Java Spring warm-echo and Java lite word-stats
  binaries build and run in both text and JSON. HTTP function invocation succeeds.
  A real gateway registration/invocation produces the same execution/trace IDs in
  control-plane and Java lite logs. See [native receipt](native/receipt.json).
- Real Fluent Bit 5.1.3 → Loki 3.7.8, Grafana 13.2.3: Unicode/newline JSON and
  text fallback delivered. Forty events emitted while Loki was stopped survived
  collector restart on its persistent volume and were recovered; no duplicates
  observed in the initial recovery checks; the final all-runtime check observed 10
  duplicate deliveries (exactly-once is not promised). File rotation passed.
- Separate **3 MiB** output quota test: 821 records explicitly dropped; delivery
  resumed after restoring Loki. Snapshot RSS 13.98 MiB / 64 MiB. These values
  describe the test workload, not a maximum memory guarantee under arbitrary load.
  [Receipt](recovery/receipt.json), [metrics](https://github.com/Nanofaas/nanofaas/blob/550d8f7a/docs/testing/edge-logging/recovery/overflow-metrics.txt),
  [query](https://github.com/Nanofaas/nanofaas/blob/550d8f7a/docs/testing/edge-logging/recovery/recovery-query.json), [hashes](recovery/sha256.json).
- All four additional SDKs pass real HTTP handler smoke (two distinct identities each).
  Their emitted logs and both Java native runtime logs are ingested and queried
  through the real collector, preserving identity/service triples and labels without
  request IDs: [SDK receipt](sdks/receipt.json), [runtime query](https://github.com/Nanofaas/nanofaas/blob/550d8f7a/docs/testing/edge-logging/recovery/runtime-query.json).
- Cleanup is checked against actual Docker project container and volume inventories,
  not just a zero exit code. Profiles must be included even on `logs` and `down`.

## Reproduction

Requires Docker Compose, Java 25/GraalVM binaries, Node >=24, Go >=1.24, Rust,
Python >=3.12 with SDK test/runtime dependencies, and Helm. Build Java binaries
with the repository's configured GraalVM, then:

```sh
python3 scripts/logging/validate-native.py --output /tmp/nf-native-evidence
python3 scripts/logging/validate-sdks.py --output /tmp/nf-sdk-evidence \
  --python /path/to/sdk-venv/bin/python --node /path/to/node24 --cargo /path/to/cargo
python3 scripts/logging/validate-collection.py --output /tmp/nf-collector-evidence
```

Collector validation uses unique Compose projects and synthetic Docker-envelope
files. It does not mount the host's production logs. Source/readiness failure is
reported, backend outage is intentional, and cleanup exports evidence before
removing only that test's resources. Image digests and real collector metrics are
included in the receipt. `validate-native.py` needs port 8080 free for the lite
example (its public builder owns the port); it never stops an unrelated service.

Scientific accounting and the one-shot sample stdout protocol are unchanged.
Before calibration, NanoLab must include logging configuration/budgets in its
fingerprint and pass the paired overhead gate described in the implementation plan.

NanoLab integration adds opt-in collectors, ownership-safe teardown, bounded
exports and measured logging identity in profile fingerprints. Three paired
windows on six heterogeneous Multipass VMs qualify the technology pilot;
[all series, including the failed gate](overhead/README.md) are retained.
The full scientific campaign must repeat its own gate before calibration.

The [final independent review and fix record](final-review.md) includes the
complete native decision ledger, RED/GREEN regressions and final checks in both
repositories. Collector finalization now preserves a verified buffer archive
before removing owned state and distinguishes query success from delivery.
