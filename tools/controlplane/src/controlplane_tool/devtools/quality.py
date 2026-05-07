from __future__ import annotations

import subprocess
import sys

ENTRYPOINT_IMPORT_MODULES = (
    "controlplane_tool.app.main",
    "controlplane_tool.cli.commands",
    "controlplane_tool.building.gradle_executor",
)


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
