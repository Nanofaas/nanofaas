"""Keep B5's expected outputs synchronized with its reference and medium corpus."""

import subprocess
import sys
from pathlib import Path


def test_committed_runtime_contract_matches_generator():
    result = subprocess.run(
        [sys.executable, str(Path(__file__).with_name("runtime_contract.py")), "--check"],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
