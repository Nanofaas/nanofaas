"""Connection-death retry shared by the release remote-execution paths.

Every release remote operation is idempotent (tests, digest-pinned
builds/pushes/transfers, cosign sign/attest, mkdir -p), so a dropped
connection mid-operation is safe to re-run. A return_code of -1 is paramiko's
"channel closed without an exit status" sentinel — never a real shell exit
code — and a raised exception is a connect-time failure; both mean the
connection failed, not the operation. Real non-zero exits are NOT retried.

Layering (spec D1): keepalive lives in the SSH SDK and turns a dead connection
into a fast failure; retry lives here, in the orchestrator, because only the
caller knows the operations are idempotent. The SDK must not retry.
"""

from __future__ import annotations

from collections.abc import Callable
import sys
import time

# paramiko's "channel closed without an exit status" sentinel.
CONNECTION_DEAD = -1


def retry_on_connection_death(
    operation: Callable[[], object],
    *,
    describe: str,
    attempts: int = 4,
    sleep: Callable[[float], None] = time.sleep,
) -> object:
    """Run *operation*, retrying only when the connection dies."""
    for attempt in range(1, attempts + 1):
        last = attempt == attempts
        try:
            result = operation()
        except Exception as error:  # noqa: BLE001 - reconnect on any transport error
            if last:
                raise
            _log(f"  ⟳ {describe} connection error ({error}); retry {attempt}/{attempts - 1}")
            sleep(min(5 * attempt, 30))
            continue
        if int(getattr(result, "return_code", 0)) == CONNECTION_DEAD and not last:
            _log(f"  ⟳ {describe} connection dropped; retry {attempt}/{attempts - 1}")
            sleep(min(5 * attempt, 30))
            continue
        return result
    raise AssertionError("unreachable")  # pragma: no cover


def _log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)
