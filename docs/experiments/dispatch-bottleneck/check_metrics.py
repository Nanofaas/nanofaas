#!/usr/bin/env python3
"""Say which of a run's declared queries actually answered.

A snapshot records every query it asked, so an unanswered one is not missing
from the file - it is present with an empty `points` list, or with points that
are all zero. Those two are different facts and the run cannot tell them apart
on its own: empty means nothing published the series, all-zero means it was
published and the event never happened. Both are printed, and neither is
inferred.

    python3 check_metrics.py <run-dir> [<run-dir> ...]
"""

from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path


def _load(path: Path) -> dict:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt") as handle:
        return json.load(handle)


def _snapshots(root: Path) -> list[Path]:
    found = [p for p in root.rglob("prometheus-snapshot.json*") if p.suffix in ("", ".json", ".gz")]
    return sorted(found)


def report(snapshot: Path) -> tuple[int, int, int]:
    queries = _load(snapshot)["queries"]
    empty, flat, live = [], [], []
    for name, entry in sorted(queries.items()):
        values = [p["value"] for p in entry["points"]]
        if not values:
            empty.append((name, entry))
        elif max(values) == 0.0:
            flat.append((name, entry))
        else:
            live.append((name, min(values), max(values), len(values)))

    print(f"\n=== {snapshot.parent.parent.relative_to(snapshot.parents[4])} ===")
    print(f"{len(live)} vive, {len(flat)} a zero, {len(empty)} vuote, su {len(queries)}")

    if empty:
        print("\n  VUOTE - nessuna serie pubblicata:")
        for name, entry in empty:
            mark = "  RICHIESTA" if entry.get("required") else ""
            print(f"    {name:<46}{mark}")
    if flat:
        print("\n  A ZERO - serie pubblicata, evento mai accaduto:")
        for name, _ in flat:
            print(f"    {name}")
    print("\n  VIVE:")
    for name, lo, hi, n in live:
        print(f"    {name:<46} {lo:>14.4g} .. {hi:>14.4g}  ({n} punti)")
    return len(live), len(flat), len(empty)


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    missing_required = 0
    for root in map(Path, argv):
        for snapshot in _snapshots(root):
            report(snapshot)
            queries = _load(snapshot)["queries"]
            missing_required += sum(
                1
                for entry in queries.values()
                if entry.get("required") and not entry["points"]
            )
    print(f"\nserie richieste e assenti: {missing_required}")
    return 1 if missing_required else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
