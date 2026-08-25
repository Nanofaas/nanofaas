#!/usr/bin/env python3
"""Una riga per run: che cosa è stato misurato e come è andata.

Uso:  python3 sintesi.py [--doc README.md]

Legge raw/ e basta. Serve a vedere la campagna intera in una schermata, non a
sostituire le tabelle per esperimento - quelle stanno nel documento, calcolate
da tables.py, e portano lo spread che qui non ci starebbe.
"""
import argparse, gzip, json, statistics as st
from datetime import datetime
from pathlib import Path

CONDIZIONE = {
    "A1-cpu1": "4 build · 1 core · 2 GiB", "A1b-cpu2": "4 build · 2 core · 2 GiB",
    "A2-mem1024": "4 build · 2 core · 1 GiB", "A2-mem512": "4 build · 2 core · 512 MiB",
    "A1c": "jvm vs c2 · 1 core · 2 GiB", "A1d": "jvm vs c2 · 2 core · 2 GiB",
    "A2c": "jvm vs c2 · 2 core · 1 GiB", "A2d": "jvm vs c2 · 2 core · 512 MiB",
    "A3-sync-2x": "jvm-c2 · sync · 2x", "A3-misto-2x": "jvm-c2 · misto · 2x",
    "A3-sync-3x": "jvm-c2 · sync · 3x", "A3-misto-3x": "jvm-c2 · misto · 3x",
}

def leggi(run):
    for suf in (".json.gz", ".json"):
        p = run / "metrics" / f"prometheus-snapshot{suf}"
        if p.exists():
            raw = gzip.open(p).read() if p.suffix == ".gz" else p.read_bytes()
            return json.loads(raw)["queries"]
    return None

def cella(run):
    q = leggi(run)
    if q is None:
        return None
    def d(n):
        e = q.get(n); v = [x["value"] for x in e["points"] if x["value"] is not None] if e else []
        return (max(v) - min(v)) if v else 0.0
    pts = q["function_dispatch_total"]["points"]
    w = (datetime.fromisoformat(pts[-1]["timestamp"]) - datetime.fromisoformat(pts[0]["timestamp"])).total_seconds()
    k6 = json.loads((run / "k6-summary.json").read_text())["metrics"]
    # Il vivo/morto: uptime dove c'è, altrimenti il surrogato monotono.
    up = [x["value"] for x in q.get("process_uptime_seconds", {"points": []})["points"] if x["value"] is not None]
    if len(up) > 1:
        vivo = all(b >= a for a, b in zip(up, up[1:]))
    else:
        s = [x["value"] for x in pts if x["value"] is not None]
        vivo = all(b >= a for a, b in zip(s, s[1:])) if len(s) > 1 else None
    return {
        "servite": d("function_dispatch_total") / w if w else float("nan"),
        "falliti": 100 * k6["http_req_failed"]["value"],
        "p95": k6["http_req_duration"]["p(95)"],
        "offerte": k6["http_reqs"]["rate"],
        "vivo": vivo,
    }

def main() -> None:
    ap = argparse.ArgumentParser(); ap.add_argument("--doc", type=Path); a = ap.parse_args()
    righe = ["| esperimento | condizione | celle | offerte/s | servite/s | HTTP falliti | p95 | tutte vive |",
             "|---|---|---|---|---|---|---|---|"]
    for nome, condizione in CONDIZIONE.items():
        base = Path(__file__).parent / "raw" / nome
        celle = [c for c in (cella(r) for v in sorted(base.glob("*")) if v.is_dir()
                             for r in sorted(v.glob("run-*"))) if c]
        if not celle:
            continue
        def med(k, f="%.0f"):
            v = [c[k] for c in celle if c[k] == c[k]]
            return f % st.mean(v) if v else "—"
        morte = sum(1 for c in celle if c["vivo"] is False)
        righe.append(f"| {nome} | {condizione} | {len(celle)} | {med('offerte')} | {med('servite')} | "
                     f"{med('falliti', '%.1f')}% | {med('p95', '%.1f')} ms | "
                     f"{'sì' if not morte else f'**NO: {morte}**'} |")
    tabella = "\n".join(righe)

    # Il registro: quando e' stata eseguita ogni run, letto da STATO.md dove la
    # coda lo ha scritto passo per passo. Le run precedenti alla coda non ci sono
    # e prendono la data di modifica del loro archivio.
    stato = Path(__file__).parent / "STATO.md"
    quando: dict[str, str] = {}
    if stato.exists():
        for riga in stato.read_text().splitlines():
            if ": avvio" in riga:
                data, nome = riga[:16], riga.split()[2].rstrip(":")
                quando.setdefault(nome, data)
    reg = ["| run | condizione | celle | quando | directory |", "|---|---|---|---|---|"]
    for nome, condizione in CONDIZIONE.items():
        base = Path(__file__).parent / "raw" / nome
        n = sum(1 for v in base.glob("*") if v.is_dir() for r in v.glob("run-*")
                if (r / "k6-summary.json").exists())
        if not n:
            continue
        data = quando.get(nome)
        if data is None and base.exists():
            from datetime import datetime
            data = datetime.fromtimestamp(base.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
        reg.append(f"| {nome} | {condizione} | {n} | {data} | `raw/{nome}/` |")
    registro = "\n".join(reg)
    print(tabella)
    print(f"\ncelle totali: {sum(1 for n in CONDIZIONE for v in (Path(__file__).parent / 'raw' / n).glob('*') if v.is_dir() for r in v.glob('run-*') if (r / 'k6-summary.json').exists())}")
    if a.doc:
        t = a.doc.read_text()
        inizio, fine = "<!-- sintesi:inizio -->", "<!-- sintesi:fine -->"
        for marcatore, contenuto in (("sintesi", tabella), ("registro", registro)):
            inizio, fine = f"<!-- {marcatore}:inizio -->", f"<!-- {marcatore}:fine -->"
            t = a.doc.read_text()
            if inizio in t and fine in t:
                testa, resto = t.split(inizio, 1); _, coda = resto.split(fine, 1)
                a.doc.write_text(f"{testa}{inizio}\n{contenuto}\n{fine}{coda}")
                print(f"{marcatore}: scritto in {a.doc}")

if __name__ == "__main__":
    main()
