# Forecasting future external arrivals

Build the optional `forecasting` module with `-PcontrolPlaneModules=all` or an explicit
module selection. It is excluded from the default build and inert unless enabled.
It depends on `forecasting-api` and core registration contracts, never offload or
P2P implementation classes.

```yaml
nanofaas:
  forecasting:
    enabled: true
    node-id: edge-a
    provider: ewma # or oracle
    alpha: 0.5
    window: 10s
    max-age: 1m
    max-functions: 1000
```

The example values are configuration examples, not an auction-period recommendation.
Node identity, observation window and maximum age must be explicit. Rates always
mean external requests/s, separated by function generation. Core observation occurs
once per valid HTTP invocation or enqueue request; forwarded hops and internal
retries are excluded. An observer also runs when oracle is selected, allowing the
observed stream to be compared to its supplied future trace.

EWMA uses complete UTC-aligned whole-second windows. Registration partway through a
window starts observation at the next boundary. Before the first complete observed
window it reports MISSING; a complete known window with no arrivals reports zero.
The first completed rate initializes the mean; subsequent rates use
`alpha * rate + (1-alpha) * mean`. Gaps containing only zeros decay analytically,
without dropping pending nonzero samples or iterating indefinitely. At most the
current and next window's counts are retained per active generation. Function
registration changes reset the series; the configured function bound limits memory.

For oracle experiments, upload a full version 1 `forecast.schema.json` document:

```sh
curl -X PUT http://localhost:8080/v1/admin/forecasting/trace \
  -H 'Content-Type: application/json' -H 'If-Match: 0' --data-binary @trace.json
curl http://localhost:8080/v1/admin/forecasting/trace
```

The upload provider is `oracle`. `If-Match` contains the expected current revision;
the replacement revision must increase. Concurrent replacements return 409. Invalid
fields, unknown versions, duplicates, overlaps and non-finite rates return 400;
bodies over 8 MiB return 413. A trace has at most 100000 rows. No rejected upload
changes the installed revision. Delivered forecast snapshots remain immutable.
Intervals are `[start,end)`; a query requires complete contiguous coverage and
returns its duration-weighted average. A gap, different node or different generation
returns MISSING. An expired trace or a future production timestamp returns STALE;
there is no implicit oracle-to-EWMA fallback. Keep `max-age` suitable for the trace
production and experimental period, then upload new revisions when necessary.

See [versioned contracts](contracts/one-shot/README.md) for provenance, measurement
units and the distinction between client arrivals and internal execution attempts.
