from __future__ import annotations

import contextlib
import subprocess
import sys
from collections.abc import Iterator

# macOS `caffeinate` flags: -i prevent idle system sleep, -m prevent disk sleep,
# -s prevent system sleep while on AC power.
_CAFFEINATE_CMD = ["caffeinate", "-i", "-m", "-s"]


@contextlib.contextmanager
def prevent_host_sleep() -> Iterator[None]:
    """Keep the host awake for the duration of the block.

    On macOS this holds a ``caffeinate`` assertion; on other platforms it is a
    no-op. Long VM-backed scenarios otherwise let the host idle-sleep, which drifts
    the guest VM clock relative to the host and breaks time-windowed metric queries.

    NOTE: this prevents *idle* sleep only — it does not stop forced sleep (e.g. the
    laptop lid closing). Pair it with clock-robust metric queries for full coverage.
    """
    proc: subprocess.Popen | None = None
    if sys.platform == "darwin":
        try:
            proc = subprocess.Popen(_CAFFEINATE_CMD)
        except (OSError, ValueError):
            proc = None
    try:
        yield
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
