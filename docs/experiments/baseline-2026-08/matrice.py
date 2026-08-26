#!/usr/bin/env python3
"""La campagna letta come le due matrici che è, invece che come dodici run.

Uso:  python3 matrice.py [--doc README.md]

Le tabelle per run restano dove sono: sono il dato, con il loro spread. Queste
sono il modo di leggerlo. La differenza conta: «tre tetti, tre vincitori» è
invisibile finché ogni tetto sta in una tabella diversa.

Ogni cella qui aggrega TUTTE le celle di quella build in quella condizione,
anche quando vengono da run diversi - il che è legittimo solo perché la
riproducibilità fra sessioni è misurata, non assunta: vedi `riproducibilita()`.
"""
import argparse, gzip, json, statistics as st
from datetime import datetime
from pathlib import Path

RADICE = Path(__file__).parent / "raw"

# Dove vive ogni combinazione build × condizione. Le due sessioni che hanno
# misurato `jvm` nella stessa condizione compaiono entrambe: sono repliche.
CONDIZIONI = ("1 core / 2 GiB", "2 core / 2 GiB", "2 core / 1 GiB", "2 core / 512 MiB")
RUN = {
    "1 core / 2 GiB": ("A1-cpu1", "A1c"),
    "2 core / 2 GiB": ("A1b-cpu2", "A1d"),
    "2 core / 1 GiB": ("A2-mem1024", "A2c"),
    "2 core / 512 MiB": ("A2-mem512", "A2d"),
}
BUILD = ("jvm", "jvm-c2", "native-os", "native-o3", "native-o3-g1")
ETICHETTA = {"jvm": "JVM (seriale, C1)", "jvm-c2": "JVM (seriale, C2)",
             "native-os": "Native −Os, seriale", "native-o3": "Native −O3, seriale",
             "native-o3-g1": "Native −O3, G1"}
A3 = (("A3-sync-2x", "sync 2×"), ("A3-misto-2x", "misto 2×"),
      ("A3-sync-3x", "sync 3×"), ("A3-misto-3x", "misto 3×"))


def _queries(run: Path):
    for suf in (".json.gz", ".json"):
        p = run / "metrics" / f"prometheus-snapshot{suf}"
        if p.exists():
            raw = gzip.open(p).read() if p.suffix == ".gz" else p.read_bytes()
            return json.loads(raw)["queries"]
    return None


def misure(run: Path) -> dict | None:
    q = _queries(run)
    if q is None or not (run / "k6-summary.json").exists():
        return None
    def d(n):
        e = q.get(n); v = [x["value"] for x in e["points"] if x["value"] is not None] if e else []
        return (max(v) - min(v)) if v else 0.0
    def mx(n):
        e = q.get(n); v = [x["value"] for x in e["points"] if x["value"] is not None] if e else []
        return max(v) if v else float("nan")
    pts = q["function_dispatch_total"]["points"]
    w = (datetime.fromisoformat(pts[-1]["timestamp"]) - datetime.fromisoformat(pts[0]["timestamp"])).total_seconds()
    k6 = json.loads((run / "k6-summary.json").read_text())["metrics"]
    out = {
        "servite": d("function_dispatch_total") / w if w else float("nan"),
        "shed": 100 * k6["http_req_failed"]["value"],
        "p95": k6["http_req_duration"]["p(95)"],
        "p99": k6["http_req_duration"]["p(99)"],
        "memoria": mx("container_memory_bytes@control-plane") / 2**20,
    }
    collezioni = d("jvm_gc_collection_count")
    out["gc"] = 100 * d("jvm_gc_collection_time") / w if (w and collezioni) else float("nan")
    for porta in ("sync", "async"):
        a = d(f"function_admitted_{porta}") + d(f"function_admitted_{porta}@word-stats-javascript")
        r = d(f"function_refused_{porta}") + d(f"function_refused_{porta}@word-stats-javascript")
        out[f"rifiuti_{porta}"] = 100 * r / (a + r) if a + r else float("nan")
    return out


def raccogli(arm: str, build: str) -> list[dict]:
    base = RADICE / arm / build
    return [m for m in (misure(r) for r in sorted(base.glob("run-*"))) if m] if base.exists() else []


def _cella(valori, fmt="%.0f"):
    valori = [v for v in valori if v == v]
    if not valori:
        return "—"
    if len(valori) == 1:
        return fmt % valori[0]
    return f"{fmt % st.mean(valori)} ± {fmt % st.stdev(valori)}"


def matrice(chiave: str, fmt: str = "%.0f") -> str:
    righe = ["| build | " + " | ".join(CONDIZIONI) + " |", "|---|" + "---|" * len(CONDIZIONI)]
    for build in BUILD:
        celle = []
        for cond in CONDIZIONI:
            valori = [m[chiave] for arm in RUN[cond] for m in raccogli(arm, build)]
            celle.append(_cella(valori, fmt))
        if any(c != "—" for c in celle):
            righe.append(f"| {ETICHETTA[build]} | " + " | ".join(celle) + " |")
    return "\n".join(righe)


def riproducibilita() -> str:
    """La stessa build nella stessa condizione, misurata in due sessioni diverse.

    Non era pianificato: `jvm` è rientrato come baseline interna in ognuno dei
    run di `jvm-c2`, e questo rende ogni condizione una replica.
    """
    righe = ["| condizione | prima sessione | seconda sessione | distanza |", "|---|---|---|---|"]
    for cond in CONDIZIONI:
        a, b = RUN[cond]
        sa = [m["servite"] for m in raccogli(a, "jvm")]
        sb = [m["servite"] for m in raccogli(b, "jvm")]
        if len(sa) < 2 or len(sb) < 2:
            continue
        pooled = ((st.stdev(sa) ** 2 + st.stdev(sb) ** 2) / 2) ** 0.5
        dist = abs(st.mean(sa) - st.mean(sb)) / pooled if pooled else float("inf")
        righe.append(f"| {cond} | {st.mean(sa):.0f} ± {st.stdev(sa):.0f} | "
                     f"{st.mean(sb):.0f} ± {st.stdev(sb):.0f} | {dist:.2f} sd |")
    return "\n".join(righe)


def matrice_a3() -> str:
    METRICHE = (("servite/s", "servite", "%.0f"), ("rifiuti porta sync (%)", "rifiuti_sync", "%.2f"),
                ("rifiuti porta async (%)", "rifiuti_async", "%.2f"), ("p95 (ms)", "p95", "%.1f"),
                ("p99 (ms)", "p99", "%.0f"), ("memoria (MiB)", "memoria", "%.0f"))
    dati = {etichetta: raccogli(arm, "jvm-c2") for arm, etichetta in A3}
    righe = ["| misura | " + " | ".join(e for _, e in A3) + " |", "|---|" + "---|" * len(A3)]
    for nome, chiave, fmt in METRICHE:
        celle = [_cella([m[chiave] for m in dati[e]], fmt) for _, e in A3]
        if any(c != "—" for c in celle):
            righe.append(f"| {nome} | " + " | ".join(celle) + " |")
    return "\n".join(righe)


BLOCCHI = {
    "matrice-servite": lambda: matrice("servite", "%.0f"),
    "matrice-shed": lambda: matrice("shed", "%.2f"),
    "matrice-p95": lambda: matrice("p95", "%.1f"),
    "matrice-memoria": lambda: matrice("memoria", "%.0f"),
    "matrice-gc": lambda: matrice("gc", "%.2f"),
    "riproducibilita": riproducibilita,
    "matrice-a3": matrice_a3,
}


def main() -> None:
    ap = argparse.ArgumentParser(); ap.add_argument("--doc", type=Path); a = ap.parse_args()
    for nome, produci in BLOCCHI.items():
        contenuto = produci()
        print(f"\n### {nome}\n{contenuto}")
        if a.doc:
            t = a.doc.read_text()
            inizio, fine = f"<!-- {nome}:inizio -->", f"<!-- {nome}:fine -->"
            if inizio in t and fine in t:
                testa, resto = t.split(inizio, 1); _, coda = resto.split(fine, 1)
                a.doc.write_text(f"{testa}{inizio}\n{contenuto}\n{fine}{coda}")


if __name__ == "__main__":
    main()
