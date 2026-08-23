#!/usr/bin/env python3
"""Regenerate the document's tables from the raw archives.

Every table in sections 22, 22.2, 22.3 and appendix B comes from here. Hand
transcription is how a number that no longer matches its data survives in a
document: run this and paste, or better, diff it against what the document says.

Usage: build_tables.py <root>   where <root> holds azure-matrix-cpu{1,2,3,4}/
Reads prometheus-snapshot.json or .json.gz indifferently.
"""
import gzip
import json
import statistics
import sys
from datetime import datetime
from pathlib import Path

BUILDS = ("jvm", "native-os", "native-o3", "native-o3-g1")
LABEL = {"jvm": "JVM (seriale, C1)", "native-os": "Native −Os, seriale",
         "native-o3": "Native −O3, seriale", "native-o3-g1": "Native −O3, G1"}
# Windows are the load profile's own phases. The ramps between them are excluded
# from anything that treats the rate as constant.
PHASES = [("warm40", 0, 30), ("climb200", 30, 90), ("hold200", 90, 150),
          ("spike600", 160, 190), ("hold350", 320, 365), ("peak900", 375, 405)]
STEADY = [("spike600", 160, 190), ("hold350", 320, 365), ("peak900", 375, 405)]


def load(path):
    op = gzip.open if path.suffix == ".gz" else open
    with op(path, "rt", encoding="utf-8") as fh:
        return json.load(fh)


def cells(root, cpu, build):
    base = Path(root) / f"azure-matrix-cpu{cpu}" / build
    for run in sorted(base.glob("run-*")):
        snap = run / "metrics" / "prometheus-snapshot.json"
        if not snap.exists():
            snap = snap.with_suffix(".json.gz")
        summ = run / "summary.json"
        if snap.exists() and summ.exists():
            yield load(snap), json.loads(summ.read_text())


class Snap:
    def __init__(self, doc):
        self.q = doc["queries"]
        self.start = datetime.fromisoformat(doc["start"])

    def _secs(self, ts):
        return (datetime.fromisoformat(ts) - self.start).total_seconds()

    def at(self, name, t):
        pts = self.q.get(name, {}).get("points") or []
        if not pts:
            return None
        return float(min(pts, key=lambda p: abs(self._secs(p["timestamp"]) - t))["value"])

    def delta(self, name, a, b):
        lo, hi = self.at(name, a), self.at(name, b)
        return None if lo is None or hi is None else hi - lo

    def window(self, name, a, b):
        return [float(p["value"]) for p in self.q.get(name, {}).get("points") or []
                if a <= self._secs(p["timestamp"]) < b]

    def last(self, name):
        pts = self.q.get(name, {}).get("points") or []
        return float(pts[-1]["value"]) if pts else 0.0


def mean(xs):
    return statistics.mean(xs) if xs else 0.0


def sd(xs):
    """Sample standard deviation: three repetitions are a sample of the runs the
    configuration could have produced, not the population of them."""
    return statistics.stdev(xs) if len(xs) > 1 else 0.0


def pm(xs, fmt="%.1f"):
    """value ± dispersion, with n so the reader knows how coarse the ± is."""
    if not xs:
        return "—"
    if len(xs) == 1:
        return fmt % xs[0]
    return (fmt + " ± " + fmt) % (mean(xs), sd(xs))


def collect(root, cpu, build):
    """One dict of lists, one entry per repetition."""
    acc = {k: [] for k in ("rps", "p95", "p99", "drop", "disp", "thr",
                           "cpu_av", "cpu_pk", "rss", "qd", "qw")}
    for doc, summ in cells(root, cpu, build):
        s, k = Snap(doc), summ["k6"]
        acc["rps"].append(k["http_reqs"]["rate"])
        acc["p95"].append(k["http_req_duration"]["p(95)"])
        acc["p99"].append(k["http_req_duration"]["p(99)"])
        acc["drop"].append(k["http_req_failed"]["value"] * 100)
        acc["disp"].append(s.last("function_dispatch_total"))
        per = s.last("container_cpu_periods@control-plane")
        acc["thr"].append(s.last("container_cpu_throttled_periods@control-plane") / per * 100 if per else 0.0)
        cores = s.window("container_cpu_cores@control-plane", 0, 1e9)
        if cores:
            acc["cpu_pk"].append(max(cores))
            acc["cpu_av"].append(mean(s.window("container_cpu_cores@control-plane", 375, 405)))
        rss = s.window("container_memory_bytes@control-plane", 0, 1e9)
        if rss:
            acc["rss"].append(max(rss) / 1048576)
        acc["qd"] += s.window("function_queue_depth", 375, 405)
        n = s.last("function_queue_wait_count")
        if n:
            acc["qw"].append(s.last("function_queue_wait_sum") / n * 1000)
    return acc


def _k6_raw(run: Path):
    """The full k6 summary, which carries what summary.json does not.

    summary.json keeps http_reqs, http_req_failed, http_req_duration and checks.
    Generator exhaustion lives only here: dropped_iterations is what k6 could not
    issue, and vus against vus_max is how close it came to running out of them.
    Without both, a ceiling cannot be told apart from a generator that gave up.
    """
    f = run / "k6-summary.json"
    if not f.exists():
        return {}
    doc = json.loads(f.read_text())
    return doc.get("metrics", doc)


def table_generator(root):
    """Was the load generator the limit? Read this before any ceiling claim."""
    out = ["| cpu | build | arrivi emessi | non emessi | VU max / disponibili | esaurito? |",
           "|---:|---|---:|---:|---:|---|"]
    for cpu in (4, 3, 2, 1):
        for i, b in enumerate(BUILDS):
            base = Path(root) / f"azure-matrix-cpu{cpu}" / b
            issued, dropped, vus, vmax = [], [], [], []
            for run in sorted(base.glob("run-*")):
                m = _k6_raw(run)
                if not m:
                    continue
                issued.append(m["iterations"]["count"])
                dropped.append(m.get("dropped_iterations", {}).get("count", 0))
                vus.append(m.get("vus", {}).get("max", 0))
                vmax.append(m.get("vus_max", {}).get("value", 0))
            if not issued:
                continue
            share = mean(dropped) / (mean(issued) + mean(dropped)) * 100 if mean(issued) else 0
            verdict = "**sì**" if share > 1 else ("marginale" if share > 0 else "no")
            out.append("| %s | %s | %s | %s (%.1f %%) | %.0f / %.0f | %s |" % (
                f"**{cpu}**" if i == 0 else "", LABEL[b],
                pm([x / 1000 for x in issued], "%.1f") + "k",
                pm(dropped, "%.0f"), share, mean(vus), mean(vmax), verdict))
        out.append("| | | | | | |")
    return "\n".join(out[:-1])


def table_k6_phases(root, arms=("azure-load1x", "azure-load2x", "azure-load3x"), variant="jvm-c2"):
    """Where the caller's wait goes, from k6's own decomposition.

    These five have been in every summary since the first run and were never
    read. They separate the client's own costs - waiting for a free connection,
    the handshake, pushing bytes - from waiting on the server, which is what
    http_req_waiting is: request fully sent, first response byte not yet back.
    """
    out = ["| carico | totale | blocked | connecting | sending | **waiting** | receiving |",
           "|---|---:|---:|---:|---:|---:|---:|"]
    for arm in arms:
        rows = []
        for run in sorted((Path(root) / arm / variant).glob("run-*")):
            m = _k6_raw(run)
            if not m:
                continue
            rows.append([m[k]["avg"] for k in (
                "http_req_duration", "http_req_blocked", "http_req_connecting",
                "http_req_sending", "http_req_waiting", "http_req_receiving")])
        if not rows:
            continue
        avg = [mean(c) for c in zip(*rows)]
        out.append("| %s | %.1f ms | %.2f | %.2f | %.2f | **%.1f (%.0f %%)** | %.2f |" % (
            arm.replace("azure-load", ""), avg[0], avg[1], avg[2], avg[3],
            avg[4], avg[4] / avg[0] * 100, avg[5]))
    return "\n".join(out)


def table_latency(root):
    """Every column carries its dispersion over the three repetitions.

    Three is enough to say whether two rows differ and not enough to characterise
    a distribution: read the ± as "how far apart the three runs landed", not as a
    confidence interval. Where it is 0.0 the three agreed to the printed digit.
    """
    out = ["| cpu | build | n | rps | p95 (ms) | p99 (ms) | scarti % | dispatch |",
           "|---:|---|---:|---:|---:|---:|---:|---:|"]
    for cpu in (4, 3, 2, 1):
        for i, b in enumerate(BUILDS):
            a = collect(root, cpu, b)
            if not a["rps"]:
                continue
            out.append("| %s | %s | %d | %s | %s | %s | %s | %s |" % (
                f"**{cpu}**" if i == 0 else "", LABEL[b], len(a["rps"]),
                pm(a["rps"]), pm(a["p95"]), pm(a["p99"]), pm(a["drop"], "%.2f"),
                pm([x / 1000 for x in a["disp"]], "%.1f") + "k"))
        out.append("| | | | | | | | |")
    return "\n".join(out[:-1])


def table_resources(root):
    out = ["| cpu | build | core medi | core picco | strozz | RSS MiB | coda media | attesa (ms) |",
           "|---:|---|---:|---:|---:|---:|---:|---:|"]
    for cpu in (4, 3, 2, 1):
        for i, b in enumerate(BUILDS):
            a = collect(root, cpu, b)
            if not a["cpu_av"]:
                continue
            out.append("| %s | %s | %.2f | %.2f | %.1f %% | %.0f | %.1f | %.1f |" % (
                f"**{cpu}**" if i == 0 else "", LABEL[b], mean(a["cpu_av"]),
                mean(a["cpu_pk"]), mean(a["thr"]), mean(a["rss"]),
                mean(a["qd"]), mean(a["qw"])))
        out.append("| | | | | | | | |")
    return "\n".join(out[:-1])


def table_phases(root, cpu=4):
    """Mean service latency per phase: the warm-up curve, with its control."""
    head = "| build | " + " | ".join(n for n, _, _ in PHASES) + " |"
    out = [head, "|---" * (len(PHASES) + 1) + "|"]
    for b in ("jvm", "native-o3-g1", "native-o3"):
        per = [[] for _ in PHASES]
        for doc, _ in cells(root, cpu, b):
            s = Snap(doc)
            for i, (_, a, z) in enumerate(PHASES):
                c = s.delta("function_latency_count", a, z)
                t = s.delta("function_latency_sum", a, z)
                if c:
                    per[i].append(t / c * 1000)
        out.append("| " + LABEL[b] + " | " + " | ".join(
            f"{mean(x):.3f}" if x else "—" for x in per) + " |")
    return "\n".join(out)


def function_cpu_model(root, cpu=4, builds=("jvm", "native-o3-g1")):
    """Fixed and marginal CPU of the function container.

    Steady phases only: the 200 rps windows fall early enough to still carry
    warm-up, and including them moves the intercept by 46%. Builds that keep up
    only: the x axis must be the achieved rate, and at target 900 that runs from
    890 to 300 depending on the budget.
    """
    pts = []
    for b in builds:
        for doc, _ in cells(root, cpu, b):
            s = Snap(doc)
            for _, a, z in STEADY:
                c = s.delta("function_latency_count", a, z)
                cores = s.window("container_cpu_cores@word-stats-java", a, z)
                if c and cores:
                    pts.append((c / (z - a), mean(cores)))
    n = len(pts)
    sx = sum(x for x, _ in pts); sy = sum(y for _, y in pts)
    sxx = sum(x * x for x, _ in pts); sxy = sum(x * y for x, y in pts)
    slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    icept = (sy - slope * sx) / n
    ss = sum((y - (icept + slope * x)) ** 2 for x, y in pts)
    tt = sum((y - sy / n) ** 2 for _, y in pts)
    se = (ss / (n - 2) / (sxx - sx * sx / n)) ** 0.5
    rows = ["| | |", "|---|---|",
            f"| osservazioni | {n} |",
            f"| costo fisso | **{icept:.3f} core** |",
            f"| costo marginale | **{slope*1000:.3f} CPU-ms per richiesta** (errore standard {se*1000:.3f}) |",
            f"| significatività | marginale a **{slope/se:.1f} errori standard** sopra zero |",
            f"| R² | {1-ss/tt:.3f} |"]
    tab = ["", "| tasso | core totali | quota fissa |", "|---:|---:|---:|"]
    for r in (350, 600, 900):
        tot = icept + slope * r
        tab.append(f"| {r} rps | {tot:.3f} | {icept/tot*100:.0f} % |")
    return "\n".join(rows + tab)


JVM_VARIANTS = ("jvm", "jvm-g1", "jvm-c2", "jvm-g1-c2")
JVM_LABEL = {
    "jvm": "seriale + C1 *(baseline)*",
    "jvm-g1": "**G1** + C1",
    "jvm-c2": "seriale + **C2**",
    "jvm-g1-c2": "**G1 + C2**",
}


def _jvm_cells(root, cpu, variant):
    base = Path(root) / f"azure-jvm-2x2-cpu{cpu}" / variant
    for run in sorted(base.glob("run-*")):
        snap = run / "metrics" / "prometheus-snapshot.json"
        if not snap.exists():
            snap = snap.with_suffix(".json.gz")
        summ = run / "summary.json"
        if snap.exists() and summ.exists():
            yield load(snap), json.loads(summ.read_text())


def _jvm_row(root, cpu, variant):
    acc = {k: [] for k in ("p95", "p99", "drop", "disp", "thr", "cpu_av", "rss", "qw", "serv")}
    for doc, summ in _jvm_cells(root, cpu, variant):
        s, k = Snap(doc), summ["k6"]
        acc["p95"].append(k["http_req_duration"]["p(95)"])
        acc["p99"].append(k["http_req_duration"]["p(99)"])
        acc["drop"].append(k["http_req_failed"]["value"] * 100)
        acc["disp"].append(s.last("function_dispatch_total"))
        per = s.last("container_cpu_periods@control-plane")
        acc["thr"].append(s.last("container_cpu_throttled_periods@control-plane") / per * 100 if per else 0.0)
        acc["cpu_av"].append(mean(s.window("container_cpu_cores@control-plane", 375, 405)))
        rss = s.window("container_memory_bytes@control-plane", 0, 1e9)
        if rss:
            acc["rss"].append(max(rss) / 1048576)
        n = s.last("function_queue_wait_count")
        if n:
            acc["qw"].append(s.last("function_queue_wait_sum") / n * 1000)
        c = s.delta("function_latency_count", 320, 365)
        t = s.delta("function_latency_sum", 320, 365)
        if c:
            acc["serv"].append(t / c * 1000)
    return acc


def table_jvm_2x2(root):
    """Collector on one axis, JIT tiering on the other, at two budgets.

    Every column carries its spread over the three repetitions, because the
    factorial's whole job is to say whether a quadrant differs from another and
    a mean alone cannot: at one core the baseline's p95 moves by 13.5ms between
    runs, which is most of some of the differences being claimed.
    """
    out = ["| cpu | collettore + JIT | n | p95 (ms) | scarti % | dispatch | core | RSS MiB | strozz % |",
           "|---:|---|---:|---:|---:|---:|---:|---:|---:|"]
    for cpu in (2, 1):
        for i, v in enumerate(JVM_VARIANTS):
            a = _jvm_row(root, cpu, v)
            if not a["p95"]:
                continue
            out.append("| %s | %s | %d | %s | %s | %s | %s | %s | %s |" % (
                f"**{cpu}**" if i == 0 else "", JVM_LABEL[v], len(a["p95"]),
                pm(a["p95"]), pm(a["drop"], "%.2f"),
                pm([x / 1000 for x in a["disp"]], "%.1f") + "k",
                pm(a["cpu_av"], "%.2f"), pm(a["rss"], "%.0f"), pm(a["thr"], "%.1f")))
        out.append("| | | | | | | | | |")
    return "\n".join(out[:-1])


def table_jvm_effects(root):
    """The 2x2 read as effects: what each factor buys, and whether they add up.

    Written as differences against the baseline rather than as four rows, because
    the question the factorial exists to answer is how much of the change belongs
    to the collector and how much to the JIT - which four absolute numbers do not
    say on their own.
    """
    out = ["| cpu | effetto | Δ p95 | Δ scarti | Δ dispatch | Δ core | Δ p95 supera la dispersione? |",
           "|---:|---|---:|---:|---:|---:|---|"]
    for cpu in (2, 1):
        base = _jvm_row(root, cpu, "jvm")
        if not base["p95"]:
            continue
        for v, name in (("jvm-g1", "solo collettore (G1)"),
                        ("jvm-c2", "solo JIT (C2)"),
                        ("jvm-g1-c2", "entrambi")):
            a = _jvm_row(root, cpu, v)
            if not a["p95"]:
                continue
            d95 = mean(a["p95"]) - mean(base["p95"])
            # Crude on purpose: with three repetitions a t test would dress up
            # the same information. "Separato" means the two means differ by more
            # than the two spreads put together, which is the weakest claim the
            # data supports and the only one worth printing.
            spread = sd(a["p95"]) + sd(base["p95"])
            verdict = "**separato**" if abs(d95) > spread else "dentro la dispersione (±%.1f)" % spread
            out.append("| %s | %s | %+.1f ms | %+.1f pt | %+.1f %% | %+.2f | %s |" % (
                f"**{cpu}**" if v == "jvm-g1" else "", name, d95,
                mean(a["drop"]) - mean(base["drop"]),
                (mean(a["disp"]) / mean(base["disp"]) - 1) * 100,
                mean(a["cpu_av"]) - mean(base["cpu_av"]), verdict))
        out.append("| | | | | | | |")
    return "\n".join(out[:-1])


def _front_split(run: Path):
    """How a request's wall clock splits between the application and what is in
    front of it.

    k6 measures the whole thing from outside. `http_server_requests_seconds`
    measures only what happened inside the handler, tagged by the status the
    caller got. The difference is everything else, and it is the quantity the
    668ms of section 25 were never able to name.

    Rejections and successes are separated because their averages are three
    orders of magnitude apart, and one mean over both describes neither.
    """
    m = _k6_raw(run)
    snap = load(run / "metrics" / "prometheus-snapshot.json.gz")["queries"]
    last = lambda n: snap[n]["points"][-1]["value"]

    n_all = m["http_reqs"]["count"]
    n_bad = m["http_req_failed"]["passes"]
    n_ok = m["http_req_failed"]["fails"]
    d_all = m["http_req_duration"]["avg"]
    d_ok = m["http_req_duration{expected_response:true}"]["avg"]
    # k6 publishes the successes apart and everything together; the rejections
    # are the remainder of the weighted mean, never measured directly.
    d_bad = (d_all * n_all - d_ok * n_ok) / n_bad

    inside_ok = last("http_server_ok_sum") / last("http_server_ok_count") * 1000
    inside_bad = last("http_server_rejected_sum") / last("http_server_rejected_count") * 1000
    return (("servite", n_ok, d_ok, inside_ok), ("rifiutate", n_bad, d_bad, inside_bad))


def table_davanti(root):
    run = Path(root) / "azure-load2x-verify" / "jvm-c2" / "run-1"
    out = ["| esito | n | k6 (ms) | dentro l'handler | **davanti** | quota davanti |",
           "|---|---:|---:|---:|---:|---:|"]
    for label, n, outside, inside in _front_split(run):
        out.append(f"| {label} | {n:,} | {outside:.2f} | {inside:.3f} | "
                   f"**{outside - inside:.2f}** | {100 * (outside - inside) / outside:.1f} % |"
                   .replace(",", "."))
    return "\n".join(out)


def table_backlog(root):
    """Little's law against the backlog that is now visible.

    The control is the calm regime, and it is the part that makes the peak
    readable: where nothing is queued, N/X must come out equal to the handler's
    own time, because there is nowhere else for a request to be. It does. Where
    they diverge by two orders of magnitude, the requests are somewhere else.
    """
    from datetime import datetime
    run = Path(root) / "azure-load2x-verify" / "jvm-c2" / "run-1"
    snap = load(run / "metrics" / "prometheus-snapshot.json.gz")["queries"]
    ser = lambda n: [(datetime.fromisoformat(p["timestamp"]), p["value"]) for p in snap[n]["points"]]

    def rate(n):
        s = ser(n)
        return {b[0]: (b[1] - a[1]) / (b[0] - a[0]).total_seconds()
                for a, b in zip(s, s[1:]) if b[1] >= a[1]}

    active, loops = dict(ser("netty_connections_active")), dict(ser("netty_eventloop_pending"))
    ok_r, ko_r = rate("http_server_ok_count"), rate("http_server_rejected_count")
    ok_s, ko_s = rate("http_server_ok_sum"), rate("http_server_rejected_sum")
    t0 = min(active)

    out = ["| t | conn. attive | task in coda sui loop | X (req/s) | **W = N/X** | dentro l'handler |",
           "|---:|---:|---:|---:|---:|---:|"]
    for t in sorted(active):
        x = ok_r.get(t, 0) + ko_r.get(t, 0)
        if active[t] <= 100 or x <= 0:
            continue
        inside = (ok_s.get(t, 0) + ko_s.get(t, 0)) / x * 1000
        out.append(f"| +{(t - t0).total_seconds():.0f}s | {active[t]:.0f} | {loops[t]:.0f} | "
                   f"{x:.0f} | **{active[t] / x * 1000:.0f} ms** | {inside:.2f} ms |")

    calm = [t for t in sorted(active) if 400 < ok_r.get(t, 0) < 700 and not ko_r.get(t)]
    w = sum(active[t] / ok_r[t] for t in calm) / len(calm) * 1000
    d = sum(ok_s[t] / ok_r[t] for t in calm) / len(calm) * 1000
    out.append(f"| *controllo: {len(calm)} campioni a riposo, nessun rifiuto* | | | | "
               f"*{w:.2f} ms* | *{d:.2f} ms* |")
    return "\n".join(out)


BLOCKS = {
    "latenza": table_latency,
    "generatore": table_generator,
    "fasi-k6": table_k6_phases,
    "risorse": table_resources,
    "fasi": table_phases,
    "modello-cpu-funzione": function_cpu_model,
    "jvm-2x2": table_jvm_2x2,
    "jvm-effetti": table_jvm_effects,
    "davanti": table_davanti,
    "backlog": table_backlog,
}


def update(doc: Path, root: str) -> int:
    """Replace every <!-- tabella:NAME --> ... <!-- /tabella:NAME --> region.

    The document keeps the prose and owns nothing numeric between the markers,
    so a table can never drift from the data it claims to show.
    """
    text = doc.read_text(encoding="utf-8")
    changed = 0
    for name, fn in BLOCKS.items():
        opener, closer = f"<!-- tabella:{name} -->", f"<!-- /tabella:{name} -->"
        i = text.find(opener)
        j = text.find(closer)
        if i < 0 or j < 0:
            continue
        block = f"{opener}\n{fn(root)}\n{closer}"
        if text[i:j + len(closer)] != block:
            text = text[:i] + block + text[j + len(closer):]
            changed += 1
    doc.write_text(text, encoding="utf-8")
    return changed


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "raw"
    doc = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--update=")), None)
    if doc:
        print(f"tabelle aggiornate: {update(Path(doc), root)}")
    else:
        for name, fn in BLOCKS.items():
            print(f"<!-- tabella:{name} -->"); print(fn(root)); print(f"<!-- /tabella:{name} -->\n")
