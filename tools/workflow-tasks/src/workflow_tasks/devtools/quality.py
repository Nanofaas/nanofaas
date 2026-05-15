from __future__ import annotations

import subprocess
import sys

CHECKS = (
    ("ruff", ["ruff", "check", "."]),
    ("basedpyright", ["basedpyright"]),
    ("import-linter", ["lint-imports"]),
)


def main() -> None:
    failures: list[str] = []
    for name, command in CHECKS:
        completed = subprocess.run(command, check=False)
        if completed.returncode != 0:
            failures.append(name)
    if failures:
        raise SystemExit(f"Quality checks failed: {', '.join(failures)}")
    sys.stdout.write("Quality checks passed\n")
