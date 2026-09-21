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
  They are not unchecked either: `INSTRUMENT_REGISTRY` recomputes every count in it, this section
  included.
- Every comparison carries a flat absolute tolerance, `TOLERANCE`, because the document rounds. It is
  a real weakening — a two-decimal figure is accepted within that slack — and it is stated in the
  document for that reason rather than left for a reader to discover.
- This script resolves `git` from its own location and refuses to run outside a checkout of the
  repository; `--perturbations` asserts the unperturbed run passes before applying anything.
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

# The flat absolute slack every comparison here carries. It exists because the document rounds —
# a two-decimal percentage cannot be matched exactly — and it is *stated in the document*, because a
# reader cannot otherwise know how much slack a check here has. One named constant rather than a
# literal at each comparison, so the document's statement of it and the checks cannot drift apart.
TOLERANCE = 0.011


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


DOC_TEXT = None          # set by the perturbation self-test; None means read the file


def doc_text():
    return DOC_TEXT if DOC_TEXT is not None else DOC.read_text()


def doc_tables():
    """The document's tables with the section each sits in.

    The lines carry their absolute offsets from this single pass. An earlier version re-located each
    prose line with `str.index` from a running cursor, which broke the moment a kept line also
    occurred inside an excluded region — a table row's text appears in a code fence, the cursor
    jumped past the real occurrence, and the next lookup failed. Offsets computed once cannot do
    that.
    """
    lines, section, offset = [], "", 0
    for line in doc_text().splitlines():
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


WORD_VALUES = {word: value for value, word in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve".split())}
WORD_PATTERN = r"\b(" + "|".join(WORD_VALUES) + r")\b"


def word_number(captured):
    """A capture may be a word or a digit: the word pass also carries entries whose claim is a count
    the digit inventory would have caught, kept here so that rewording one does not lose its guard."""
    low = captured.lower()
    return float(WORD_VALUES[low]) if low in WORD_VALUES else float(captured)


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
                and abs(exp_number - got_number) <= max(tol, TOLERANCE))
    if isinstance(expected, bool):
        return plain.lower() == str(expected).lower()
    got = leading_number(cell_text)
    return got is not None and abs(got - float(expected)) <= max(tol, TOLERANCE)


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
                          statistics.median(diffs), TOLERANCE, label)[0]
            parts = re.split("…", row[range_col].replace("**", ""))
            cells += cell(f"§9.2 {wl} {field} low", parts[0], min(diffs), TOLERANCE, label)[0]
            cells += cell(f"§9.2 {wl} {field} high", parts[1], max(diffs), TOLERANCE, label)[0]
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
    # From the repository root: run from this directory and the pathspec matches the two harness
    # files beside the document, which is how the first version of this silently returned 2.
    root = HERE.parents[2]
    result = subprocess.run(["git", "-C", str(root), "grep", "-l", "-E", pattern, revision,
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
    # --- the greps §5.7 quotes as its evidence: reconciled by running the command ---
    ("EngineReadiness at OLD", r"05f49dcb` matches\s+\*\*(\d+) files\*\*",
     "git grep -l -E EngineReadiness 05f49dcb -- '*.java'",
     lambda a: git_files("EngineReadiness", "05f49dcb")),
    ("EngineReadiness at HEAD", r"and at HEAD \*\*(\d+)\*\*",
     "git grep -l -E EngineReadiness HEAD -- '*.java'",
     lambda a: git_files("EngineReadiness", "HEAD")),
    ("notReady at OLD", r"`notReady` matches (\d+) at OLD",
     "git grep -l -E notReady 05f49dcb -- '*.java'",
     lambda a: git_files("notReady", "05f49dcb")),
    ("readiness alternation at OLD", r"matches in \*\*(\d+)\*\* `\.java`",
     "git grep -l -E 'readiness|Readiness' 05f49dcb -- '*.java'",
     lambda a: git_files("readiness|Readiness", "05f49dcb")),
    ("readiness one term at OLD", r"\((\d+) for either term alone",
     "git grep -l -E 'readiness' 05f49dcb -- '*.java'",
     lambda a: git_files("readiness", "05f49dcb")),
    ("readiness union at OLD", r"(\d+) for the union with `isReady`",
     "git grep -l -E 'readiness|Readiness|isReady' 05f49dcb -- '*.java'",
     lambda a: git_files("readiness|Readiness|isReady", "05f49dcb")),

    # --- figures this round introduced, reconciled the same way ---
    ("queued settled depth old low", r"settled depth ranges (\d+)\u201329",
     "raw/old-vs-new-analysis.txt (queued, old, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == OLD][0]))),
    ("queued settled depth new low", r"engine's (\d+)\u20139, so this",
     "raw/old-vs-new-analysis.txt (queued, new, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == NEW][0]))),
    ("mixed-kind offered median half", r"medians agree \((\d+)/\d+",
     "raw/old-vs-new.jsonl (mixed-kind-retry, offered, median)",
     lambda a: a.med("mixed-kind-retry", NEW, "offered")),
    ("unqueued offered median half", r"medians agree \(\d+/\d+, (\d+)/",
     "raw/old-vs-new.jsonl (unqueued, offered, median, old arm)",
     lambda a: a.med("unqueued", OLD, "offered")),
    ("profile count in a clause", r"workload of (6)\*\*",
     "raw/old-vs-new.jsonl (profiles whose whole-span p99 is measurable)",
     lambda a: profiles_measured(a, "whole-span p99")),
    ("p99 budget in a clause", r"no (\d+) % effect",
     "budgets.json (maxSteadyP99RegressionPercent)",
     lambda a: float(summarize.BUDGETS["maxSteadyP99RegressionPercent"])),
    ("cpu per completion old", r"(0\.76) ms per completion on the old arm",
     "raw/old-vs-new.jsonl (low-load, thread cpu per useful completion, median, old arm)",
     lambda a: a.med("low-load", OLD, "threadCpuPerUsefulCompletionNanos") / 1e6),
    ("queued settled depth new high", r"engine's \d+\u2013(\d+), so this",
     "raw/old-vs-new-analysis.txt (queued, new, settled depth, the range's upper end)",
     lambda a: settled_depth(a, "queued", NEW)),
    ("mixed-kind offered median first", r"medians agree \((\d+)/",
     "raw/old-vs-new.jsonl (mixed-kind-retry, offered, median)",
     lambda a: a.med("mixed-kind-retry", NEW, "offered")),
    ("unqueued offered median second", r"2833/(\d+)\)",
     "raw/old-vs-new.jsonl (unqueued, offered, median, new arm)",
     lambda a: a.med("unqueued", NEW, "offered")),
    ("offered median mixed-kind old", r"medians agree \((\d+)/\d+, \d+/\d+\)",
     "raw/old-vs-new.jsonl (mixed-kind-retry, offered, median, old arm)",
     lambda a: a.med("mixed-kind-retry", OLD, "offered")),
    ("offered median mixed-kind new", r"medians agree \(\d+/(\d+), \d+/\d+\)",
     "raw/old-vs-new.jsonl (mixed-kind-retry, offered, median, new arm)",
     lambda a: a.med("mixed-kind-retry", NEW, "offered")),
    ("offered median unqueued old", r", (\d+)/\d+\) and no conclusion",
     "raw/old-vs-new.jsonl (unqueued, offered, median, old arm)",
     lambda a: a.med("unqueued", OLD, "offered")),
    ("offered median unqueued new", r"\d+/(\d+)\) and no conclusion",
     "raw/old-vs-new.jsonl (unqueued, offered, median, new arm)",
     lambda a: a.med("unqueued", NEW, "offered")),
    ("settled depth old upper", r"ranges \d+\u2013(\d+) across",
     "raw/old-vs-new-analysis.txt (queued, old, settled depth, upper end)",
     lambda a: settled_depth(a, "queued", OLD)),
    ("cpu per completion new", r"against (0\.77) ms on the new one",
     "raw/old-vs-new.jsonl (low-load, thread cpu per useful completion, median, new arm)",
     lambda a: a.med("low-load", NEW, "threadCpuPerUsefulCompletionNanos") / 1e6),
    ("saturated useful ratio", r"by a factor of (\d+)",
     "raw/old-vs-new.jsonl (saturated useful medians, new / old)",
     lambda a: a.med("saturated", NEW, "useful") / a.med("saturated", OLD, "useful")),
    ("saturated new useful", r"against (3 655)",
     "raw/old-vs-new.jsonl (saturated, useful, median, new arm)",
     lambda a: a.med("saturated", NEW, "useful")),
    ("saturated old useful", r"factor of \d+ \((\d+)\s+useful completions",
     "raw/old-vs-new.jsonl (saturated, useful, median, old arm)",
     lambda a: a.med("saturated", OLD, "useful")),
    ("contract deadline", r"against a (100) ms contract",
     "raw/old-vs-new.jsonl header (low-load contractMillis)",
     lambda a: float([p["contractMillis"] for p in a.header["profilesInRun"]
                      if p["name"] == "low-load"][0])),
    ("offered rate low", r"from \*\*(\d+)/s \(`low-load`",
     "raw/old-vs-new.jsonl header (low-load offeredRatePerSecond)",
     lambda a: float([p["offeredRatePerSecond"] for p in a.header["profilesInRun"]
                      if p["name"] == "low-load"][0])),
    ("offered rate high", r"to (1 \d+)/s \(`churn-drain`",
     "raw/old-vs-new.jsonl header (churn-drain offeredRatePerSecond)",
     lambda a: float([p["offeredRatePerSecond"] for p in a.header["profilesInRun"]
                      if p["name"] == "churn-drain"][0])),
    ("throughput median replaced 1", r"\((\d+\.\d+)/s and 1 188\.4/s",
     "raw/old-vs-new.jsonl (low-load, useful throughput, median)",
     lambda a: a.med("low-load", OLD, "usefulThroughputPerSecond")),
    ("throughput median replaced 2", r"/s and (1 188\.4)/s were",
     "raw/old-vs-new.jsonl (mixed-kind-retry, useful throughput, median)",
     lambda a: a.med("mixed-kind-retry", NEW, "usefulThroughputPerSecond")),
    ("settled depth old", r"ranges (6)–29 across",
     "raw/old-vs-new-analysis.txt (queued, old, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == OLD][0]))),
    ("settled depth new", r"engine's (4)–9",
     "raw/old-vs-new-analysis.txt (queued, new, settled depth, min)",
     lambda a: min(float(x) for x in re.findall(r"\d+",
                   [r[2] for r in a.tool_rows("settlement")
                    if norm(r[0]) == "queued" and r[1] == NEW][0]))),
    ("unpaired 4 of 6", r"separates \*\*(\d+) of 6\*\* and",
     "raw/old-vs-new-analysis.txt (whole-span p99, +20 %)",
     lambda a: tool_cell(a, "whole-span p99", 5)),
    ("unpaired 2 of 5", r"and\s+\*\*(\d+) of 5\*\*\. CPU",
     "raw/old-vs-new-analysis.txt (steady p99, +20 %)",
     lambda a: tool_cell(a, "steady p99", 5)),
    ("expiry-clean pairs", r"the (20) expiry-clean ones",
     "raw/old-vs-new.jsonl (the pairs in which neither arm expired anything)",
     lambda a: float(pair_set_sizes(a)[0])),
    ("all measured pairs", r"all-(30) window total",
     "raw/old-vs-new.jsonl (30 measured pairs)", lambda a: float(len(a.by_pair))),

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
     "raw/old-vs-new.jsonl header (`spanMillis + warmupMillis + 1 000`, per OldLoopComparison:773)",
     lambda a: float(a.header["spanMillis"] + a.header["warmupMillis"] + 1_000)),
    ("window close", r"`WARMUP_MS \+ SPAN_MS` = \*\*(\d+ ?\d+) ms\*\*",
     "raw/old-vs-new.jsonl header (`spanMillis + warmupMillis`)",
     lambda a: float(a.header["spanMillis"] + a.header["warmupMillis"])),
    ("script horizon arrivals", r"holds \*\*(\d+)\*\* arrivals for `low-load` over that horizon",
     "read off `Script.build` and reproduced from the recorded seed at the 10 500 ms horizon",
     lambda a: horizon_arrivals(a, 10_500)),
    ("window arrivals", r"the same script holds \*\*(\d+)\*\*",
     "raw/smoke-old.jsonl `offered`", lambda a: float(a.smoke[0]["offered"])),
    ("offered identical pairs", r"identical in (\d+) of the 30",
     "raw/old-vs-new.jsonl (per-pair offered comparison)",
     lambda a: 30.0 - differing_offered_pairs(a)),

    # --- the load record ---
    ("load samples", r"\*\*(\d+) samples of `/proc/loadavg`",
     "raw/load-average-samples-old-vs-new.txt (line count)", lambda a: float(len(a.load))),
    ("load record span", r"campaign's\s+(\d+) s",
     "raw/load-average-samples-old-vs-new.txt (first to last timestamp)",
     lambda a: float(_load_span(a))),
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
     "a DECLARED LITERAL: the truncation is described in §7.6, not derivable from the artifact",
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
    ("low-load steady p99 miss", r"budget MISS: \+(\d+\.\d+) %",
     "raw/old-vs-new.jsonl (low-load, steady p99, median paired delta)",
     lambda a: statistics.median([(r["trailing"][STEADY]["p99Nanos"] - o["trailing"][STEADY]["p99Nanos"])
                                  * 100.0 / o["trailing"][STEADY]["p99Nanos"]
                                  for (o, r) in zip(sorted(a.by_workload[("low-load", OLD)],
                                                           key=lambda x: x["repetition"]),
                                                    sorted(a.by_workload[("low-load", NEW)],
                                                           key=lambda x: x["repetition"]))])),
    ("low-load miss absolute", r"contract, (\d+\.\d+) ms",
     "raw/old-vs-new.jsonl (low-load steady p99 medians, the two arms' difference)",
     lambda a: (a.med("low-load", NEW, "trailing", "window") if False else abs(
         statistics.median([r["trailing"][STEADY]["p99Nanos"] for r in a.by_workload[("low-load", NEW)]])
         - statistics.median([r["trailing"][STEADY]["p99Nanos"]
                              for r in a.by_workload[("low-load", OLD)]])) / 1e6)),
    # Pointed at the column the sentence actually quotes. It used to read column 11 — the smallest
    # *same-signed* effect, which is 0.00 for post-GC heap — while §9.1 said "a resolution of
    # 0.01 %", and it passed only because the flat tolerance swallows 0.01 against 0.00. That is the
    # tolerance doing a check's work, which is why the tolerance is stated in the document now.
    ("heap resolution", r"at a resolution of (\d+\.\d+) %",
     "raw/old-vs-new-analysis.txt (post-GC heap, median paired spread)",
     lambda a: tool_cell(a, "post-GC heap", 10)),
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
    # Re-anchored on the range it is a claim about. It used to hang off the words that followed the
    # figure ("%, and the"), so rewording the sentence after the number made the entry stale — which
    # fails the run, as designed, but for a reason that has nothing to do with the figure.
    ("heap paired median range high", r"lies between −\d+\.\d+ % and −(\d+\.\d+) %",
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
    # The two entries below read the *whole* set the sentence names, `saturated` included. The
    # earlier versions excluded it while their labels said "five measurable profiles", so they
    # encoded the same exclusion as the sentence they guarded and could never catch it — the range
    # the tool prints for those five profiles is 0.00–43.48 %, and 3.06 is the minimum over the
    # other four. Both numbers are stated in the document and each has its own entry now.
    ("p99 steady resolution low", r"\*\*(\d+\.\d+)–43\.48 % over the five profiles",
     "raw/old-vs-new-analysis.txt (steady p99, all five measurable profiles, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution") if r[1] == "steady p99")),
    ("p99 steady resolution without saturated", r"the steady range is (\d+\.\d+)–43\.48",
     "raw/old-vs-new-analysis.txt (steady p99, the four profiles excluding `saturated`, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution")
                   if r[1] == "steady p99" and r[0] != "saturated")),
    ("p99 whole-span resolution low", r"\*\*(\d+\.\d+)–33\.49 % over all six",
     "raw/old-vs-new-analysis.txt (whole-span p99, the five profiles excluding `saturated`, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution")
                   if r[1] == "whole-span p99" and r[0] != "saturated")),
    ("p99 whole-span resolution without the separated pair",
     r"excluding those\s+the\s+whole-span\s+range is (\d+\.\d+)–33\.49",
     "raw/old-vs-new-analysis.txt (whole-span p99, excluding the two profiles whose arms are "
     "already separated, min)",
     lambda a: min(float(r[2]) for r in a.tool_rows("resolution")
                   if r[1] == "whole-span p99" and r[0] not in ("saturated", "mixed-kind-retry"))),
    ("p99 whole-span resolution high", r"\*\*0\.00–(33\.49) % over all six",
     "raw/old-vs-new-analysis.txt (queued, whole-span p99)",
     lambda a: resolution(a, "queued", "whole-span p99")),
    ("whole-span p99 zero contributors", r"whole-span p99 \*\*(\d+)\*\*,\s*steady p99",
     "raw/old-vs-new-analysis.txt (whole-span p99, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "whole-span p99")),
    ("steady p99 zero contributors", r"steady p99 \*\*(\d+)\*\*, whole-span throughput",
     "raw/old-vs-new-analysis.txt (steady p99, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "steady p99")),
    ("whole-span throughput zero contributors", r"whole-span throughput \*\*(\d+)\*\*, steady throughput",
     "raw/old-vs-new-analysis.txt (whole-span throughput, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "whole-span useful throughput")),
    ("steady throughput zero contributors", r"steady throughput \*\*(\d+)\*\*, thread CPU",
     "raw/old-vs-new-analysis.txt (steady throughput, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "steady useful throughput")),
    ("cpu zero contributors", r"thread CPU per useful\s+completion \*\*(\d+)\*\*",
     "raw/old-vs-new-analysis.txt (thread cpu per useful, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "thread cpu per useful completion")),
    ("heap zero contributors", r"post-GC heap \*\*(\d+)\*\*, allocated bytes",
     "raw/old-vs-new-analysis.txt (post-GC heap, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "post-GC heap")),
    ("allocation zero contributors", r"allocated bytes per useful completion \*\*(\d+)\*\*",
     "raw/old-vs-new-analysis.txt (allocated bytes, profiles at 0.00)",
     lambda a: metric_zero_profiles(a, "allocated bytes per useful completion")),
    ("metrics where five or six sit at 0.00", r"the (two|three|\d+)\s+metrics where five or six of the six",
     "raw/old-vs-new-analysis.txt (metrics with five or six profiles at 0.00)",
     lambda a: float(len([m for m in {r[1] for r in a.tool_rows("resolution")}
                          if metric_zero_profiles(a, m) >= 5]))),
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
     "a DECLARED LITERAL: the service duration lives in SchedulerSwitchBenchmark.Profile, not in "
     "the artifact", lambda a: 500.0),
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
    ("section/chapter reference", r"§\s?\d|## \d+\.|^\d+\. \*\*|spec:|level-?\s*\d"),
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
    ("a value the table beneath carries, restated in the clause",
     r"contributes 0 by construction|contribute 0 by construction"),
    ("the change log describing its own edits",
     r"out-of-order duplicate|post-campaign|truncated|the earlier|an earlier (revision|draft)|"
     r"withdrawn|restored|mistook|reword"),
    ("numeral used as an article, a pronoun or an ordinary noun rather than a count",
     r"sides of zero|cross zero|zero denominator|zero sample-cap|a zero from|zero-capacity|It is zero everywhere|"
     r"zero-capacity|one-claim|one dispatch path|one shared driver|reached this one|"
     r"a full one|\*\*one\*\*|\*\*One\*\*|\bzero\b(?=\s+(driver|sample|denominator))|"
     r"zero-capacity|one-claim"),
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


# The entries whose value cannot be recomputed from an artifact. Declared here so that §12's count of
# them is computed from this set rather than written into the document, and so that a reader can see
# exactly which claims stand as literals.
LITERAL_ENTRIES = {"post-campaign lines dropped", "saturated service rate"}


def _load_span(art):
    """The load record's own span in seconds, from its first and last timestamps."""
    import datetime
    lines = [l for l in open(LOAD) if len(l.split()) > 1]
    first = datetime.datetime.fromisoformat(lines[0].split()[0].replace("Z", "+00:00"))
    last = datetime.datetime.fromisoformat(lines[-1].split()[0].replace("Z", "+00:00"))
    return int((last - first).total_seconds())


METRIC_WORDS = (("throughput", "useful throughput"), ("p99", "p99"), ("cpu", "cpu per useful"),
                ("heap", "heap"), ("alloc", "allocated bytes"), ("p50", "p50"), ("p95", "p95"))


def subject_row(art, context):
    """The (workload, metric) a sentence is talking about, if it can be told from its own words.

    This exists to close the hole the reviewer demonstrated: a value that exists *somewhere* in the
    analyzer's output passed, even when swapped from another row — `queued`'s throughput median
    replaced by `queued`'s CPU median. With the subject identified, the value has to appear in that
    subject's own row, which is what a mis-association cannot do.
    """
    for workload in COVERED_PROFILES:
        if workload in context:
            for word, metric in METRIC_WORDS:
                if word in context:
                    return workload, metric
    return None


def value_in_row(art, workload, metric_word, value):
    """Is `value` one of the analyzer's cells for this workload and metric?"""
    for row in art.tool_rows("comparison"):
        if norm(row[0]) != workload:
            continue
        if metric_word not in row[1]:
            continue
        for c in row[3:]:
            got = number(c)
            if got is not None and abs(got - value) <= TOLERANCE:
                return True
    return False


def settled_depth(art, workload, arm):
    """The second number of the analyzer's `settled depth` cell for one (workload, arm), worst rep."""
    values = []
    for row in art.tool_rows("settlement"):
        if norm(row[0]) == workload and row[1] == arm:
            numbers = [float(x) for x in re.findall(r"\d+", row[2])]
            values.append(max(numbers))
    return max(values)


def metric_zero_profiles(art, metric):
    """How many profiles the analyzer's resolution table puts at 0.00 for one metric."""
    return float(len([r for r in art.tool_rows("resolution")
                      if r[1] == metric and r[2] not in ("n/a",) and float(r[2]) == 0.0]))


def profiles_measured(art, metric):
    return float(len([r for r in art.tool_rows("resolution") if r[1] == metric]))


def differing_offered_pairs(art):
    n = 0
    for key, pair in art.by_pair.items():
        if OLD in pair and NEW in pair and pair[NEW]["offered"] != pair[OLD]["offered"]:
            n += 1
    return float(n)


def offered_difference_size(art):
    """The ticket count the differing pairs differ by — the largest one, so a pair differing by two
    cannot pass behind one differing by one."""
    return float(max(abs(pair[NEW]["offered"] - pair[OLD]["offered"])
                     for pair in art.by_pair.values()
                     if OLD in pair and NEW in pair
                     and pair[NEW]["offered"] != pair[OLD]["offered"]))


def pair_set_sizes(art):
    """The three nested pair sets §9.2 names, counted from the artifact: the pairs in which neither
    arm expired anything (what the document calls expiry-clean), those not on `saturated`, and all
    of them. The first is a property of the artifact, not of the profile names, because "expired
    nothing" is what the sample records."""
    return (sum(1 for pair in art.by_pair.values()
                if OLD in pair and NEW in pair
                and pair[OLD]["expired"] == 0 and pair[NEW]["expired"] == 0),
            sum(1 for (workload, _repetition) in art.by_pair if workload != "saturated"),
            len(art.by_pair))


def horizon_arrivals(art, upto_ms):
    """Replay `Script.build` for one profile: the LCG, the inter-arrival draw order and the
    horizon. This is what makes the 226/208 claim recomputed rather than asserted."""
    profile = [p for p in art.header["profilesInRun"] if p["name"] == "low-load"][0]
    state = (art.smoke[0]["seed"] ^ 0x5DEECE66D) & ((1 << 48) - 1)
    rate = profile["offeredRatePerSecond"]

    def next_bits(bits):
        nonlocal state
        state = (state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return state >> (48 - bits)

    def next_double():
        return ((next_bits(26) << 27) + next_bits(27)) / float(1 << 53)

    count, elapsed = 0, 0.0
    while elapsed < upto_ms * 1_000_000.0:
        next_bits(31)          # chooseFunction: nextInt(hotFunctions)
        next_double()          # the SYNC/ASYNC draw
        count += 1
        elapsed += -__import__("math").log(1.0 - next_double()) * 1e9 / rate
    return float(count)


WORD_REGISTRY = [
    ("offered slightly different pairs", r"the (three|two|four) that differ differ by exactly one",
     "raw/old-vs-new.jsonl (per-pair offered comparison)",
     lambda a: differing_offered_pairs(a)),
    ("offered difference size", r"differ by exactly (one|two) ticket",
     "raw/old-vs-new.jsonl (the largest per-pair offered difference)",
     lambda a: offered_difference_size(a)),
    ("nested sets", r"(three|two) nested sets",
     "raw/old-vs-new.jsonl (the distinct sizes of the three nested pair sets)",
     lambda a: float(len(set(pair_set_sizes(a))))),
    ("script horizon arrivals", r"it\s+holds \*\*(226)\*\* arrivals",
     "replayed from `Script.build` with the recorded seed at a 10 500 ms horizon",
     lambda a: horizon_arrivals(a, 10_500)),
    ("window arrivals", r"the same script holds \*\*(208)\*\*",
     "replayed from `Script.build` with the recorded seed at a 9 500 ms horizon",
     lambda a: horizon_arrivals(a, 9_500)),
]


INSTRUMENT_REGISTRY = [
    # Each pattern has exactly one capture group and is matched against the whole document, §12
    # included, because §12's numbers are claims about this run.
    ("perturbation count", r"all (\d+) perturbations caught",
     "this run's own perturbation list", lambda c: c["perturbations"]),
    # The same count, in the two places this document states it in words. Only the digit form above
    # was guarded, so changing the word to `nine` or to `eleven` both passed — the A1/A3 class,
    # reintroduced by the round that fixed it, which is why the word forms are entries now too.
    ("perturbation count in words", r"applies (\w+) changes a careless edit",
     "this run's own perturbation list", lambda c: c["perturbations"]),
    ("perturbation count as caughts", r"because (\w+) `caught`s out of a document",
     "this run's own perturbation list", lambda c: c["perturbations"]),
    ("table count", r"all (\d+) tables",
     "this run's own table count", lambda c: c["tables"]),
    ("checked table count", r"the (\d+) of them this reconciler checks",
     "this run's own checked-table count", lambda c: c["checked"]),
    ("cell count", r"\*\*(\d+) table cells\*\*",
     "this run's own reconciled-cell count", lambda c: c["cells"]),
    ("registry entry count", r"a registry of \*\*(\d+) entries\*\*",
     "this run's own registry size", lambda c: c["entries"]),
    ("matched claim count", r"\*\*(\d+) of \d+\*\* occurrences",
     "this run's own matched-claim count", lambda c: c["matched"]),
    ("digit class count", r"(\d+) named classes for digits",
     "this run's own digit-class count", lambda c: c["classes_digits"]),
    ("word class count", r"a parallel set for words carrying (\d+)",
     "this run's own word-class count", lambda c: c["classes_words"]),
    ("residue count", r"\*\*(\d+) numbers\*\* left over",
     "this run's own unreconciled count", lambda c: c["inventory"]),
    ("digit residue count", r"prose numbers NOT reconciled and NOT a\s+citation: (\d+)",
     "this run's own digit-inventory size", lambda c: c["inventory_digits"]),
    ("word residue count", r"words NOT reconciled and NOT a\s+citation: (\d+)",
     "this run's own word-inventory size", lambda c: c["inventory_words"]),
    ("literal entry count", r"(\d+) of them declared literals",
     "this run's own literal-entry count", lambda c: c["literals"]),
    # The per-class occurrence totals. §12 is the document's account of how much of itself is
    # checked, so its two totals are the last place a stale figure could sit unguarded — the
    # registry above guards the counts of *claims*, and nothing guarded the counts of *non-claims*.
    ("digit non-claim total", r"(\d+) \+ \d+ numbers",
     "this run's own classified-digit count", lambda c: c["classified_digits"]),
    ("word non-claim total", r"\d+ \+ (\d+) numbers",
     "this run's own classified-word count", lambda c: c["classified_words"]),
    # The slack every comparison carries, and the two figures the coverage-limits section quotes to
    # show what a metric name in the clause does to the subject check.
    ("comparison tolerance", r"flat absolute tolerance of ±(\d+\.\d+)",
     "this script's own TOLERANCE constant", lambda c: c["tolerance"]),
    ("p99 paired spread, whole-span", r"paired spreads are \*\*(\d+\.\d+) %\*\* \(whole-span\)",
     "raw/old-vs-new-analysis.txt (whole-span p99, median paired spread)",
     lambda c: c["p99_paired_whole"]),
    ("p99 paired spread, steady", r"and \*\*(\d+\.\d+) %\*\* \(steady\)",
     "raw/old-vs-new-analysis.txt (steady p99, median paired spread)",
     lambda c: c["p99_paired_steady"]),
]


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
    cells_total = sum(count for _n, _l, count, _s, _r in COVERAGE)
    skipped_total = sum(skipped for _n, _l, _c, skipped, _r in COVERAGE)

    # ---- prose claims ----
    text = doc_text()
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
        for hit in re.finditer(pattern, prose_text, re.I):
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
            if got is None and captured.lower() in WORD_VALUES:
                got = word_number(captured)
            if isinstance(expected, str):
                if captured != expected:
                    PROBLEMS.append(f"registry {name}: document says {captured!r}, artifact "
                                    f"says {expected!r} ({label})")
                continue
            # A document may round; it may not round to something the artifact does not support, so
            # the artifact's value is rounded to the precision the document itself states.
            decimals = len(captured.split(".")[1]) if "." in captured else 0
            if got is None or not (round(float(expected), decimals) == got
                                   or abs(got - float(expected)) <= TOLERANCE):
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
            kind = next((name for name, pattern in CITATIONS if re.search(pattern, context, re.I)),
                        None)
            if kind is None and "." in hit.group(0) and hit.group(0) in analysis_text:
                # The subject is looked for in the whole clause, not the display window: "queued's
                # throughput is the one exception worth stating: its median is +8.27 %" names its
                # subject more than 46 characters before the figure.
                wide = text[lo + max(0, hit.start() - 220):lo + hit.end() + 60].replace("\n", " ")
                subject = subject_row(art, wide)
                if subject and not value_in_row(art, subject[0], subject[1], float(hit.group(0))):
                    # the sentence names a workload and a metric, and the figure is not that
                    # subject's: this is the mis-association class, and it is a gap, not a pass
                    inventory.append((section, hit.group(0),
                                      context.strip() + "   [names " + subject[0] + " / " +
                                      subject[1] + ", where this value does not appear]"))
                    continue
                kind = ("value the analyzer also quotes for the subject its clause names"
                        if subject else
                        "value the analyzer also quotes (no workload or metric named in the "
                        "clause, so only \"this figure exists\" is checked)")
            if kind:
                classified.setdefault(kind, []).append((section, hit.group(0), context.strip()))
            else:
                inventory.append((section, hit.group(0), context.strip()))

    # ---- the same pass over numbers spelled out in words ----
    # The digit inventory cannot see "two of the six profiles" or "the three that differ", which is
    # how four falsehoods survived two rounds: every one of them was written in words. So the words
    # get their own registry, their own inventory, and their own classifier, and a word-registry
    # entry whose claim has been reworded away fails the run exactly as a digit one does.
    word_matched, word_spans = 0, []
    for name, pattern, label, compute in WORD_REGISTRY:
        if re.compile(pattern).groups != 1:
            PROBLEMS.append(f"word registry {name}: {re.compile(pattern).groups} capture groups, "
                            "not one — a registry bug, and this claim is UNRECONCILED")
            continue
        hits = matches_in_prose(pattern)
        if not hits:
            PROBLEMS.append(f"word registry {name}: the claim this entry guards is not in the "
                            "document's prose any more — the entry is stale")
            continue
        expected = compute(art)
        for captured, span, _whole in hits:
            word_matched += 1
            word_spans.append(span)
            got = word_number(captured)
            if abs(got - float(expected)) > 0.001:
                PROBLEMS.append(f"word registry {name}: document says {captured!r} = {got:.0f}, "
                                f"artifact says {expected:.0f} ({label})")
    word_covered = set()
    for begin, end in word_spans:
        for _section, lo, hi in located:
            if lo <= begin < hi:
                word_covered.update(range(begin, min(end, hi)))
    word_inventory, word_classified = [], {}
    for section, lo, hi in located:
        for hit in re.finditer(WORD_PATTERN, text[lo:hi], re.I):
            if lo + hit.start() in word_covered:
                continue
            context = text[lo + max(0, hit.start() - 46):lo + hit.end() + 26].replace("\n", " ")
            kind = next((name for name, pattern in CITATIONS if re.search(pattern, context, re.I)),
                        None)
            if kind:
                word_classified.setdefault(kind, []).append((section, hit.group(0), context.strip()))
            else:
                word_inventory.append((section, hit.group(0), context.strip()))

    # ---- the instrument's own counts, in §12, are computed rather than written ----
    # §12 describes this reconciliation, so its numbers are the one place a written figure drifts
    # with nothing to catch it: "11 tables" was stale by two the moment this round added them. The
    # instrument registry is evaluated against the whole document, exempt section included.
    instrument_matched = 0
    for name, pattern, label, compute in INSTRUMENT_REGISTRY:
        expected = compute({
            "tables": len(tables), "checked": len(COVERAGE), "cells": cells_total,
            "entries": len(REGISTRY) + len(WORD_REGISTRY), "matched": matched + word_matched,
            "classes_digits": len(classified), "classes_words": len(word_classified),
            "inventory": len(inventory) + len(word_inventory),
            "inventory_digits": len(inventory), "inventory_words": len(word_inventory),
            "literals": len(LITERAL_ENTRIES), "perturbations": len(PERTURBATIONS),
            "classified_digits": sum(len(hits) for hits in classified.values()),
            "classified_words": sum(len(hits) for hits in word_classified.values()),
            "tolerance": TOLERANCE,
            "p99_paired_whole": tool_cell(art, "whole-span p99", 10),
            "p99_paired_steady": tool_cell(art, "steady p99", 10)})
        if re.compile(pattern).groups != 1:
            PROBLEMS.append(f"instrument registry {name}: {re.compile(pattern).groups} groups, "
                            "not one — a registry bug")
            continue
        hits = list(re.finditer(pattern, text))
        if not hits:
            PROBLEMS.append(f"instrument registry {name}: the claim is not in the document")
            continue
        for hit in hits:
            if hit.group(1) is None:
                continue
            instrument_matched += 1
            got = number(hit.group(1))
            if got is None and hit.group(1).lower() in WORD_VALUES:
                # A count §12 states in words is the same count, and the digit-only form of this
                # loop is what let the word drift. Same fallback as the prose registry's.
                got = word_number(hit.group(1))
            if got is None or abs(got - float(expected)) > 0.001:
                PROBLEMS.append(f"instrument registry {name}: document says {hit.group(1)!r}, this "
                                f"run says {expected:.0f} ({label})")

    # ---- report ----
    print("## Table reconciliation\n")
    print("| table | reconciled against | cells reconciled | cells not reconciled (reason) |")
    print("|---|---|---|---|")
    for name, label, count, skipped, reason in COVERAGE:
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
    print("### Spelled-out numbers\n")
    print("The digit scan cannot see \"two of the six profiles\" or \"the three that differ\", and every "
          "falsehood this round fixed was written in words. So the words get the same treatment: a "
          "registry, a classifier, and an inventory that is listed in full.\n")
    print(f"- word-registry entries evaluated: **{len(WORD_REGISTRY)}**")
    print(f"- word claims matched and recomputed against an artifact: **{word_matched}**")
    print(f"- words classified as citations, references or protocol structure: "
          f"**{sum(len(v) for v in word_classified.values())}**, in {len(word_classified)} classes "
          f"(each named, with an example, so the filter is auditable):\n")
    # Printed for the words too. The document says the classes are printed with their counts and an
    # example each, and until this round that was true of the digits only — the word pass reported a
    # total and nothing behind it, which is the difference between an auditable filter and a claim.
    print("| class | words | example |")
    print("|---|---|---|")
    for kind, hits in sorted(word_classified.items(), key=lambda item: -len(item[1])):
        print(f"| {kind} | {len(hits)} | `{hits[0][1]}` in …{hits[0][2][:56]}… |")
    print()
    print(f"- **words NOT reconciled and NOT a citation: {len(word_inventory)}**\n")
    if word_inventory:
        print("| section | word | context |")
        print("|---|---|---|")
        seen = set()
        for section, value, context in word_inventory:
            if (section, context) in seen:
                continue
            seen.add((section, context))
            print(f"| §{section} | `{value}` | …{context.replace('|', '\\|')}… |")
        print()
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


# Each perturbation is a change a careless edit could make, and the reconciler must FAIL on every
# one. The first three are the defects this round's fixes removed; the rest are the mechanism's own
# holes — a spelled-out number, a mis-associated value, a stale instrument count, a range endpoint.
PERTURBATIONS = (
    ("the §5.7 grep count, which is reconciled by running git grep",
     "matches in **25** `.java` files", "matches in **99** `.java` files"),
    ("a cell of the table quoted verbatim from the analyzer",
     "| thread cpu per window | — | 6 | 0 | 0 | 0 | 0 | 1 | 4 |",
     "| thread cpu per window | — | 6 | 0 | 0 | 0 | 0 | 0 | 4 |"),
    ("the cross-artifact cell: one run's completed, another's useful",
     "| completed / useful | 4267 / 34 |", "| completed / useful | 4181 / 34 |"),
    ("a value swapped to another row of the analyzer's table",
     "its median is **+8.27 %** in the new", "its median is **+27.84 %** in the new"),
    ("a metric-dependent count, written in a word",
     "the two\nmetrics where five or six", "the three\nmetrics where five or six"),
    ("a registry claim",
     "budget MISS: +5.40 %", "budget MISS: +15.40 %"),
    ("a claim written in words",
     "the three that differ differ", "the four that differ differ"),
    ("a cell of the settlement table",
     "| queued | old-async (no change) | 6-29 |", "| queued | old-async (no change) | 7-29 |"),
    ("the instrument's own table count",
     "every cell of all 13 tables", "every cell of all 11 tables"),
    ("an endpoint of an allocation range",
     "**+1.58 % to +45.84 %**", "**+1.58 % to +45.80 %**"),
    # This cell had no entry at all until this round: `297 + 131 numbers` sat in the section that
    # claims its numbers are computed, and `999 + 999 numbers` returned PASS.
    ("a per-class occurrence total in §12 — the document's own account of how much of itself is "
     "checked, which had no entry at all until this round",
     "289 + 149 numbers", "999 + 999 numbers"),
    # The same count as the digit perturbation above, written in words. Only the digit form was
    # guarded, which is the A1/A3 class one round after it was closed — so both word forms are
    # entries now, and this is the change that proves it.
    ("a count §12 states in words — the perturbation count, whose digit form was guarded and whose "
     "word form was not",
     "because twelve `caught`s", "because nine `caught`s"),
)


def assert_in_checkout():
    """Six registry entries are `git grep` claims run from this repository's root, so this script
    only means anything inside a checkout of it. Outside one they do not fail — they have nothing to
    run — which is the silent pass this document exists to stop."""
    import subprocess
    root = HERE.parents[2]
    try:
        toplevel = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                                  capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as failure:
        raise SystemExit(f"reconcile-12e.py: no checkout at {root} to resolve git against "
                         f"({failure}) — the six §5.7 `git grep` entries would report themselves "
                         "uncomputable rather than checked") from None
    if pathlib.Path(toplevel).resolve() != root.resolve():
        raise SystemExit(f"reconcile-12e.py: {root} is not the root of a checkout "
                         f"(git says {toplevel}); run this from a clone of the repository")


def self_test():
    """Apply each perturbation and require the reconciler to fail. Returns 0 when all fail.

    The unperturbed run is asserted to pass first. Without it a broken environment reports its
    eleven `caught`s out of a document that was already failing, which is a self-test reporting the
    opposite of what it measured.
    """
    global DOC_TEXT, PROBLEMS, COVERAGE
    DOC_TEXT, PROBLEMS, COVERAGE = None, [], []
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        baseline = main()
    print(f"- unperturbed run: **{'PASS' if baseline == 0 else 'FAIL'}**")
    if baseline != 0:
        print()
        print(buffer.getvalue())
        print("**The unperturbed document does not reconcile, so the perturbations below would be "
              "vacuous.**\n")
        return 1
    failures = []
    for description, old, new in PERTURBATIONS:
        original = DOC.read_text()
        if original.count(old) != 1:
            failures.append(f"{description}: the perturbation no longer applies "
                            f"({original.count(old)} matches) — the check is stale")
            continue
        PROBLEMS, COVERAGE = [], []
        DOC_TEXT = original.replace(old, new)
        try:
            exit_code = main()
        except SystemExit as stop:
            exit_code = stop.code
        finally:
            DOC_TEXT = None
        caught = exit_code != 0
        print(f"{'caught  ' if caught else 'MISSED  '} {description}")
        if not caught:
            failures.append(description)
    PROBLEMS, COVERAGE = [], []
    print()
    if failures:
        print(f"**{len(failures)} of {len(PERTURBATIONS)} perturbations went undetected**")
        for failure in failures:
            print(f"- {failure}")
        return 1
    print(f"**all {len(PERTURBATIONS)} perturbations caught**")
    return 0


if __name__ == "__main__":
    assert_in_checkout()
    if "--perturbations" in sys.argv:
        print("## Perturbation self-test\n")
        raise SystemExit(self_test())
    raise SystemExit(main())
