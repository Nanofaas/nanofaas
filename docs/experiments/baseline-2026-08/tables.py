#!/usr/bin/env python3
"""Le tabelle della campagna baseline, calcolate dai grezzi.

Uso:  python3 tables.py <dir-di-run> [--doc README.md]

<dir-di-run> e' una directory di confronto di NanoLab: un manifest e una cella
per variante e ripetizione, in <variante>/run-N/.

Ogni numero viene da un file di run. Niente e' scritto a mano, perche' questa
serie ha gia' prodotto due volte cifre giuste calcolate su dati sbagliati.

Discendente diretto di ../archive/mixed-workload/tables.py: stessi lettori,
stesse trappole gia' pagate (un campo assente non e' uno zero; una cella morta
a meta' non e' una cella lenta).
"""
import argparse
import gzip
import json
import statistics as st
from datetime import datetime
from pathlib import Path

# Il control plane pubblica le serie function_* per una sola funzione (la
# primaria); container_*@<nome> le ha per tutte.
PRIMARY = "word-stats-java"


def _open(path: Path):
    raw = gzip.open(path).read() if path.suffix == ".gz" else path.read_bytes()
    return json.loads(raw)


def _snapshot(run: Path) -> Path | None:
    for name in ("prometheus-snapshot.json", "prometheus-snapshot.json.gz"):
        candidate = run / "metrics" / name
        if candidate.exists():
            return candidate
    return None


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


def peak(queries, name):
    values = _series(queries, name)
    return max(values) if values else float("nan")


def survived(queries):
    """Se il processo alla fine e' quello dell'inizio.

    process_uptime_seconds sarebbe il segnale diretto, ma il catalogo di NanoLab
    su main non lo raccoglie. Un contatore monotono serve allo stesso scopo:
    function_dispatch_total puo' solo salire, tranne attraverso un riavvio che
    lo riporta a zero. E lo pubblicano tutte le build, anche quelle native che
    non hanno nessuna serie jvm_*.

    Senza questo controllo una cella morta a meta' si legge come una cella lenta.
    """
    values = _series(queries, "function_dispatch_total")
    if len(values) < 2:
        return None
    return all(b >= a for a, b in zip(values, values[1:]))


def _window_seconds(queries):
    for name in ("function_dispatch_total", "function_queue_depth"):
        entry = queries.get(name)
        if entry and len(entry["points"]) > 1:
            first = datetime.fromisoformat(entry["points"][0]["timestamp"])
            last = datetime.fromisoformat(entry["points"][-1]["timestamp"])
            return (last - first).total_seconds()
    return float("nan")


def cell(run: Path) -> dict:
    snapshot = _snapshot(run)
    if snapshot is None:
        raise FileNotFoundError(f"nessuno snapshot Prometheus in {run}")
    queries = _open(snapshot)["queries"]
    k6 = json.loads((run / "k6-summary.json").read_text())["metrics"]
    window = _window_seconds(queries)
    out = {"vivo": survived(queries), "finestra_s": window}

    dispatch = delta(queries, "function_dispatch_total")
    rejected = delta(queries, "function_queue_rejected_total")
    offered = dispatch + rejected
    out["dispatch_s"] = dispatch / window if window else float("nan")
    out["rifiuti_%"] = 100 * rejected / offered if offered else float("nan")
    out["coda"] = mean(queries, "function_queue_depth")
    for name, key in (("function_error_total", "errori"),
                      ("function_timeout_total", "timeout"),
                      ("function_retry_total", "ritentativi")):
        out[key] = delta(queries, name)

    # Il costo del control plane: cio' che questo esperimento sta confrontando.
    out["rss_cp_MB"] = peak(queries, "container_memory_bytes@control-plane") / 1e6
    out["rss_cp_medio_MB"] = mean(queries, "container_memory_bytes@control-plane") / 1e6
    out["cpu_cp_picco"] = peak(queries, "container_cpu_cores@control-plane")
    out["cpu_cp_medio"] = mean(queries, "container_cpu_cores@control-plane")
    periods = delta(queries, "container_cpu_periods@control-plane")
    throttled = delta(queries, "container_cpu_throttled_periods@control-plane")
    out["strozzati_%"] = 100 * throttled / periods if periods else float("nan")

    # Le funzioni sono fisse fra le varianti: se si muovono, si e' mosso qualcosa
    # che non doveva, e il confronto ha due parti mobili invece di una.
    for function in (PRIMARY, "word-stats-javascript"):
        short = "java" if function.endswith("java") else "js"
        out[f"rss_{short}_MB"] = peak(queries, f"container_memory_bytes@{function}") / 1e6
        out[f"cpu_{short}_picco"] = peak(queries, f"container_cpu_cores@{function}")

    # Nessuna build nativa pubblica una serie jvm_*: assente qui vuol dire
    # "questa domanda non si applica", non "zero".
    heap = _series(queries, "jvm_heap_used_bytes")
    if heap:
        out["heap_picco_MB"] = max(heap) / 1e6
    gc_sum, gc_cnt = delta(queries, "jvm_gc_pause_sum"), delta(queries, "jvm_gc_pause_count")
    if gc_cnt:
        out["gc_pausa_media_ms"] = 1000 * gc_sum / gc_cnt
        out["gc_pause"] = gc_cnt
    if window and gc_sum:
        out["gc_frazione_%"] = 100 * gc_sum / window

    latency_count = delta(queries, "function_latency_count")
    if latency_count:
        out["servizio_ms"] = 1000 * delta(queries, "function_latency_sum") / latency_count

    duration = k6.get("http_req_duration", {})
    out["p50_ms"] = duration.get("med", float("nan"))
    out["p95_ms"] = duration.get("p(95)", float("nan"))
    out["p99_ms"] = duration.get("p(99)", float("nan"))
    out["max_ms"] = duration.get("max", float("nan"))
    reqs = k6.get("http_reqs", {})
    out["offerte_s"] = reqs.get("rate", float("nan"))
    out["offerte"] = reqs.get("count", float("nan"))
    # Un campo assente resta assente: 0 sarebbe una misura, e non l'abbiamo fatta.
    out["scartati_gen"] = (k6.get("dropped_iterations") or {}).get("count", float("nan"))
    checks = k6.get("checks", {})
    out["check_falliti"] = checks.get("fails", float("nan"))
    failed = k6.get("http_req_failed", {})
    out["http_falliti_%"] = 100 * failed["value"] if "value" in failed else float("nan")

    sizes = run.parent.parent / "image-sizes.json"
    if sizes.exists():
        out["immagine_MB"] = json.loads(sizes.read_text()).get(run.parent.name, float("nan"))
    return out


def variants(root: Path) -> list[tuple[str, str]]:
    manifest = json.loads((root / "comparison-manifest.json").read_text())
    return [(v["key"], v["label"]) for v in manifest["variants"]]


def cells(root: Path, key: str) -> list[dict]:
    return [cell(run) for run in sorted((root / key).glob("run-*")) if _snapshot(run)]


def summarise(values, fmt="%.1f"):
    values = [v for v in values if v == v]
    if not values:
        return "—"
    if len(values) == 1:
        return fmt % values[0]
    return f"{fmt % st.mean(values)} ± {fmt % st.stdev(values)}"


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
    ("celle", "__n", None),
    ("processo vivo a fine cella", "__vivo", None),
    ("dispatch/s", "dispatch_s", "%.0f"),
    ("richieste offerte/s (k6)", "offerte_s", "%.0f"),
    ("rifiuti (%)", "rifiuti_%", "%.2f"),
    ("HTTP falliti (%)", "http_falliti_%", "%.2f"),
    ("p50 chiamante (ms)", "p50_ms", "%.2f"),
    ("p95 chiamante (ms)", "p95_ms", "%.1f"),
    ("p99 chiamante (ms)", "p99_ms", "%.1f"),
    ("max chiamante (ms)", "max_ms", "%.0f"),
    ("servizio lato server (ms)", "servizio_ms", "%.2f"),
    ("coda media", "coda", "%.1f"),
    ("RSS control plane, picco (MB)", "rss_cp_MB", "%.0f"),
    ("RSS control plane, medio (MB)", "rss_cp_medio_MB", "%.0f"),
    ("CPU control plane, picco (core)", "cpu_cp_picco", "%.2f"),
    ("CPU control plane, media (core)", "cpu_cp_medio", "%.2f"),
    ("periodi CPU strozzati (%)", "strozzati_%", "%.1f"),
    ("heap JVM al picco (MB)", "heap_picco_MB", "%.0f"),
    ("pause GC", "gc_pause", "%.0f"),
    ("pausa GC media (ms)", "gc_pausa_media_ms", "%.1f"),
    ("tempo in GC (%)", "gc_frazione_%", "%.2f"),
    ("immagine (MB)", "immagine_MB", "%.0f"),
    ("RSS word-stats-java, picco (MB)", "rss_java_MB", "%.0f"),
    ("RSS word-stats-javascript, picco (MB)", "rss_js_MB", "%.0f"),
    ("errori", "errori", "%.0f"),
    ("timeout", "timeout", "%.0f"),
    ("iterazioni scartate dal generatore", "scartati_gen", "%.0f"),
    ("check k6 falliti", "check_falliti", "%.0f"),
]


def render(root: Path) -> str:
    keys = variants(root)
    data = {key: cells(root, key) for key, _ in keys}
    present = [(k, label) for k, label in keys if data[k]]
    if not present:
        return "_Nessuna cella con dati._"

    header = "| misura | " + " | ".join(label for _, label in present) + " |"
    rule = "|---|" + "---|" * len(present)
    lines = [header, rule]
    for name, key, fmt in ROWS:
        values = []
        for variant, _ in present:
            group = data[variant]
            if key == "__n":
                values.append(str(len(group)))
            elif key == "__vivo":
                dead = [c for c in group if c["vivo"] is False]
                values.append("si, tutte" if not dead else f"**NO: {len(dead)} morte**")
            else:
                values.append(summarise([c.get(key, float("nan")) for c in group], fmt))
        if all(v == "—" for v in values):
            continue
        lines.append(f"| {name} | " + " | ".join(values) + " |")

    baseline = present[0][0]
    notes = []
    for variant, label in present[1:]:
        for key, name in (("dispatch_s", "dispatch/s"), ("p95_ms", "p95"), ("rss_cp_MB", "RSS")):
            gap = separation([c.get(key, float("nan")) for c in data[baseline]],
                             [c.get(key, float("nan")) for c in data[variant]])
            if gap is not None and gap >= 2:
                notes.append(f"- {label} contro {present[0][1]}: {name} separati di {gap:.1f} sd aggregate")
    if notes:
        lines += ["", "Separazioni oltre 2 deviazioni standard aggregate (non sono valori p):", *notes]
    return "\n".join(lines)


def write_into(doc: Path, marker: str, table: str) -> bool:
    start, end = f"<!-- {marker}:inizio -->", f"<!-- {marker}:fine -->"
    text = doc.read_text()
    if start not in text or end not in text:
        return False
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    doc.write_text(f"{head}{start}\n{table}\n{end}{tail}")
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--doc", type=Path)
    parser.add_argument("--marker", default="A1")
    args = parser.parse_args()
    table = render(args.run_dir)
    print(table)
    if args.doc and write_into(args.doc, args.marker, table):
        print(f"\nscritta in {args.doc} fra i marcatori {args.marker}")
    elif args.doc:
        print(f"\nmarcatori {args.marker} assenti in {args.doc}: tabella non scritta")


if __name__ == "__main__":
    main()
