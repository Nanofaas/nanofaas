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

    periods, throttled = delta(queries, "container_cpu_periods@control-plane"), delta(queries, "container_cpu_throttled_periods@control-plane")
    out["strozzati_%"] = 100 * throttled / periods if periods else float("nan")
    out["cpu_picco"] = max(_series(queries, "container_cpu_cores@control-plane") or [float("nan")])

    # k6: il chiamante sincrono ha la sua serie solo nei run misti; altrove e'
    # http_req_duration, che li' contiene solo traffico sincrono ed e' la stessa cosa.
    trend = k6.get("mixed_sync_duration") or k6.get("http_req_duration")
    out["p95_sync_ms"] = trend.get("p(95)", float("nan"))
    out["scartati_gen"] = (k6.get("dropped_iterations") or {}).get("count", 0)
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
    ("quota async degli arrivi (%)", "quota_async_%"),
    ("rifiuti complessivi (%)", "rifiuti_%"),
    ("  porta sync (%)", "rifiuti_sync_%"),
    ("  porta async (%)", "rifiuti_async_%"),
    ("p95 chiamante sync (ms)", "p95_sync_ms"),
    ("dispatch/s", "dispatch_s"),
    ("coda media", "coda"),
    ("  quota sync", "coda_sync"),
    ("  quota async", "coda_async"),
    ("chiavi di idempotenza tenute", "chiavi_max"),
    ("coppie idempotenti", "coppie_idem"),
    ("  stessa esecuzione (%)", "idem_ok_%"),
    ("probe liveness p95 (ms)", "probe_p95_ms"),
    ("probe liveness max (ms)", "probe_max_ms"),
    ("  campioni oltre 1000 ms (%)", "probe_fuori_budget_%"),
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
        groups[root.name if root.parent.name == "runs" else f"{root.parent.name}/{root.name}"] = cells

    names = list(groups)
    width = max((len(n) for n in names), default=10) + 2
    print(f"{'':34}" + "".join(f"{n:>{width}}" for n in names))
    print("-" * (34 + width * len(names)))
    for label, key in ROWS:
        print(f"{label:34}" + "".join(f"{summarise(groups[n], key):>{width}}" for n in names))
    if len(names) == 2:
        print(f"\nseparazione fra {names[0]} e {names[1]}, in deviazioni standard aggregate:")
        for label, key in ROWS:
            s = separation([c.get(key, float('nan')) for c in groups[names[0]]],
                           [c.get(key, float('nan')) for c in groups[names[1]]])
            if s is not None:
                print(f"  {label:34} {s:5.1f}σ")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1:])
