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
    return statistics.pstdev(xs) if len(xs) > 1 else 0.0


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


def table_latency(root):
    out = ["| cpu | build | rps | p95 (ms) | p99 (ms) | scarti | dispatch |",
           "|---:|---|---:|---:|---:|---:|---:|"]
    for cpu in (4, 3, 2, 1):
        for i, b in enumerate(BUILDS):
            a = collect(root, cpu, b)
            if not a["rps"]:
                continue
            out.append("| %s | %s | %.1f | %.1f ± %.1f | %.1f | %.1f %% | %s |" % (
                f"**{cpu}**" if i == 0 else "", LABEL[b], mean(a["rps"]),
                mean(a["p95"]), sd(a["p95"]), mean(a["p99"]), mean(a["drop"]),
                f"{mean(a['disp']):,.0f}".replace(",", ".")))
        out.append("| | | | | | | |")
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


BLOCKS = {
    "latenza": table_latency,
    "risorse": table_resources,
    "fasi": table_phases,
    "modello-cpu-funzione": function_cpu_model,
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
