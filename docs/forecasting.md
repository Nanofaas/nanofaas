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
    provider: ewma # or oracle, holt, ari
    alpha: 0.5
    window: 10s
    max-age: 1m
    max-functions: 1000
    history: 360 # complete windows kept for holt and ari
    holt:
      level: 0.4
      trend: 0.4
      damping: 0.95
    ari:
      order: 6
```

The example values are configuration examples, not an auction-period recommendation.
Node identity, observation window and maximum age must be explicit. Rates always
mean external requests/s, separated by function generation. Core observation occurs
once per valid HTTP invocation or enqueue request; forwarded hops and internal
retries are excluded. EWMA, Holt and ARI observe whichever provider is selected,
oracle included, so the observed stream can be compared to a supplied future trace
and a [runtime switch](#switching-the-provider-at-runtime) needs no warm-up.

EWMA uses complete UTC-aligned whole-second windows. Registration partway through a
window starts observation at the next boundary. Before the first complete observed
window it reports MISSING; a complete known window with no arrivals reports zero.
The first completed rate initializes the mean; subsequent rates use
`alpha * rate + (1-alpha) * mean`. Gaps containing only zeros decay analytically,
without dropping pending nonzero samples or iterating indefinitely. At most the
current and next window's counts are retained per active generation. Function
registration changes reset the series; the configured function bound limits memory.

## Holt and ARI

Both count the same complete windows as EWMA and keep the latest `history` of them per
generation, 8 bytes each; a gap without arrivals enters as at most `history` zeros.
Unlike EWMA, their forecast depends on how far ahead the queried interval lies: each
window after the latest complete one gets its own forecast, and the answer is their
duration-weighted mean over `[start,end)`. Windows more than 4096 ahead repeat the
4096th. MISSING and STALE follow the EWMA rules.

Each query copies the completed windows and their revision/time under the observation
lock, then calculates its forecast after releasing the lock. Model fitting therefore
does not block arrival recording, and concurrent observations cannot alter the query's
captured history or snapshot metadata.

- `holt` is Holt's linear method with a damped trend, run over the retained windows
  from the oldest: `level = l * rate + (1-l) * (level + d * trend)` and
  `trend = t * (level - previous) + (1-t) * d * trend`. Its forecast `s` windows
  ahead is `level + (d + d^2 + ... + d^s) * trend`, never below zero. `level` (`l`),
  `trend` (`t`) and `damping` (`d`) are in (0, 1]; damping 1 extends the trend
  undamped.
- `ari` fits, by least squares with a negligible ridge, each window-to-window change
  on an intercept and the `order` previous changes over the retained windows, then
  iterates the fit forward. A fitted autoregression can be explosive, so each
  forecast stays between zero and twice the largest rate of the latest 60 windows.
  With fewer than `2 * order + 2` retained windows, or a degenerate fit, it forecasts
  the latest window's rate. `order` is in [1, 64] and at most `(history - 2) / 2`.

## Switching the provider at runtime

When the `runtime-config` module is built in (a weak dependency of this module), the
forecasting module contributes a `forecasting` namespace to its admin API, enabled like
every admin route by `nanofaas.admin.runtime-config.enabled=true`:

```sh
curl -fsS http://localhost:8080/v1/admin/runtime-config
curl -fsS -X PATCH http://localhost:8080/v1/admin/runtime-config/forecasting \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":0,"values":{"provider":"ari","ariOrder":6}}'
curl -fsS http://localhost:8080/v1/admin/runtime-config/forecasting
```

Replace the `0` with the revision the first `curl` read: a stale revision answers
`409`, an invalid value `422`. `provider` is one of the namespace's `available` ids
(`ewma`, `oracle`, `holt`, `ari`); `holtLevel`, `holtTrend`, `holtDamping` and
`ariOrder` replace the model parameters, which Holt and ARI apply to the windows
already retained. The next forecast query uses the change; an in-flight query keeps
the provider and model parameters selected when it began. `alpha`, `window`,
`max-age` and `history` stay as started, because existing series were built with
them. A change does not persist across a restart (`persistence: restart`): the
configured provider and parameters win again on the next boot. In a native image the
admin API exists only when the flag is set at build time, as described in
[manual scheduler switching](control-plane.md#manual-scheduler-switching).

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
