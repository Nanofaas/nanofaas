# Shim: re-exports from shellcraft.net.
# socket is imported at module level so existing tests can patch net_utils.socket.socket.
import socket  # noqa: F401

import shellcraft.net as _shellcraft_net


def is_port_free(port: int) -> bool:
    """Return True if *port* can be bound on both IPv4 and IPv6 loopback."""
    return _shellcraft_net.is_port_free(port)


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
