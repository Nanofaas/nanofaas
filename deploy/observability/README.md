# Optional edge log collection

Each process emits stdout/stderr. `NANOFAAS_LOG_FORMAT=json` enables the shared
[SDK contract](../../sdks/runtime-contract/README.md); text remains the default.
Every in-process module inherits the process backend. Separately deployed services
and function containers must set the variable themselves; for managed functions
put it in the function specification's `env`. Rust applications explicitly call
`nanofaas::logging::init_logging()` or install `LogFormatter` in their subscriber.
Application-owned logger configuration takes precedence; the collector has no SDK dependency.

## Docker

From this directory, run the backend **on a separate host from the compute VMs**:

```sh
docker compose --profile backend up -d
```

Loki and Grafana bind localhost by default (3100 and 3000). To receive edge VM
traffic, set `LOKI_BIND` to the host's private interface and configure that reachable
address on each VM. Grafana's provisioned datasource uses the private Compose network.
For an existing backend, run only the agent profile:

```sh
NANOFAAS_NODE_ID=edge-1 LOKI_HOST=192.168.64.1 LOKI_PORT=3100 \
  docker compose --profile agent up -d
```

`LOKI_HOST`, `LOKI_PORT`, `LOKI_URI`, `LOKI_TLS` describe the **push** endpoint
(default `/loki/api/v1/push`). TLS verification stays enabled. Use separate Compose
project names if multiple instances share a host. `DOCKER_LOG_ROOT` defaults to
`/var/lib/docker/containers`; query `docker info --format '{{.DockerRootDir}}'` if
Docker uses a different root. Applications must use the `json-file` logging driver
with a finite `max-size`/`max-file` rotation budget. The collector/backend use `local`
to avoid collecting their own logs. No Docker socket is mounted.

The JSON envelope is unwrapped and application JSON merged; non-JSON text stays
in `log`. Node/campaign/run/mode/role metadata is added by the collector from
`NANOFAAS_NODE_ID`, `NANOFAAS_ENVIRONMENT`, `NANOFAAS_CAMPAIGN_ID`, `NANOFAAS_RUN_ID`,
`NANOFAAS_MODE`, `NANOFAAS_NODE_ROLE`. Unknown campaign/run metadata uses `none`,
never a fabricated request or epoch ID. Restrict service names to a stable finite set.

## Kubernetes

```sh
helm upgrade --install nanofaas ../helm/nanofaas \
  --set observability.logs.enabled=true \
  --set observability.logs.loki.url=http://logs.internal:3100/loki/api/v1/push
```

One Fluent Bit DaemonSet per release reads CRI logs in the release namespace.
Set `observability.logs.namespaces` to include separately deployed function
namespaces. Use **one release's collector per node**, avoiding duplicate collection
if multiple NanoFaaS releases share nodes. The control plane selects JSON when
collection is enabled; each function specification still selects its own log format.
The agent excludes its own logs, reads `/var/log` read-only, and uses node-local
`/var/lib/nanofaas/fluent-bit` for state. It does not use the Kubernetes API or an
API token and needs no collector RBAC. Node identity comes from the downward API.
Pod annotations expose Prometheus metrics to the chart's existing pod scraper.

## Budgets, persistence and failure semantics

Pinned images: Fluent Bit **5.1.3**, Loki **3.7.8**, Grafana **13.2.3**.
Per collector: **0.10 CPU**, **64 MiB RAM**, **256 MiB output queue**; persistent
SQLite tail checkpoints and filesystem chunks. A node's disk reserve must also
cover the checkpoint DB and rotated application logs; 256 MiB is the output queue
limit, not a whole-volume quota. Backend: Loki 1 CPU/512 MiB and Grafana
0.5 CPU/256 MiB, persistent named volumes. Backend disk usage needs monitoring:
retention is **72 hours**, compacted asynchronously, not a hard disk-size cap.

Retries are unlimited while retained chunks fit the output quota. Once full,
Fluent Bit discards its **oldest output chunks** to make room. This is best-effort
collection: duplicates can occur after restart, rotation can delete unread source
logs, and physical disk exhaustion/agent OOM can cause loss. Lines exceeding 1 MiB
are skipped explicitly. JSON escaping keeps ordinary stack traces in one physical
line; arbitrary multiline text is retained as physical records. CRI partial lines
are reassembled by its multiline parser. No exactly-once guarantee is claimed.

Agent metrics: `http://127.0.0.1:2020/api/v1/metrics/prometheus`; inspect output
retries/errors/dropped records, filesystem backlog, process RSS/CPU and free disk.
Export query results and metrics **before** removing owned containers/volumes.
`docker compose down` preserves state; `down -v` intentionally removes this project's
volumes and should only follow evidence export. Never remove an external backend.

Grafana Explore query, IDs kept as JSON fields rather than labels:

```logql
{job="nanofaas",node="edge-1"} | json | executionId="exec-7"
```

Labels are only job, node, environment, service. Logs supplement diagnostics;
scientific completions/offload accounting comes from SDK evidence and metrics.
Calibration must include the same logging format, level, agent and budgets as the
campaign. See the [logging validation dossier](../../docs/testing/edge-logging/README.md).

Official references: [Fluent Bit buffering](https://docs.fluentbit.io/manual/data-pipeline/buffering),
[Loki output](https://docs.fluentbit.io/manual/data-pipeline/outputs/loki),
[Loki retention](https://grafana.com/docs/loki/latest/operations/storage/retention/).
