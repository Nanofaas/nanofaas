#!/usr/bin/env python3
"""Le tabelle del carico misto, calcolate dai grezzi.

Uso:  python3 tables.py <dir-di-run> [<dir-di-run> ...]
      dove ogni dir-di-run e' una directory di nanolab (es. azure-mixed-2x),
      con celle in <variante>/run-N/{k6-summary.json,metrics/prometheus-snapshot.json}.

Ogni numero qui viene da un file di run. Niente e' scritto a mano: il motivo e'
che questa serie di esperimenti ha gia' prodotto due volte cifre giuste calcolate
su dati sbagliati - un run confrontato con una base che aveva un pool di VU
diverso, e un run analizzato come 'debole' quando in realta' il processo misurato
era morto a meta'.
"""
import json
import statistics as st
import sys
from datetime import datetime
from pathlib import Path


def _series(queries, name):
    entry = queries.get(name)
    if entry is None:
        return []
    return [p["value"] for p in entry["points"] if p["value"] is not None]


def delta(queries, name):
    """Un contatore cresce solo: la finestra e' max - min."""
    values = _series(queries, name)
    return (max(values) - min(values)) if values else 0.0


def mean(queries, name):
    values = _series(queries, name)
    return st.mean(values) if values else float("nan")


def survived(queries):
    """Se il processo alla fine e' quello dell'inizio.

    process_uptime_seconds sale sempre, tranne attraverso un riavvio. Senza questo
    controllo una cella morta a meta' si legge come una cella lenta: i contatori
    ripartono da zero e il delta sulla finestra sembra solo piccolo.
    """
    up = _series(queries, "process_uptime_seconds")
    return all(b >= a for a, b in zip(up, up[1:])) if len(up) > 1 else None


def cell(path):
    queries = json.loads(path.read_text())["queries"]
    k6 = json.loads((path.parent.parent / "k6-summary.json").read_text())["metrics"]
    window = _window_seconds(queries)
    out = {"vivo": survived(queries), "finestra_s": window}

    dispatch, rejected = delta(queries, "function_dispatch_total"), delta(queries, "function_queue_rejected_total")
    out["rifiuti_%"] = 100 * rejected / (dispatch + rejected) if dispatch + rejected else float("nan")
    out["dispatch_s"] = dispatch / window if window else float("nan")
    out["coda"] = mean(queries, "function_queue_depth")

    for door in ("sync", "async"):
        admitted = delta(queries, f"function_admitted_{door}")
        refused = delta(queries, f"function_refused_{door}")
        total = admitted + refused
        out[f"rifiuti_{door}_%"] = 100 * refused / total if total else float("nan")
        out[f"arrivi_{door}"] = total
        out[f"coda_{door}"] = mean(queries, f"function_queue_depth_{door}")
        out[f"replay_{door}"] = delta(queries, f"function_replayed_{door}")
    arrivals = out["arrivi_sync"] + out["arrivi_async"]
    out["quota_async_%"] = 100 * out["arrivi_async"] / arrivals if arrivals else float("nan")
    out["chiavi_max"] = max(_series(queries, "idempotency_keys_held") or [float("nan")])
    store = _series(queries, "execution_store_size")
    out["record_max"] = max(store) if store else float("nan")
    heap = _series(queries, "jvm_heap_used_bytes")
    if heap and window:
        out["heap_crescita_MBs"] = (heap[-1] - heap[0]) / window / 1e6
        out["heap_finale_MB"] = heap[-1] / 1e6
        out["heap_picco_MB"] = max(heap) / 1e6

    periods, throttled = delta(queries, "container_cpu_periods@control-plane"), delta(queries, "container_cpu_throttled_periods@control-plane")
    out["strozzati_%"] = 100 * throttled / periods if periods else float("nan")
    out["cpu_picco"] = max(_series(queries, "container_cpu_cores@control-plane") or [float("nan")])
    for name, key in (("function_error_total", "errori"), ("function_timeout_total", "timeout"),
                      ("function_retry_total", "ritentativi")):
        out[key] = delta(queries, name)
    gc_sum, gc_cnt = delta(queries, "jvm_gc_pause_sum"), delta(queries, "jvm_gc_pause_count")
    if window:
        out["gc_frazione_%"] = 100 * gc_sum / window
    if gc_cnt:
        out["gc_pausa_media_ms"] = 1000 * gc_sum / gc_cnt
    lat_c, lat_s = delta(queries, "function_latency_count"), delta(queries, "function_latency_sum")
    if lat_c:
        out["servizio_ms"] = 1000 * lat_s / lat_c

    # k6: il chiamante sincrono ha la sua serie solo nei run misti; altrove e'
    # http_req_duration, che li' contiene solo traffico sincrono ed e' la stessa cosa.
    trend = k6.get("mixed_sync_duration") or k6.get("http_req_duration")
    out["p50_sync_ms"] = trend.get("med", float("nan"))
    out["p95_sync_ms"] = trend.get("p(95)", float("nan"))
    out["p99_sync_ms"] = trend.get("p(99)", float("nan"))
    out["max_sync_ms"] = trend.get("max", float("nan"))
    ack = k6.get("mixed_async_ack_duration")
    if ack:
        out["p95_ack_ms"] = ack.get("p(95)", float("nan"))
    idem = k6.get("mixed_sync_idem_duration")
    if idem:
        out["p95_idem_ms"] = idem.get("p(95)", float("nan"))
    reqs = k6.get("http_reqs", {})
    out["richieste_http_offerte_s"] = reqs.get("rate", float("nan"))
    out["richieste_http_offerte"] = reqs.get("count", float("nan"))
    refusal_rates = [k6.get("mixed_sync_refused")]
    if k6.get("mixed_async_ack_duration"):
        refusal_rates.append(k6.get("mixed_async_refused"))
    duration = reqs.get("count", 0) / reqs.get("rate", 0) if reqs.get("rate", 0) else 0
    out["workload_accettato_s"] = (
        sum(rate["fails"] for rate in refusal_rates) / duration
        if duration and all(rate is not None and "fails" in rate for rate in refusal_rates)
        else float("nan")
    )
    checks = k6.get("checks", {})
    out["check_falliti"] = checks.get("fails", float("nan"))
    probe = k6.get("mixed_probe_duration")
    if probe:
        out["probe_p50_ms"] = probe.get("med", float("nan"))
    out["scartati_gen"] = (k6.get("dropped_iterations") or {}).get("count", float("nan"))
    probe = k6.get("mixed_probe_duration")
    if probe:
        out["probe_p95_ms"] = probe.get("p(95)", float("nan"))
        out["probe_max_ms"] = probe.get("max", float("nan"))
    over = k6.get("mixed_probe_over_budget")
    if over:
        passes, fails = over.get("passes", 0), over.get("fails", 0)
        out["probe_fuori_budget_%"] = 100 * passes / (passes + fails) if passes + fails else float("nan")

    rate = k6.get("mixed_idem_same_execution")
    if rate:
        passes, fails = rate.get("passes", 0), rate.get("fails", 0)
        out["coppie_idem"] = passes + fails
        out["idem_ok_%"] = 100 * passes / (passes + fails) if passes + fails else float("nan")
    return out


def _window_seconds(queries):
    for name in ("function_dispatch_total", "function_queue_depth"):
        entry = queries.get(name)
        if entry and len(entry["points"]) > 1:
            first = datetime.fromisoformat(entry["points"][0]["timestamp"])
            last = datetime.fromisoformat(entry["points"][-1]["timestamp"])
            return (last - first).total_seconds()
    return float("nan")


def summarise(cells, key):
    values = [c[key] for c in cells if key in c and c[key] == c[key]]
    if not values:
        return "—"
    if len(values) == 1:
        return f"{values[0]:.1f}"
    return f"{st.mean(values):.1f} ± {st.stdev(values):.1f}"


def separation(a, b):
    """Distanza in unita' di deviazione standard aggregata. NON un valore p."""
    a, b = [x for x in a if x == x], [x for x in b if x == x]
    if len(a) < 2 or len(b) < 2:
        return None
    pooled = ((st.stdev(a) ** 2 + st.stdev(b) ** 2) / 2) ** 0.5
    if pooled == 0:
        return float("inf") if st.mean(a) != st.mean(b) else 0.0
    return abs(st.mean(a) - st.mean(b)) / pooled


ROWS = [
    ("quota async degli arrivi (%) (solo word-stats-java)", "quota_async_%"),
    ("rifiuti complessivi (%) (solo word-stats-java)", "rifiuti_%"),
    ("  porta sync (%) (solo word-stats-java)", "rifiuti_sync_%"),
    ("  porta async (%) (solo word-stats-java)", "rifiuti_async_%"),
    ("workload accettato/s (Java+JS; sync completate + ACK async)", "workload_accettato_s"),
    ("HTTP totali offerti/s (incl. probe management)", "richieste_http_offerte_s"),
    ("HTTP totali offerti (incl. probe management)", "richieste_http_offerte"),
    ("p50 chiamante sync (ms)", "p50_sync_ms"),
    ("p95 chiamante sync (ms)", "p95_sync_ms"),
    ("p99 chiamante sync (ms)", "p99_sync_ms"),
    ("max chiamante sync (ms)", "max_sync_ms"),
    ("p95 ack async (ms)", "p95_ack_ms"),
    ("p95 coppia idempotente (ms)", "p95_idem_ms"),
    ("servizio lato server (ms) (solo word-stats-java)", "servizio_ms"),
    ("dispatch/s (solo word-stats-java)", "dispatch_s"),
    ("coda media (solo word-stats-java)", "coda"),
    ("  quota sync (solo word-stats-java)", "coda_sync"),
    ("  quota async (solo word-stats-java)", "coda_async"),
    ("record in ExecutionStore (max)", "record_max"),
    ("crescita heap (MB/s)", "heap_crescita_MBs"),
    ("heap a fine run (MB)", "heap_finale_MB"),
    ("heap al picco (MB)", "heap_picco_MB"),
    ("chiavi di idempotenza tenute", "chiavi_max"),
    ("coppie idempotenti", "coppie_idem"),
    ("  stessa esecuzione (%)", "idem_ok_%"),
    ("probe liveness p50 (ms)", "probe_p50_ms"),
    ("probe liveness p95 (ms)", "probe_p95_ms"),
    ("probe liveness max (ms)", "probe_max_ms"),
    ("  campioni oltre 1000 ms (%)", "probe_fuori_budget_%"),
    ("errori (solo word-stats-java)", "errori"),
    ("timeout (solo word-stats-java)", "timeout"),
    ("ritentativi (solo word-stats-java)", "ritentativi"),
    ("check k6 falliti", "check_falliti"),
    ("tempo in GC (%)", "gc_frazione_%"),
    ("pausa GC media (ms)", "gc_pausa_media_ms"),
    ("periodi CFS strozzati (%)", "strozzati_%"),
    ("cpu al picco (core)", "cpu_picco"),
    ("scartati dal generatore", "scartati_gen"),
]


def _snapshots(root):
    """Una directory di run, o una sua singola variante.

    Puntare a azure-ab-hop prende TUTTE le varianti sotto di essa e le media
    insieme, il che per un A/B significa mediare i due bracci. Passare
    azure-ab-hop/jvm-c2 prende solo quello.
    """
    found = sorted(root.glob("*/run-*/metrics/prometheus-snapshot.json"))
    return found or sorted(root.glob("run-*/metrics/prometheus-snapshot.json"))


def main(dirs):
    groups = {}
    for d in dirs:
        root = Path(d)
        cells = [cell(p) for p in _snapshots(root)]
        if not cells:
            print(f"!! {root.name}: nessuna cella", file=sys.stderr)
            continue
        dead = [i + 1 for i, c in enumerate(cells) if c["vivo"] is False]
        if dead:
            print(f"!! {root.name}: il control plane e' RIPARTITO nelle celle {dead} — "
                  f"quei numeri non descrivono un solo processo", file=sys.stderr)
        label = root.name if root.parent.name == "runs" else f"{root.parent.name}/{root.name}"
        for noise in ("azure-matrix-", "azure-", "-workload", "-baseline"):
            label = label.replace(noise, "")
        label = label.strip("/")
        groups[label] = cells

    names = list(groups)
    cells_wide = max((len(summarise(g, k)) for g in groups.values() for _, k in ROWS), default=10)
    width = max(max((len(n) for n in names), default=10), cells_wide) + 2
    print(f"{'':34}" + "".join(f"{n:>{width}}" for n in names))
    print("-" * (34 + width * len(names)))
    for label, key in ROWS:
        print(f"{label:34}" + "".join(f"{summarise(groups[n], key):>{width}}" for n in names))
    pairs = [(a, b) for i, a in enumerate(names) for b in names[i + 1:]
             if a.replace("sync", "").replace("mixed", "") == b.replace("sync", "").replace("mixed", "")]
    for a, b in (pairs or ([(names[0], names[1])] if len(names) == 2 else [])):
        print(f"\nseparazione {a} contro {b}, in deviazioni standard aggregate")
        print("(non e' un valore p: tre celle non reggono l'affermazione che un valore p fa)")
        for label, key in ROWS:
            sep = separation([c.get(key, float("nan")) for c in groups[a]],
                             [c.get(key, float("nan")) for c in groups[b]])
            if sep is not None and sep == sep and sep > 1.0:
                print(f"  {label:34} {sep:6.1f}σ")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1:])
