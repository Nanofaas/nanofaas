from __future__ import annotations

from pathlib import Path
import subprocess


def test_controlplane_import_contracts_pass() -> None:
    tool_root = Path(__file__).resolve().parents[1]

    result = subprocess.run(
        ["uv", "run", "lint-imports"],
        cwd=tool_root,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0, result.stdout + result.stderr
