"""
net_utils.py

Shared socket/port utilities — extracted from the duplicate implementations
in prometheus_runtime.py, control_plane_runtime.py, and mockk8s_runtime.py.
"""
from __future__ import annotations

import errno
import socket


def _can_bind(family: int, address: str, port: int) -> bool | None:
    try:
        with socket.socket(family, socket.SOCK_STREAM) as sock:
            sock.bind((address, port))
    except OSError as exc:
        if family == socket.AF_INET6 and getattr(exc, "errno", None) in {
            errno.EAFNOSUPPORT,
            errno.EPROTONOSUPPORT,
            errno.EINVAL,
        }:
            return None
        message = str(exc).lower()
        if family == socket.AF_INET6 and any(
            marker in message
            for marker in ("address family not supported", "protocol not supported", "invalid argument")
        ):
            return None
        return False
    return True


def is_port_free(port: int) -> bool:
    """Return True if *port* can be bound on both IPv4 and IPv6 loopback."""
    if _can_bind(socket.AF_INET, "127.0.0.1", port) is False:
        return False

    ipv6_available = _can_bind(socket.AF_INET6, "::1", port)
    if ipv6_available is False:
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
