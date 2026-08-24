#!/usr/bin/env python3
"""La tabella markdown della campagna, per il documento.

Uso:  uv run --project <nanolab> python3 tables.py <dir-di-run> [--doc README.md --marker A1]

Il grosso NON e' calcolato qui. `nanolab compare` produce gia' un report HTML che
aggrega le celle per variante, porta ogni cifra con il suo spread e - la parte
che conta - si rifiuta di dichiarare un vincitore quando gli intervalli di due
build si sovrappongono. Questo script chiama quello stesso codice
(`read_cell`, `aggregate_table`) e ne rende il risultato in markdown, cosi' la
tabella del documento e quella del report non possono divergere.

Qui vive solo cio' che il report non guarda, e che a questa campagna serve:

* se il processo e' sopravvissuto alla cella (una cella morta a meta' si legge
  come una cella lenta: i contatori ripartono da zero);
* il collector, che e' l'oggetto del capitolo su footprint e GC;
* lo strozzamento della CPU, che decide se le build sono state confrontate o
  solo appoggiate tutte allo stesso muro;
* costo e dimensione della build, che esistono solo nel log e nel registry
  della VM e spariscono con il teardown.
"""
import argparse
import gzip
import json
import re
import shutil
import statistics as st
import sys
import tempfile
from pathlib import Path

from sonata_tasks.loadtest.comparison_report import (  # noqa: E402
    _fmt,
    _spread,
    aggregate_table,
    read_cell,
)


def _readable(root: Path) -> tuple[Path, tempfile.TemporaryDirectory | None]:
    """read_cell vuole snapshot non compressi; l'archivio li tiene in gzip.

    48 MB a cella diventano 2,8 compressi, che e' la differenza fra un archivio
    che sta nel repository e uno che non ci sta. Scompattare in un temporaneo
    costa qualche secondo e tiene entrambe le cose.
    """
    zipped = list(root.glob("*/run-*/metrics/prometheus-snapshot.json.gz"))
    if not zipped:
        return root, None
    tmp = tempfile.TemporaryDirectory(prefix="baseline-")
    mirror = Path(tmp.name)
    for path in root.rglob("*"):
        target = mirror / path.relative_to(root)
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif path.suffix == ".gz":
            target.parent.mkdir(parents=True, exist_ok=True)
            target.with_suffix("").write_bytes(gzip.open(path).read())
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    return mirror, tmp


def variants(root: Path) -> dict[str, str]:
    manifest = json.loads((root / "comparison-manifest.json").read_text())
    return {v["key"]: v["label"] for v in manifest["variants"]}


def cells(root: Path, labels: dict[str, str]):
    found = []
    for key in labels:
        for repetition in range(1, 21):
            cell = read_cell(root, key, repetition)
            if cell is not None:
                found.append(cell)
    return found


def _snapshot(root: Path, variant: str, repetition: int) -> dict | None:
    path = root / variant / f"run-{repetition}" / "metrics" / "prometheus-snapshot.json"
    return json.loads(path.read_text())["queries"] if path.is_file() else None


def _series(queries, name):
    entry = queries.get(name)
    return [p["value"] for p in entry["points"] if p["value"] is not None] if entry else []


def _delta(queries, name):
    values = _series(queries, name)
    return (max(values) - min(values)) if values else 0.0


def extras(root: Path, labels: dict[str, str], cells_found) -> list[dict]:
    """Le colonne che il report non ha. Una riga per variante."""
    build = build_phase(root, labels)
    rows = []
    for key, label in labels.items():
        reps = [c.repetition for c in cells_found if c.variant == key]
        if not reps:
            continue
        alive, gc_fraction, gc_pause, throttled, heap = [], [], [], [], []
        served = []
        gc_count, broken = [], []
        for repetition in reps:
            queries = _snapshot(root, key, repetition)
            if queries is None:
                continue
            dispatch = _series(queries, "function_dispatch_total")
            if len(dispatch) > 1:
                alive.append(all(b >= a for a, b in zip(dispatch, dispatch[1:])))
            # Il "Throughput (rps)" del report e' il tasso OFFERTO da k6, che a
            # ciclo aperto e' identico in tutte le celle per costruzione: la
            # differenza fra le build sta in quanto ne servono. Questa e' quella.
            window = _window(queries)
            if window:
                served.append(_delta(queries, "function_dispatch_total") / window)
            periods = _delta(queries, "container_cpu_periods@control-plane")
            if periods:
                throttled.append(
                    100 * _delta(queries, "container_cpu_throttled_periods@control-plane") / periods
                )
            # jvm_gc_pause_* viene dal binder di Micrometer, che ascolta le
            # notifiche di GC: SubstrateVM non ne emette, quindi su una build
            # nativa quella serie e' vuota. jvm_gc_collection_time e _count
            # vengono invece dal polling dell'MXBean, che risponde su entrambe
            # le VM - registrati apposta su tutte e due le build, perche' una
            # diagnostica presente in una sola configurazione non puo' servire a
            # confrontarle. Sono quelle le serie da usare qui.
            collections = _delta(queries, "jvm_gc_collection_count")
            seconds = _delta(queries, "jvm_gc_collection_time")
            if collections:
                gc_pause.append(1000 * seconds / collections)
                gc_count.append(collections)
            if seconds and window:
                gc_fraction.append(100 * seconds / window)
            # Il gauge che dovrebbe dare la stessa cosa gia' pronta, e che su
            # HotSpot non la da': va riportato quando mente, non nascosto.
            fraction = _series(queries, "jvm_gc_time_fraction")
            if fraction and all(v != v for v in fraction):
                broken.append(repetition)
            values = _series(queries, "jvm_heap_used_bytes")
            if values:
                heap.append(max(values) / 1e6)
        rows.append(
            {
                "Build": label,
                "Servite/s (dispatch)": _fmt(*_spread(served), digits=0),
                "Vivo a fine cella": "si"
                if alive and all(alive)
                else (f"NO: {alive.count(False)}/{len(alive)}" if alive else "—"),
                "CPU strozzata (%)": _fmt(*_spread(throttled), digits=1),
                "Heap picco (MB)": _fmt(*_spread(heap), digits=0),
                "Collezioni GC": _fmt(*_spread(gc_count), digits=0),
                "Pausa GC media (ms)": _fmt(*_spread(gc_pause), digits=1),
                "Tempo in GC (%)": _fmt(*_spread(gc_fraction), digits=2),
                "gauge gc_time_fraction": "NaN" if len(broken) == len(reps) else ("ok" if not broken else f"NaN in {len(broken)}/{len(reps)}"),
                "Compilazione (s)": f"{build.get(key, {}).get('build_s', float('nan')):.1f}",
                "Immagine (MB)": f"{build.get(key, {}).get('immagine_MB', float('nan')):.0f}",
            }
        )
    return rows


def _window(queries):
    from datetime import datetime

    entry = queries.get("function_dispatch_total")
    if not entry or len(entry["points"]) < 2:
        return 0.0
    first = datetime.fromisoformat(entry["points"][0]["timestamp"])
    last = datetime.fromisoformat(entry["points"][-1]["timestamp"])
    return (last - first).total_seconds()


def build_phase(root: Path, labels: dict[str, str]) -> dict[str, dict]:
    """Quanto e' costato produrre ogni build, dal log e dal registry.

    Non c'e' altrove: la fase di prepare non scrive un record proprio, e la VM
    che l'ha eseguita viene distrutta subito dopo.
    """
    out: dict[str, dict] = {}
    sizes = root / "image-sizes.json"
    if sizes.exists():
        for key, value in json.loads(sizes.read_text()).items():
            out.setdefault(key, {})["immagine_MB"] = value
    log = root / "run.log"
    if not log.exists():
        return out

    def slug(label: str) -> str:
        # Lo stesso appiattimento che il runner applica al summary per farne un
        # operation id: i separatori ripetuti collassano, cosi' "Native, -Os,
        # serial GC" diventa native-os-serial-gc e non native--os-serial-gc.
        return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", label.lower())).strip("-")

    slugs = {slug(label): key for key, label in labels.items()}
    for line in log.read_text().splitlines():
        if not line.startswith("[") or "] passed" not in line:
            continue
        step, seconds = line.split("]", 1)[0].lstrip("["), line.rsplit(None, 1)[-1]
        if not seconds.endswith("s") or not any(
            marker in step for marker in ("compile-native-image", "build-image", "build-boot-jar")
        ):
            continue
        for name, key in slugs.items():
            if step.endswith(name):
                entry = out.setdefault(key, {})
                entry["build_s"] = entry.get("build_s", 0.0) + float(seconds[:-1])
    return out


def markdown(rows) -> str:
    if not rows:
        return "_Nessuna cella con dati._"
    columns = list(rows[0])
    lines = ["| " + " | ".join(columns) + " |", "|" + "---|" * len(columns)]
    for row in rows:
        lines.append("| " + " | ".join(str(row[c]) for c in columns) + " |")
    return "\n".join(lines)


def render(root: Path) -> str:
    readable, holder = _readable(root)
    try:
        labels = variants(readable)
        found = cells(readable, labels)
        if not found:
            return "_Nessuna cella con dati._"
        aggregate = aggregate_table(found, labels).to_dict("records")
        return (
            markdown(aggregate)
            + "\n\nQuello che il report di confronto non guarda:\n\n"
            + markdown(extras(readable, labels, found))
        )
    finally:
        if holder is not None:
            holder.cleanup()


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
        print(f"\nscritta in {args.doc} fra i marcatori {args.marker}", file=sys.stderr)
    elif args.doc:
        print(f"\nmarcatori {args.marker} assenti in {args.doc}", file=sys.stderr)


if __name__ == "__main__":
    main()
