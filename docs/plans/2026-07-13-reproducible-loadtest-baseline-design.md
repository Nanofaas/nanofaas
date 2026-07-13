# Reproducible Load-Test Baseline Design

## Goal

Make each load-test run self-describing and directly comparable while keeping the workflow provider-neutral and dependency-free.

## Design

The existing k6 summary and Prometheus snapshot remain the source data. A final workflow task writes a compact `summary.json` containing selected k6 statistics, generic statistics for every Prometheus query, and the verified autoscaling result. Generic Prometheus statistics preserve the query name and expose point count, first, last, delta, minimum, and maximum; this avoids a second metric-specific data model while making counters and resource peaks immediately comparable.

The default Prometheus query set will use the metric names actually exported by Micrometer. It will add retries, timeouts, queue rejections, cold/warm starts, queue wait, end-to-end latency, control-plane CPU, and JVM heap memory. Function metrics are filtered by function label and process metrics by the control-plane application label.

The Helm chart currently annotates both the control-plane Service and Pod for scraping, while Prometheus discovers both endpoints and pods. That duplicates every control-plane series. The Pod annotations will be removed from the control-plane Deployment; endpoint discovery remains active, while dynamically created function pods continue to use pod discovery.

The CLI progress sink already measures every task, including provisioning and cleanup. It will retain those measurements in memory. For load-test runs, the CLI writes `run-metadata.json` in a `finally` block with schema version, status, timestamps, Git commit, scenario/environment configuration, and task results. Failed runs therefore remain diagnosable. No provider-specific logic is added: Multipass, Azure, Proxmox, external SSH, and local runs share the same artifact contract.

## Error Handling

Raw load-test failures keep their existing behavior. Metadata writing must not mask the original workflow exception. Optional Prometheus queries may remain empty, and the summary records zero points rather than inventing a value. Required queries retain the existing fail-fast semantics.

## Verification

Changes are developed test-first. Unit tests cover query construction, summary statistics, autoscaling persistence, metadata recording, and failure metadata. Helm rendering verifies that only the Service is annotated for control-plane scraping. The complete `workflow-tasks` and control-plane tool suites must remain green, followed by a dry-run plan for Multipass, Azure, and Proxmox. A real Multipass run validates the complete artifact set; Azure and Proxmox live runs remain deferred until those environments are available.
