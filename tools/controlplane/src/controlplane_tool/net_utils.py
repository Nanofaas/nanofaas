"""
net_utils.py

Shared socket/port utilities — extracted from the duplicate implementations
in prometheus_runtime.py, control_plane_runtime.py, and mockk8s_runtime.py.
"""
from __future__ import annotations

import socket


def is_port_free(port: int) -> bool:
    """Return True if *port* can be bound on 127.0.0.1."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def pick_local_port(preferred: int, blocked: set[int] | None = None) -> int:
    """Return *preferred* if free and not blocked, otherwise an OS-assigned port."""
    blocked = blocked or set()
    if preferred not in blocked and is_port_free(preferred):
        return preferred
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        candidate = int(sock.getsockname()[1])
    if candidate in blocked:
        return pick_local_port(preferred=0, blocked=blocked)
    return candidate
