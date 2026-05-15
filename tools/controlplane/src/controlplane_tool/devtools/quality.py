from __future__ import annotations

import subprocess
import sys

ENTRYPOINT_IMPORT_MODULES = (
    "controlplane_tool.app.main",
    "controlplane_tool.cli.commands",
    "controlplane_tool.building.gradle_executor",
)


_GRIMP_CHECK = """
import grimp

graph = grimp.build_graph("controlplane_tool", "tui_toolkit", "workflow_tasks")
violations = []

chain = graph.find_shortest_chain(importer="workflow_tasks", imported="tui_toolkit")
if chain:
    violations.append(f"workflow_tasks -> tui_toolkit: {' -> '.join(chain)}")

chain = graph.find_shortest_chain(importer="tui_toolkit", imported="controlplane_tool")
if chain:
    violations.append(f"tui_toolkit -> controlplane_tool: {' -> '.join(chain)}")

if violations:
    for v in violations:
        print(f"VIOLATION: {v}")
    raise SystemExit(1)

print("Cross-project coupling: OK")
"""

CHECKS = (
    ("ruff", ["ruff", "check", "."]),
    ("basedpyright", ["basedpyright"]),
    ("import-linter", ["lint-imports"]),
    (
        "entrypoint-imports",
        [
            sys.executable,
            "-c",
            (
                "import importlib; "
                f"[importlib.import_module(name) for name in {ENTRYPOINT_IMPORT_MODULES!r}]"
            ),
        ],
    ),
    ("cross-project-coupling", [sys.executable, "-c", _GRIMP_CHECK]),
)


def main() -> None:
    failures: list[str] = []
    for name, command in CHECKS:
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            failures.append(name)

    if failures:
        joined = ", ".join(failures)
        raise SystemExit(f"Quality checks failed: {joined}")

    sys.stdout.write("Quality checks passed\n")
