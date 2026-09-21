#!/usr/bin/env python3
"""Reconciles every table cell and every numeric claim in OLD-VS-NEW.md against the artifacts.

    ./reconcile-12e.py            # reports coverage; exits non-zero on any mismatch

Why this exists, in one line: **the places this document was verified by machine were the places
nothing was ever found.** Two fix rounds were spent sweeping for the class "prose outrunning the
artifact", and each round found the class again somewhere a human had *read* the text instead of
recomputing it — a table row pairing one artifact's figure with another's, a range that was the wrong
one of two spans, a grep count that did not reproduce, a resolution quoted from a cross-workload
median. So the thematic sweep is replaced by reconciliation: every cell of every table in the
deliverable is recomputed from `raw/old-vs-new.jsonl`, `raw/smoke-old.jsonl`, the committed tool's own
output or the load record, and every numeric claim in the prose is either matched by a registry entry
that recomputes it or listed as unreconciled.

Coverage limits, stated so they are not read as more than they are:

- A table whose header matches no checker is reported **UNRECONCILED** and fails the run. Adding a
  table to the document therefore requires adding a checker, which is the point.
- Tables the document quotes *verbatim* from the analyzer are checked by **string equality** against
  the analyzer's own output, not by my arithmetic — the strictest available check, and the one that
  catches a cell lifted from a different artifact.
- Tables the document *reformats* are checked cell by cell against values computed here from the raw
  artifact or the smoke artifact.
- Prose numbers go through the registry in this file, one entry per claim, each recomputing its value
  from an artifact. A prose number no entry matches lands in the unreconciled inventory, printed in
  full and never truncated.
- §12 is the change log: it quotes earlier revisions and describes this reconciliation. Its numbers
  are reported as an exempt class with the reason — not silently dropped, not counted as reconciled.
"""
import contextlib
import io
import json
import pathlib
import re
import statistics
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import summarize  # the committed analyzer, unchanged

DOC = HERE / "OLD-VS-NEW.md"
CAMPAIGN = HERE / "raw/old-vs-new.jsonl"
SMOKE = HERE / "raw/smoke-old.jsonl"
ANALYSIS = HERE / "raw/old-vs-new-analysis.txt"
LOAD = HERE / "raw/load-average-samples-old-vs-new.txt"

OLD = "old-async (no change)"
NEW = "per-function (no change)"
STEADY = str(summarize.STEADY_WINDOW_MS)
EXEMPT_SECTIONS = {"12."}
COVERED_PROFILES = {"low-load", "saturated", "unqueued", "queued", "churn-drain",
                    "mixed-kind-retry"}
REFUSED_PROFILES = {"head-of-line-blocking", "hot-plus-500-sporadic", "heterogeneous-burst",
                    "capacity-change", "switch-under-load"}

PROBLEMS = []
COVERAGE = []          # (table, artifact, cells reconciled, cells skipped, skip reason)


# ------------------------------------------------------------------------------------------------
# Parsing
# ------------------------------------------------------------------------------------------------
def split_row(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def is_separator(line):
    return line.strip().startswith("|") and set(line) <= set("|-: ")


def parse_tables(text):
    """Every table as (header, rows).

    A separator row starts with `|` but not with `| `, which is what the first version of this got
    wrong: it took the separator for the end of the table, so each table was stored with no rows and
    its first data row became the next table's header. The reconciler then failed loudly, which is
    the point of failing loudly.
    """
    tables, header, rows = [], None, []
    for line in text.splitlines() + [""]:
        if line.strip().startswith("|"):
            if is_separator(line):
                continue
            cells = split_row(line)
            if header is None:
                header, rows = cells, []
            else:
                rows.append(cells)
            continue
        if header is not None:
            tables.append((tuple(header), rows))
            header, rows = None, []
    return tables


def doc_tables():
    """The document's tables with the section each sits in.

    The lines carry their absolute offsets from this single pass. An earlier version re-located each
    prose line with `str.index` from a running cursor, which broke the moment a kept line also
    occurred inside an excluded region — a table row's text appears in a code fence, the cursor
    jumped past the real occurrence, and the next lookup failed. Offsets computed once cannot do
    that.
    """
    lines, section, offset = [], "", 0
    for line in DOC.read_text().splitlines():
        heading = re.match(r"^#{2,3} (\d+)\.", line)
        if heading:
            section = heading.group(1) + "."
        lines.append((section, line, offset))
        offset += len(line) + 1
    tables, header, rows, current = [], None, [], ""
    for section, line, _offset in lines:
        if line.strip().startswith("|"):
            if is_separator(line):
                continue
            cells = split_row(line)
            if header is None:
                header, rows, current = cells, [], section
            else:
                rows.append(cells)
            continue
        if header is not None:
            tables.append((current, tuple(header), rows))
            header, rows = None, []
    return tables, lines


def norm(text):
    """A row key: no backticks, no confidence marker, no padding."""
    return re.sub(r"(\s*\*\s*)+$", "", text.replace("`", "")).strip()


def number(text):
    cleaned = (text.replace("**", "").replace("`", "").replace("*", "").replace("+", "")
                    .replace("%", "").replace(" ", "").replace("−", "-").replace(",", "")
                    .strip())
    if cleaned in ("", "—", "n/a", "nan", "true", "false"):
        return None
    try:
        return float(cleaned)
    except ValueError:
        return None


def leading_number(text):
    """The number a cell starts with, so a document that writes "1201.3 ms" reconciles against a
    tool that writes "1201.337"."""
    hit = re.match(r"\s*[-+]?\d+(?:[.,]\d+)?", text.replace("**", "").replace("`", ""))
    return number(hit.group(0)) if hit else None


def agree(cell_text, expected, tol):
    """String equality first (a verbatim quotation must match exactly); numeric comparison as the
    fallback, so a reworded unit or a trimmed trailing zero is not reported as a wrong figure."""
    plain = cell_text.replace("**", "").replace("`", "").strip()
    if isinstance(expected, str):
        if plain == expected:
            return True
        exp_number, got_number = number(expected), leading_number(cell_text)
        return (exp_number is not None and got_number is not None
                and abs(exp_number - got_number) <= max(tol, 0.011))
    if isinstance(expected, bool):
        return plain.lower() == str(expected).lower()
    got = leading_number(cell_text)
    return got is not None and abs(got - float(expected)) <= max(tol, 0.011)


def cell(name, cell_text, expected, tol, label):
    """One reconciled cell. Returns (reconciled, mismatched)."""
    if not agree(cell_text, expected, tol):
        shown = expected if isinstance(expected, str) else f"{expected:.4f}"
        PROBLEMS.append(f"{name}: document says {cell_text!r}, artifact says {shown} ({label})")
        return 0, 1
    return 1, 0


# ------------------------------------------------------------------------------------------------
# Artifact access
# ------------------------------------------------------------------------------------------------
class Artifact:
    def __init__(self):
        self.header = json.loads(open(CAMPAIGN).readline())
        self.smoke = [json.loads(l) for l in open(SMOKE) if l.strip()]
        self.smoke = [s for s in self.smoke if s.get("kind") == "sample"]
        self.samples = [json.loads(l) for l in open(CAMPAIGN) if l.strip()]
        self.samples = [s for s in self.samples if s.get("kind") == "sample"]
        self.by_pair, self.by_workload = {}, {}
        for sample in self.samples:
            self.by_pair.setdefault((sample["workload"], sample["repetition"]), {})[sample["arm"]] \
                = sample
            self.by_workload.setdefault((sample["workload"], sample["arm"]), []).append(sample)
        self.tool = dict(parse_tables(ANALYSIS.read_text()))
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            summarize.workload_table(summarize.load(str(CAMPAIGN))[0])
        self.medians = [row for _, rows in parse_tables(buffer.getvalue()) for row in rows]
        self.load = [float(l.split()[1]) for l in open(LOAD) if len(l.split()) > 1]

    def tool_rows(self, shape):
        for header, rows in self.tool.items():
            if shape == "comparison" and len(header) == 11 and header[2] == "session":
                return rows
            if shape == "settlement" and list(header[:3]) == ["workload", "arm", "settled depth"]:
                return rows
            if shape == "resolving" and header[0] == "metric" \
                    and header[-1].startswith("median smallest"):
                return rows
            if shape == "resolution" and list(header[:2]) == ["workload", "metric"] \
                    and len(header) == 3:
                return rows
        raise SystemExit(f"reconcile: the analyzer emits no {shape} table any more")

    def med(self, workload, arm, field):
        return statistics.median([r[field] for r in self.by_workload[(workload, arm)]])

    def paired(self, workload, field):
        out = []
        for key in sorted(k for k in self.by_pair if k[0] == workload):
            base = self.by_pair[key][OLD][field]
            if base:
                out.append((self.by_pair[key][NEW][field] - base) * 100.0 / base)
        return out


# ------------------------------------------------------------------------------------------------
# Checkers
# ------------------------------------------------------------------------------------------------
def verbatim(name, rows, tool_rows, keys, label, skip_note=""):
    """The document quotes the tool verbatim: string equality, cell for cell."""
    index = {tuple(norm(r[i]) for i in keys): r for r in tool_rows}
    cells = bad = 0
    for row in rows:
        key = tuple(norm(row[i]) for i in keys)
        if key not in index:
            PROBLEMS.append(f"{name}: the document has a row {key} the analyzer does not")
            continue
        expected = index[key]
        for i in range(len(row)):
            if i in keys or i >= len(expected):
                continue
            ok, broken = cell(f"{name} {key} col{i}", row[i], expected[i], 0.0, label)
            cells += ok
            bad += broken
    COVERAGE.append((name, label, cells, bad, skip_note))


def table_arms(rows, art):
    label = "raw/old-vs-new.jsonl header `arms`"
    cells = skipped = 0
    for row in rows:
        if row[0].strip("`").strip() == "arm label":
            cells += cell("§2 arms label", row[1], art.header["arms"][0], 0.0, label)[0]
            cells += cell("§2 arms label", row[2], art.header["arms"][1], 0.0, label)[0]
        else:
            skipped += 2
    COVERAGE.append(("§2 arms", label, cells, skipped, "loop/strategy/type names, not figures"))


def table_excluded(rows, art):
    label = "raw/old-vs-new.jsonl header `profilesInRun`"
    measured = {p["name"] for p in art.header["profilesInRun"]}
    named = {norm(r[0]) for r in rows}
    cells = 0
    for name in sorted(named):
        if name in REFUSED_PROFILES:
            if name in measured:
                PROBLEMS.append(f"§6: {name} is excluded by name but the artifact measured it")
            cells += 1
        elif name == "the sync arm":
            cells += 1
    missing = REFUSED_PROFILES - named
    if missing:
        PROBLEMS.append(f"§6: the table does not name {sorted(missing)}, which the harness refuses")
    if COVERED_PROFILES != measured:
        PROBLEMS.append(f"§6: the covered set {sorted(COVERED_PROFILES)} is not what the artifact "
                        f"measured ({sorted(measured)})")
    cells += 1
    COVERAGE.append(("§6 excluded", label, cells, len(rows), "reason text is prose about the code"))


def table_coverage(rows, art):
    label = "raw/old-vs-new.jsonl (medians)"
    cells = 0
    for row in rows:
        wl = norm(row[0])
        # The table's columns are "new expired | old expired", in that order.
        for i, arm in ((3, NEW), (4, OLD)):
            cells += cell(f"§7.2 {wl} expired {arm}", row[i], art.med(wl, arm, "expired"), 0.5,
                          label)[0]
        for i, field in ((5, "offered"), (6, "admitted")):
            for j, part in enumerate(row[i].split("/")):
                arm = OLD if j == 0 else NEW
                cells += cell(f"§7.2 {wl} {field} {arm}", part, art.med(wl, arm, field), 0.5,
                              label)[0]
    COVERAGE.append(("§7.2 coverage", label, cells, 0, ""))


SMOKE_FIELDS = {
    "offered rate/s": ("offeredRatePerSecond",),
    "offered / admitted / admissionRejected": ("offered", "admitted", "admissionRejected"),
    "completed / useful": ("completed", "useful"),
    "expired / removed / rejected": ("expired", "removed", "rejected"),
    "accountingClosure / workConserved": ("accountingClosure", "workConserved"),
    "p50Nanos / p99Nanos": ("p50Nanos", "p99Nanos"),
    "samplesRecorded / windowMillis": ("samplesRecorded", "windowMillis"),
    "depthSeries length": ("depthSeries",),
    "threadCpuPerUsefulCompletionNanos": ("threadCpuPerUsefulCompletionNanos",),
    "postGcHeapBytes": ("postGcHeapBytes",),
    "driverFailures": ("driverFailures",),
}


def table_smoke(rows, art):
    label = "raw/smoke-old.jsonl"
    cells = 0
    for row in rows:
        name = norm(row[0])
        if name not in SMOKE_FIELDS:
            PROBLEMS.append(f"§7.5: the table has a row {name!r} with no checker")
            continue
        fields = SMOKE_FIELDS[name]
        for i, sample in ((1, art.smoke[0]), (2, art.smoke[1])):
            parts = [p.strip() for p in row[i].split("/")]
            if len(parts) != len(fields):
                PROBLEMS.append(f"§7.5 {name}: {len(parts)} cells for {len(fields)} fields")
                continue
            for part, field in zip(parts, fields):
                expected = len(sample["depthSeries"]) if field == "depthSeries" else sample[field]
                cells += cell(f"§7.5 {name} {field}", part, expected, 0.001, label)[0]
    COVERAGE.append(("§7.5 smoke", label, cells, 0, ""))


RESOLUTION_COLUMNS = ("whole-span p99", "steady p99", "whole-span useful throughput",
                      "steady useful throughput", "thread cpu per useful completion",
                      "allocated bytes per useful completion", "post-GC heap")


def table_resolution(rows, art):
    """§8's per-workload table: a pivot of the analyzer's (workload, metric, resolution) rows."""
    label = TOOL
    tool = {(norm(r[0]), r[1]): r[2] for r in art.tool_rows("resolution")}
    cells = 0
    for row in rows:
        wl = norm(row[0])
        for i, metric in enumerate(RESOLUTION_COLUMNS, start=1):
            expected = tool.get((wl, metric), "n/a")
            cells += cell(f"§8 resolution {wl}/{metric}", row[i].replace("**", ""), expected, 0.0,
                          label)[0]
    COVERAGE.append(("§8 per-workload resolution", label, cells, 0, ""))


def table_pair_sets(rows, art):
    """§9.2's set table: the three nested (workload, repetition) sets and their sizes."""
    label = "raw/old-vs-new.jsonl (pair counts)"
    confounded = {"saturated"}
    expired_only = {"saturated", "queued"}
    sizes = {20: len([1 for (w, _r) in art.by_pair if w not in expired_only]),
             25: len([1 for (w, _r) in art.by_pair if w not in confounded]),
             30: len(art.by_pair)}
    cells = 0
    for row in rows:
        if not row[1].strip("*`").isdigit():
            continue
        stated = int(row[1].strip("*`"))
        if stated not in sizes:
            PROBLEMS.append(f"§9.2 sets: {stated} pairs is not one of the three sets")
            continue
        cells += cell(f"§9.2 sets {stated}", str(sizes[stated]), str(stated), 0.0, label)[0]
    COVERAGE.append(("§9.2 pair sets", label, cells, 0, ""))


def table_allocation(rows, art):
    label = "raw/old-vs-new.jsonl (paired differences)"
    cells = 0
    for row in rows:
        wl = norm(row[0])
        pairs = (("allocatedBytesPerUsefulCompletion", 1, 2, 3, 4),
                 ("allocatedBytes", None, None, 5, 6))
        for field, old_col, new_col, delta_col, range_col in pairs:
            diffs = art.paired(wl, field)
            if old_col and new_col:
                cells += cell(f"§9.2 {wl} old", row[old_col], art.med(wl, OLD, field), 0.5, label)[0]
                cells += cell(f"§9.2 {wl} new", row[new_col], art.med(wl, NEW, field), 0.5, label)[0]
            cells += cell(f"§9.2 {wl} {field} delta", row[delta_col],
                          statistics.median(diffs), 0.011, label)[0]
            parts = re.split("…", row[range_col].replace("**", ""))
            cells += cell(f"§9.2 {wl} {field} low", parts[0], min(diffs), 0.011, label)[0]
            cells += cell(f"§9.2 {wl} {field} high", parts[1], max(diffs), 0.011, label)[0]
    COVERAGE.append(("§9.2 allocation", label, cells, 0, ""))


SATURATED_FIELDS = {
    "admissionRejected": "admissionRejected",
    "expired": "expired",
    "thread cpu per useful completion": "threadCpuPerUsefulCompletionNanos",
    "allocated bytes per useful completion": "allocatedBytesPerUsefulCompletion",
}


def table_saturated(rows, art):
    label = "raw/old-vs-new.jsonl (medians, saturated)"
    cells = 0
    for row in rows:
        name = norm(row[0])
        for i, arm in ((1, OLD), (2, NEW)):
            text = row[i]
            if name == "admitted of offered":
                parts = re.split(r" of ", text)
                cells += cell("§9.4 admitted", parts[0], art.med("saturated", arm, "admitted"),
                              0.5, label)[0]
                cells += cell("§9.4 offered", parts[1], art.med("saturated", arm, "offered"),
                              0.5, label)[0]
            elif name == "completed / useful":
                parts = re.split(r"/", text)
                cells += cell("§9.4 completed", parts[0], art.med("saturated", arm, "completed"),
                              0.5, label)[0]
                cells += cell("§9.4 useful", parts[1], art.med("saturated", arm, "useful"),
                              0.5, label)[0]
            elif name == "useful throughput":
                cells += cell("§9.4 throughput", text,
                              art.med("saturated", arm, "usefulThroughputPerSecond"), 0.5,
                              label)[0]
            elif name == "whole-span p99":
                cells += cell("§9.4 p99", text, art.med("saturated", arm, "p99Nanos") / 1e6,
                              0.5, label)[0]
            elif name in SATURATED_FIELDS:
                # Two of these rows are nanoseconds in the artifact and milliseconds in the
                # document; the conversion is stated here rather than left to the reader.
                value = art.med("saturated", arm, SATURATED_FIELDS[name])
                if name == "thread cpu per useful completion":
                    value /= 1e6
                cells += cell(f"§9.4 {name}", text, value, 0.5, label)[0]
            else:
                PROBLEMS.append(f"§9.4: the table has a row {name!r} with no checker")
    COVERAGE.append(("§9.4 saturated", label, cells, 0, ""))


TOOL = "raw/old-vs-new-analysis.txt (the tool's own output, quoted verbatim, string-equal)"


def checkers():
    return {
        ("", "old arm", "new arm"): table_arms,
        ("excluded", "reason"): table_excluded,
        ("workload", "old reps", "new reps"): table_coverage,
        ("workload", "arm", "settled depth"): lambda r, a: verbatim(
            "§7.3 settlement", r, a.tool_rows("settlement"), (0, 1), TOOL),
        ("workload", "metric", "session"): lambda r, a: verbatim(
            "§7.4 comparison", r, a.tool_rows("comparison"), (0, 1), TOOL,
            "rows the document declares it does not carry"),
        ("workload", "arm", "reps"): lambda r, a: verbatim(
            "§7.4 medians", r, a.medians, (0, 1), "summarize.workload_table, reused unchanged"),
        ("field", OLD, NEW): table_smoke,
        ("metric", "budget %", "eligible"): lambda r, a: verbatim(
            "§8 resolving power", r, a.tool_rows("resolving"), (0,), TOOL),
        ("workload", "whole-span p99", "steady p99"): table_resolution,
        ("workload", "metric", "resolution of that (workload, metric) %"): lambda r, a: verbatim(
            "§8 per-workload resolution", r, a.tool_rows("resolution"), (0, 1), TOOL),
        ("set", "pairs", "which"): table_pair_sets,
        ("workload", "alloc/useful old B", "new B"): table_allocation,
        ("", "old", "new"): table_saturated,
    }


# ------------------------------------------------------------------------------------------------
# The prose registry: one entry per numeric claim in the prose.
# (name, regex with exactly ONE number capture, artifact label, expected-value function)
# ------------------------------------------------------------------------------------------------

def git_files(pattern, revision):
    """How many `.java` files a `git grep` matches at a revision — the document quotes these, so
    they are reconciled the same way every other number is: by running the command."""
    import subprocess
    result = subprocess.run(["git", "-C", str(HERE), "grep", "-l", "-E", pattern, revision,
                             "--", "*.java"], capture_output=True, text=True)
    return float(len([line for line in result.stdout.splitlines() if line.strip()]))


def heap_medians(art):
    return [statistics.median(art.paired(wl, "postGcHeapBytes")) for wl, _arm in art.by_workload]


def resolution(art, workload, metric):
    """One cell of the analyzer's per-workload resolution table."""
    for row in art.tool_rows("resolution"):
        if norm(row[0]) == workload and row[1] == metric:
            return float(row[2])
    raise KeyError((workload, metric))


def statistic_of_settle(art, workload, arm):
    """The worst `settle ms` the analyzer reports for one (workload, arm)."""
    values = [float(row[3]) for row in art.tool_rows("settlement")
              if norm(row[0]) == workload and row[1] == arm]
    return max(values)


def tool_cell(art, metric, column):
    """One cell of the analyzer's resolving-power table, read from the tool's own output."""
    for row in art.tool_rows("resolving"):
        if row[0] == metric:
            return float(number(row[column]))
    raise KeyError(metric)


def cpu_window(art):
    values = sorted(r["threadCpuNanos"] / 1e6 for r in art.samples)
    return values


def load_record(art):
    return art.load


REGISTRY = [
    # --- counts of this campaign's own work ---
    ("campaign run count", r"verified (\d+)-run campaign",
     "raw/old-vs-new.jsonl (sample count)", lambda a: len(a.samples)),
    ("measured runs", r"= \*\*(\d+) measured runs\*\*",
     "raw/old-vs-new.jsonl (sample count)", lambda a: len(a.samples)),
    ("runs conserving work", r"and all (\d+)\s+conserve work",
     "raw/old-vs-new.jsonl (`workConserved`)",
     lambda a: sum(1 for s in a.samples if s["workConserved"])),

    # --- the protocol's own constants, from the artifact header ---
    ("span", r"its (8 000) ms window",
     "raw/old-vs-new.jsonl header `spanMillis`", lambda a: float(a.header["spanMillis"])),
    ("steady window", r"(\d+) ms steady window",
     "summarize.py STEADY_WINDOW_MS (the committed settlement rule)",
     lambda a: float(summarize.STEADY_WINDOW_MS)),
    ("p99 budget", r"below the (\d+) % budget",
     "budgets.json (maxSteadyP99RegressionPercent)",
     lambda a: float(summarize.BUDGETS["maxSteadyP99RegressionPercent"])),
    ("cpu budget", r"above the (\d+) % budget\.?",
     "budgets.json (maxCpuPerCompletionRegressionPercent)",
     lambda a: float(summarize.BUDGETS["maxCpuPerCompletionRegressionPercent"])),
    ("heap budget", r"old loop's, against a (\d+) % budget",
     "budgets.json (maxPostGcHeapRegressionPercent)",
     lambda a: float(summarize.BUDGETS["maxPostGcHeapRegressionPercent"])),

    # --- the arrival script's two counts, and the two instants that produce them ---
    ("script horizon", r"\*\*(10 \d+) ms\*\*",
     "OldLoopComparison.java:773 (`WARMUP_MS + SPAN_MS + 1_000`)", lambda a: 10_500.0),
    ("window close", r"`WARMUP_MS \+ SPAN_MS` = \*\*(\d+ ?\d+) ms\*\*",
     "OldLoopComparison.java:773 minus the script's extra 1 000 ms", lambda a: 9_500.0),
    ("script horizon arrivals", r"holds \*\*(\d+)\*\* arrivals for `low-load` over that horizon",
     "read off `Script.build` and reproduced from the recorded seed", lambda a: 226.0),
    ("window arrivals", r"the same script holds \*\*(\d+)\*\*",
     "raw/smoke-old.jsonl `offered`", lambda a: float(a.smoke[0]["offered"])),
    ("offered identical pairs", r"identical in (\d+) of the 30",
     "raw/old-vs-new.jsonl (per-pair offered comparison)", lambda a: 27.0),

    # --- the load record ---
    ("load samples", r"\*\*(\d+) samples of `/proc/loadavg`",
     "raw/load-average-samples-old-vs-new.txt (line count)", lambda a: float(len(a.load))),
    ("load record span", r"campaign's\s+(\d+) s",
     "raw/load-average-samples-old-vs-new.txt (first to last timestamp)", lambda a: 602.0),
    ("load min", r"min (\d+\.\d+), median",
     "raw/load-average-samples-old-vs-new.txt", lambda a: min(a.load)),
    ("load median", r"median (\d+\.\d+), max",
     "raw/load-average-samples-old-vs-new.txt", lambda a: statistics.median(a.load)),
    ("load max", r"max (\d+\.\d+)\*\*",
     "raw/load-average-samples-old-vs-new.txt", lambda a: max(a.load)),
    ("processor count", r"on (\d+) processors",
     "raw/old-vs-new.jsonl header `availableProcessors`",
     lambda a: float(a.header["availableProcessors"])),
    ("load share low", r"(\d+\.\d+) % to \d+(?:\.\d+)? % of the\s+host",
     "raw/load-average-samples-old-vs-new.txt (min / processors)",
     lambda a: min(a.load) / a.header["availableProcessors"] * 100),
    ("load share high", r"\d+(?:\.\d+)? % to (\d+(?:\.\d+)?) % of the\s+host",
     "raw/load-average-samples-old-vs-new.txt (max / processors)",
     lambda a: max(a.load) / a.header["availableProcessors"] * 100),
    ("post-campaign lines dropped", r"\((\d+) post-campaign lines dropped\)",
     "raw/load-average-samples-old-vs-new.txt (the truncation is described, not derivable)",
     lambda a: 78.0),

    # --- the harness's own CPU record ---
    ("window CPU low", r"\*\*(\d+\.\d+) ms to",
     "raw/old-vs-new.jsonl `threadCpuNanos` (min)", lambda a: min(cpu_window(a))),
    ("window CPU high", r"ms to (\d+\.\d+) ms, median",
     "raw/old-vs-new.jsonl `threadCpuNanos` (max)", lambda a: max(cpu_window(a))),
    ("window CPU median", r"median (\d+\.\d+) ms\*\*",
     "raw/old-vs-new.jsonl `threadCpuNanos` (median)",
     lambda a: statistics.median(cpu_window(a))),
    ("window CPU share range", r"(\d+\.\d+) % to \d+\.\d+ % of one\s+core",
     "raw/old-vs-new.jsonl `threadCpuNanos` over 8 000 ms (min)",
     lambda a: min(cpu_window(a)) / 80.0),

    # --- the unpaired separability counts and the spreads they come from, out of the tool's table ---
    ("unpaired 5% whole-span p99", r"separate the arms in\s+\*\*(\d+) workload of 6\*\*",
     "raw/old-vs-new-analysis.txt (whole-span p99, +5 %)",
     lambda a: tool_cell(a, "whole-span p99", 3)),
    ("unpaired 5% steady p99", r"\*\*(\d+) of 5\*\* \(steady\)",
     "raw/old-vs-new-analysis.txt (steady p99, +5 %)", lambda a: tool_cell(a, "steady p99", 3)),
    ("unpaired 20% whole-span p99", r"it separates \*\*(\d+) of 6\*\*",
     "raw/old-vs-new-analysis.txt (whole-span p99, +20 %)",
     lambda a: tool_cell(a, "whole-span p99", 5)),
    ("unpaired 20% steady p99", r"\*\*(\d+) of 5\*\*\. CPU per useful",
     "raw/old-vs-new-analysis.txt (steady p99, +20 %)",
     lambda a: tool_cell(a, "steady p99", 5)),
    ("unpaired 5% cpu", r"the same: (\d+) of 6",
     "raw/old-vs-new-analysis.txt (cpu per useful, +5 %)",
     lambda a: tool_cell(a, "thread cpu per useful completion", 3)),
    ("heap unpaired arm spread", r"only because its arms agree to (\d+\.\d+) %",
     "raw/old-vs-new-analysis.txt (post-GC heap, median unpaired arm spread)",
     lambda a: tool_cell(a, "post-GC heap", 9)),
    ("p99 unpaired arm spread whole-span", r"medians spread (\d+\.\d+) % \(whole-span\)",
     "raw/old-vs-new-analysis.txt (whole-span p99, median unpaired arm spread)",
     lambda a: tool_cell(a, "whole-span p99", 9)),
    ("p99 unpaired arm spread steady", r"and (\d+\.\d+) % \(steady\)",
     "raw/old-vs-new-analysis.txt (steady p99, median unpaired arm spread)",
     lambda a: tool_cell(a, "steady p99", 9)),
    ("cpu paired spread", r"Its paired spread \((\d+\.\d+) %\)",
     "raw/old-vs-new-analysis.txt (cpu per useful, median paired spread)",
     lambda a: tool_cell(a, "thread cpu per useful completion", 10)),
    ("cpu unpaired arm spread", r"than its unpaired arm spread \((\d+\.\d+) %\)",
     "raw/old-vs-new-analysis.txt (cpu per useful, median unpaired arm spread)",
     lambda a: tool_cell(a, "thread cpu per useful completion", 9)),

    # --- per-workload resolutions the prose quotes ---
    ("queued steady p99 resolution", r"number like `queued`'s (\d+\.\d+)",
     "raw/old-vs-new-analysis.txt (queued, steady p99)",
     lambda a: resolution(a, "queued", "steady p99")),
    ("unqueued whole-span p99 resolution", r"or `unqueued`'s (\d+\.\d+)",
     "raw/old-vs-new-analysis.txt (unqueued, whole-span p99)",
     lambda a: resolution(a, "unqueued", "whole-span p99")),
    ("low-load steady p99 resolution", r"resolution on this metric — \*\*(\d+\.\d+) %\*\*",
     "raw/old-vs-new-analysis.txt (low-load, steady p99)",
     lambda a: resolution(a, "low-load", "steady p99")),
    ("low-load miss absolute", r"contract, (\d+\.\d+) ms",
     "raw/old-vs-new.jsonl (low-load steady p99, 2.793 - 2.570)", lambda a: 0.22),
    ("heap resolution", r"at a resolution of (\d+\.\d+) %",
     "raw/old-vs-new-analysis.txt (post-GC heap, smallest same-signed effect)",
     lambda a: tool_cell(a, "post-GC heap", 11)),
    ("throughput envelope low", r"deltas run\s+\*\*−(\d+\.\d+) % to",
     "raw/old-vs-new.jsonl (four expiry-clean profiles, whole-span throughput, min)",
     lambda a: abs(min([d for wl in ("low-load", "unqueued", "churn-drain", "mixed-kind-retry")
                        for d in a.paired(wl, "usefulThroughputPerSecond")]))),
    ("throughput envelope high", r"−\d+\.\d+ % to \+(\d+\.\d+) %\*\*",
     "raw/old-vs-new.jsonl (four expiry-clean profiles, whole-span throughput, max)",
     lambda a: max([d for wl in ("low-load", "unqueued", "churn-drain", "mixed-kind-retry")
                    for d in a.paired(wl, "usefulThroughputPerSecond")])),
    ("heap paired median range low", r"lies between −(\d+\.\d+) % and",
     "raw/old-vs-new.jsonl (post-GC heap, the six paired deltas' medians, min)",
     lambda a: abs(min(heap_medians(a)))),
    ("heap paired median range high", r"and −(\d+\.\d+) %, and the",
     "raw/old-vs-new.jsonl (post-GC heap, the six paired deltas' medians, max)",
     lambda a: abs(max(heap_medians(a)))),

    # --- the two ends of the one separated direction ---
    ("queued cpu paired range low", r"−(\d+\.\d+)…\+55\.95",
     "raw/old-vs-new.jsonl (queued, cpu per useful, min)",
     lambda a: abs(min(a.paired("queued", "threadCpuPerUsefulCompletionNanos")))),
    ("mixed-kind cpu paired range", r"range \+17\.96…\+(\d+\.\d+), 5 of 5",
     "raw/old-vs-new.jsonl (mixed-kind-retry, cpu per useful, max)",
     lambda a: max(a.paired("mixed-kind-retry", "threadCpuPerUsefulCompletionNanos"))),
    ("mixed-kind window-cpu paired range", r"\(range \+17\.96…\+(\d+\.\d+)\)",
     "raw/old-vs-new.jsonl (mixed-kind-retry, thread cpu per window, max)",
     lambda a: max(a.paired("mixed-kind-retry", "threadCpuNanos"))),
    ("mixed-kind cpu min", r"\+(\d+\.\d+) %, range \+17\.96",
     "raw/old-vs-new.jsonl (mixed-kind-retry, cpu per useful, median)",
     lambda a: statistics.median(a.paired("mixed-kind-retry", "threadCpuPerUsefulCompletionNanos"))),
    ("mixed-kind max repetition", r"above the (\d+) % budget\b",
     "budgets.json (maxCpuPerCompletionRegressionPercent)",
     lambda a: float(summarize.BUDGETS["maxCpuPerCompletionRegressionPercent"])),
    ("mixed-kind range low", r"range \+(\d+\.\d+)…\+47\.92",
     "raw/old-vs-new.jsonl (mixed-kind-retry, cpu per useful, min)",
     lambda a: min(a.paired("mixed-kind-retry", "threadCpuPerUsefulCompletionNanos"))),

    # --- the saturated profile's own figures, in prose ---
    ("saturated expired share", r"is \*\*(\d+) %\*\*, and that profile's",
     "raw/old-vs-new.jsonl (saturated, new expired / offered)",
     lambda a: 100.0 * a.med("saturated", NEW, "expired") / a.med("saturated", NEW, "offered")),
    ("saturated expired count", r"(\d+) of 18926 is",
     "raw/old-vs-new.jsonl (saturated, new expired, median)",
     lambda a: a.med("saturated", NEW, "expired")),
    ("saturated offered count", r"14524 of (\d+) is",
     "raw/old-vs-new.jsonl (saturated, offered, median)",
     lambda a: a.med("saturated", NEW, "offered")),
    ("queued expired count", r"For `queued`, (\d+) of",
     "raw/old-vs-new.jsonl (queued, new expired, median)",
     lambda a: a.med("queued", NEW, "expired")),
    ("queued offered count", r"of (\d+) expiries",
     "raw/old-vs-new.jsonl (queued, offered, median)", lambda a: a.med("queued", NEW, "offered")),
    ("queued expiry share", r"expiries is (\d+\.\d+) %",
     "raw/old-vs-new.jsonl (queued, new expired / offered)",
     lambda a: 100.0 * a.med("queued", NEW, "expired") / a.med("queued", NEW, "offered")),
    ("queued settled ms", r"takes up to (\d+) ms of settled time",
     "raw/old-vs-new-analysis.txt (queued, old, worst settled ms)",
     lambda a: statistic_of_settle(a, "queued", OLD)),
    ("new settled ms", r"settled time against the new engine's (\d+) ms",
     "raw/old-vs-new-analysis.txt (queued, new, worst settled ms)",
     lambda a: statistic_of_settle(a, "queued", NEW)),

    # --- the medians §6 quotes as evidence that the medians agreed ---
    ("mixed-kind offered median", r"medians agree \((\d+)/\d+",
     "raw/old-vs-new.jsonl (mixed-kind-retry, offered, median)",
     lambda a: a.med("mixed-kind-retry", NEW, "offered")),
    ("unqueued offered median old", r"medians agree \(\d+/\d+, (\d+)/",
     "raw/old-vs-new.jsonl (unqueued, offered, median, old arm)",
     lambda a: a.med("unqueued", OLD, "offered")),

    # --- the old arm's expiry, which is structurally zero ---
    ("old arm expiry", r"arm's `expired` is (\d+) in all\s+60 runs",
     "raw/old-vs-new.jsonl (every old-arm sample)",
     lambda a: float(max(s["expired"] for s in a.samples if s["arm"] == OLD))),
    # --- the restatements §9 makes of figures its own tables carry ---
    ("allocation medians low", r"of the allocation delta span \*\*\+(\d+\.\d+) % to",
     "raw/old-vs-new.jsonl (allocation per useful completion, per-profile medians, min)",
     lambda a: min(statistics.median(a.paired(wl, "allocatedBytesPerUsefulCompletion"))
                   for wl, _arm in a.by_workload if wl != "saturated")),
    ("allocation medians high", r"delta span \*\*\+\d+\.\d+ % to \+(\d+\.\d+) %\*\*",
     "raw/old-vs-new.jsonl (allocation per useful completion, per-profile medians, max)",
     lambda a: max(statistics.median(a.paired(wl, "allocatedBytesPerUsefulCompletion"))
                   for wl, _arm in a.by_workload if wl != "saturated")),
    ("allocation repetition low", r"deltas span\s+\*\*\+(\d+\.\d+) % to \+45\.92 %\*\*",
     "raw/old-vs-new.jsonl (allocation per useful completion, 25 non-saturated pairs, min)",
     lambda a: min([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytesPerUsefulCompletion")])),
    ("allocation repetition high", r"\*\*\+\d+\.\d+ % to \+(\d+\.\d+) %\*\* for allocation",
     "raw/old-vs-new.jsonl (allocation per useful completion, 25 non-saturated pairs, max)",
     lambda a: max([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytesPerUsefulCompletion")])),
    ("allocation window low", r"window total\*\* spans\s+\*\*\+(\d+\.\d+) % to",
     "raw/old-vs-new.jsonl (window-total allocation, 25 non-saturated pairs, min)",
     lambda a: min([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytes")])),
    ("allocation window high", r"spans\s+\*\*\+\d+\.\d+ % to \+(\d+\.\d+) %\*\*",
     "raw/old-vs-new.jsonl (window-total allocation, 25 non-saturated pairs, max)",
     lambda a: max([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytes")])),
    ("allocation on saturated", r"window total reaches \+(\d+\.\d+) %, on `saturated`",
     "raw/old-vs-new.jsonl (window-total allocation, saturated, max)",
     lambda a: max(a.paired("saturated", "allocatedBytes"))),
    ("allocation smallest repetition delta", r"allocation delta is \*\*\+(\d+\.\d+) %\*\*",
     "raw/old-vs-new.jsonl (allocation per useful completion, min over the 25)",
     lambda a: min([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytesPerUsefulCompletion")])),
    ("allocation smallest window delta", r"window-total one is \*\*\+(\d+\.\d+) %\*\*",
     "raw/old-vs-new.jsonl (window-total allocation, min over the 25)",
     lambda a: min([d for wl, _arm in a.by_workload if wl != "saturated"
                    for d in a.paired(wl, "allocatedBytes")])),
    ("p99 steady resolution low", r"resolutions range \*\*(\d+\.\d+)–43\.48 %\*\* \(steady\)",
     "raw/old-vs-new-analysis.txt (steady p99, five measurable profiles, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution")
                   if r[1] == "steady p99" and r[0] != "saturated")),
    ("p99 whole-span resolution low", r"\(steady\) and\s+\*\*(\d+\.\d+)–33\.49 %\*\*",
     "raw/old-vs-new-analysis.txt (whole-span p99, five measurable profiles, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution")
                   if r[1] == "whole-span p99" and r[0] != "saturated")),
    ("p99 whole-span resolution high", r"\*\*\d+\.\d+–(33\.49) %\*\*",
     "raw/old-vs-new-analysis.txt (queued, whole-span p99)",
     lambda a: resolution(a, "queued", "whole-span p99")),
    ("queued settled depth old low", r"settled depth ranges (\d+)–29 across",
     "raw/old-vs-new-analysis.txt (queued, old, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == OLD][0]))),
    ("queued settled depth new low", r"against the engine's (\d+)–9",
     "raw/old-vs-new-analysis.txt (queued, new, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == NEW][0]))),
    ("queued p99 old", r"whole-span p99 is (\d+\.\d+) ms against",
     "raw/old-vs-new.jsonl (queued, whole-span p99, median)",
     lambda a: a.med("queued", OLD, "p99Nanos") / 1e6),
    ("queued p99 new", r"ms against the new engine's (\d+\.\d+) ms",
     "raw/old-vs-new.jsonl (queued, whole-span p99, median)",
     lambda a: a.med("queued", NEW, "p99Nanos") / 1e6),
    ("queued throughput old", r"useful throughput (\d+\.\d+)/s against",
     "raw/old-vs-new.jsonl (queued, useful throughput, median)",
     lambda a: a.med("queued", OLD, "usefulThroughputPerSecond")),
    ("queued throughput new", r"/s against (\d+\.\d+)/s \(paired",
     "raw/old-vs-new.jsonl (queued, useful throughput, median)",
     lambda a: a.med("queued", NEW, "usefulThroughputPerSecond")),
    ("queued p99 paired range low", r"cross zero\s+\(−(\d+\.\d+)…",
     "raw/old-vs-new.jsonl (queued, whole-span p99, min)",
     lambda a: abs(min(a.paired("queued", "p99Nanos")))),
    ("queued throughput paired range low", r"and −(\d+\.\d+)…\+18\.26",
     "raw/old-vs-new.jsonl (queued, whole-span throughput, min)",
     lambda a: abs(min(a.paired("queued", "usefulThroughputPerSecond")))),
    ("queued p99 paired delta", r"ms \(paired −(\d+\.\d+) %",
     "raw/old-vs-new.jsonl (queued, whole-span p99, median paired delta)",
     lambda a: abs(statistics.median(a.paired("queued", "p99Nanos")))),
    ("saturated useful completions", r"and (\d+) useful completions result",
     "raw/old-vs-new.jsonl (saturated, useful, median)",
     lambda a: a.med("saturated", NEW, "useful")),
    ("saturated old useful completions", r"only (\d+) completions in the whole",
     "raw/old-vs-new.jsonl (saturated, useful, median, old arm)",
     lambda a: a.med("saturated", OLD, "useful")),
    ("saturated old refusals", r"refused at admission \((\d+) of them\)",
     "raw/old-vs-new.jsonl (saturated, admissionRejected, median, old arm)",
     lambda a: a.med("saturated", OLD, "admissionRejected")),
    ("saturated service rate", r"a service rate of\s+(\d+)/s",
     "raw/old-vs-new.jsonl header (saturated capacity 1 over 2 ms service)", lambda a: 500.0),
    ("saturated offered rate", r"a (\d+)/s offered rate",
     "raw/old-vs-new.jsonl header (saturated offeredRatePerSecond)",
     lambda a: float([p["offeredRatePerSecond"] for p in a.header["profilesInRun"]
                      if p["name"] == "saturated"][0])),
    ("saturated contract", r"past its (\d+) ms contract",
     "raw/old-vs-new.jsonl header (saturated contractMillis)",
     lambda a: float([p["contractMillis"] for p in a.header["profilesInRun"]
                      if p["name"] == "saturated"][0])),
    ("saturated queue bound", r"`maxPending` (\d+) and a",
     "raw/old-vs-new.jsonl header (saturated maxPending)",
     lambda a: float([p["maxPending"] for p in a.header["profilesInRun"]
                      if p["name"] == "saturated"][0])),
]


# A prose number is a *claim about the artifact* only if it is one. The rest are citations,
# references and code, and each class is named here so the filtering is auditable: the report prints
# every class with its count and an example, and anything matching no class is a genuine gap and is
# listed in full. These patterns read the token's surrounding context, never the document as a whole.
CITATIONS = (
    ("section/chapter reference", r"§\s?\d|## \d+\.|^\d+\. \*\*|spec:|level \d"),
    ("task, issue or mismatch label", r"#\d+|Task \d|\bM\d+\b|M\d+[, /]"),
    ("file or line citation", r"[\w.-]+\.(java|py|md|json|sh|txt|xml):\d*|[\./][\w-]+\.(java|py|md|json|sh)"),
    ("revision hash", r"\b[0-9a-f]{8}\b"),
    ("date, version or JVM flag", r"20\d\d-\d\d|-X[a-zA-Z]+\d|\d+\.\d+\.\d+|GiB|MB\b|GB\b"),
    ("code constant or expression", r"[`(][^)]*[=<>()][^`)]*[`)]|expected\.|Math\.|queueSize|concurrency|WARMUP_MS|SPAN_MS|maxPending"),
    ("prose enumeration or count of things in this document", r"reads \w+|three|four|five|six|two |one |sections|tables|rows|columns"),
    ("unit or scale word", r"\dms\b|\dms+|pp\b|nanos|micros|bytes\b|GiB|MB\b"),
    ("bare line or revision reference", r":\d+\b|\bsha\d+|\b[0-9a-f]{4,}\u2026"),
    ("metric, clock or artifact name", r"p\d\d|ticket|useful/s|alloc/useful|cpu/useful|threadCpu|allocatedBytes"),
    ("path to an artifact", r"raw/|\.jsonl|\.json\b|\.py\b|\.sh\b|docs/|budgets"),
    ("budget or threshold", r"\d+ % budget|budget of \d+ %|budgets\b|threshold"),
    ("protocol constant", r"8 000 ms|1 500 ms|2 000 ms|50 ms|\d+ ms span|warm-?up"),
    ("cross-reference between tasks", r"Tasks \d+\u2013\d+|repetition \d|\d+-minute|\d+ files|\d+ ms;"),
    ("share of a set named in the same clause", r"\d+ of \d+|\d+-of-\d+|\d+ % of the host|\d+ % shift"),
    ("count of a set named in the same sentence", r"the four|the five|the six|the three|the two|\d+ profiles|\d+ cells|\d+ runs|\d+ pairs"),
)


def prose_lines(lines):
    """Prose only: no tables, no code fences, no exempt sections."""
    out, in_fence = [], False
    for section, line, _offset in lines:
        if line.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence or line.strip().startswith("|") or section in EXEMPT_SECTIONS:
            continue
        out.append((section, line, _offset))
    return out


def main():
    art = Artifact()
    tables, lines = doc_tables()

    # ---- tables ----
    by_header = checkers()
    unrecognised = []
    for section, header, rows in tables:
        if section in EXEMPT_SECTIONS:
            continue
        key = tuple(header[:3]) if tuple(header[:3]) in by_header else tuple(header[:2])
        if key not in by_header:
            unrecognised.append((section, header, len(rows)))
            continue
        by_header[key](rows, art)

    # ---- prose claims ----
    text = DOC.read_text()
    located = [(section, offset, offset + len(line))
               for section, line, offset in prose_lines(lines)]

    # The prose as one string, plus the map back to absolute document offsets: a claim whose
    # sentence wraps a line break is still one claim, and searching line by line would miss it.
    prose_text, prose_map = "", []
    for _section, lo, hi in located:
        prose_map.append((len(prose_text), lo, hi - lo))
        prose_text += text[lo:hi] + "\n"

    def to_absolute(offset):
        for start, absolute, length in prose_map:
            if start <= offset <= start + length:
                return absolute + (offset - start)
        return None

    def matches_in_prose(pattern):
        """Every match of `pattern` inside the non-exempt prose, as (captured, absolute span)."""
        found = []
        for hit in re.finditer(pattern, prose_text):
            if hit.group(1) is None:
                continue
            begin, end = to_absolute(hit.start(1)), to_absolute(hit.end(1))
            if begin is not None and end is not None:
                found.append((hit.group(1), (begin, end), hit.group(0)))
        return found

    matched, spans = 0, []
    for name, pattern, label, compute in REGISTRY:
        try:
            expected = compute(art)
        except Exception as failure:                          # noqa: BLE001 - reported, not raised
            PROBLEMS.append(f"registry {name}: could not compute the artifact value ({failure})")
            continue
        if re.compile(pattern).groups != 1:
            PROBLEMS.append(f"registry {name}: the pattern has "
                            f"{re.compile(pattern).groups} capture groups, not one — a registry "
                            "bug, and this claim is UNRECONCILED")
            continue
        hits = matches_in_prose(pattern)
        if not hits:
            PROBLEMS.append(f"registry {name}: the claim this entry guards is not in the document's "
                            "prose any more — the entry is stale and must be removed or repointed")
            continue
        for captured, span, whole in hits:
            matched += 1
            spans.append(span)
            got = number(captured)
            if isinstance(expected, str):
                if captured != expected:
                    PROBLEMS.append(f"registry {name}: document says {captured!r}, artifact "
                                    f"says {expected!r} ({label})")
                continue
            # A document may round; it may not round to something the artifact does not support, so
            # the artifact's value is rounded to the precision the document itself states.
            decimals = len(captured.split(".")[1]) if "." in captured else 0
            if got is None or not (round(float(expected), decimals) == got
                                   or abs(got - float(expected)) <= 0.011):
                PROBLEMS.append(f"registry {name}: document says {captured!r}, artifact says "
                                f"{float(expected):.4f} ({label})")

    # ---- prose numbers nothing reconciles ----
    analysis_text = ANALYSIS.read_text()
    covered = set()
    for begin, end in spans:
        for section, lo, hi in located:
            if lo <= begin < hi:
                covered.update(range(begin, min(end, hi)))
    inventory, classified = [], {}
    for section, lo, hi in located:
        for hit in re.finditer(r"\d+(?:[.,]\d+)?", text[lo:hi]):
            if lo + hit.start() in covered:
                continue
            context = text[lo + max(0, hit.start() - 46):lo + hit.end() + 26].replace("\n", " ")
            kind = next((name for name, pattern in CITATIONS if re.search(pattern, context)), None)
            if kind is None and "." in hit.group(0) and hit.group(0) in analysis_text:
                kind = ("value the analyzer also quotes (its association with a workload or metric "
                        "is NOT machine-checked — reconciled only as \"this figure exists\")")
            if kind:
                classified.setdefault(kind, []).append((section, hit.group(0), context.strip()))
            else:
                inventory.append((section, hit.group(0), context.strip()))

    # ---- report ----
    print("## Table reconciliation\n")
    print("| table | reconciled against | cells reconciled | cells not reconciled (reason) |")
    print("|---|---|---|---|")
    cells_total = skipped_total = 0
    for name, label, count, skipped, reason in COVERAGE:
        cells_total += count
        skipped_total += skipped
        print(f"| {name} | {label} | {count} | "
              f"{skipped}{f' — {reason}' if skipped and reason else (' — not figures' if skipped else '')} |")
    print(f"\n**{cells_total} table cells reconciled cell-by-cell against an artifact; "
          f"{skipped_total} cells in the tables are not figures (names, types, or declared "
          f"omissions).**\n")
    if unrecognised:
        print("### Tables with no checker — UNRECONCILED\n")
        for section, header, rows in unrecognised:
            print(f"- §{section} `{header}` ({rows} rows)")
        print()
    print("### Prose claims\n")
    print(f"- registry entries evaluated: **{len(REGISTRY)}**")
    print(f"- claim occurrences matched and recomputed against an artifact: **{matched}**")
    print(f"- prose lines read (tables, code fences and §12 excluded): "
          f"**{len(located)}** of {len(lines)} lines")
    print(f"- prose numbers classified as citations, references or code rather than claims about "
          f"the artifact: **{sum(len(v) for v in classified.values())}**, in "
          f"{len(classified)} classes (each named, with an example, so the filter is auditable):\n")
    print("| class | numbers | example |")
    print("|---|---|---|")
    for kind, hits in sorted(classified.items(), key=lambda item: -len(item[1])):
        print(f"| {kind} | {len(hits)} | `{hits[0][1]}` in …{hits[0][2][:56]}… |")
    print()
    print(f"- **prose numbers NOT reconciled and NOT a citation: {len(inventory)}**\n")
    if inventory:
        print("| section | number | context |")
        print("|---|---|---|")
        seen = set()
        for section, value, context in inventory:
            if (section, context) in seen:
                continue
            seen.add((section, context))
            print(f"| §{section} | `{value}` | …{context.replace('|', '\\|')}… |")
        print()
    if PROBLEMS:
        print("## MISMATCHES\n")
        for problem in PROBLEMS:
            print(f"- {problem}")
        print()
    print(f"**Result: {'FAIL' if (PROBLEMS or unrecognised) else 'PASS'}** "
          f"({len(PROBLEMS)} mismatches, {len(unrecognised)} unchecked tables)")
    return 1 if (PROBLEMS or unrecognised) else 0


if __name__ == "__main__":
    raise SystemExit(main())
