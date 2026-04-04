"""
Tests for net_utils.py — is_port_free and pick_local_port.
"""
from __future__ import annotations

import socket
from unittest.mock import patch, MagicMock

import pytest

from controlplane_tool.net_utils import is_port_free, pick_local_port


# ---------------------------------------------------------------------------
# is_port_free
# ---------------------------------------------------------------------------

def test_is_port_free_returns_true_when_port_available() -> None:
    with patch("socket.socket") as mock_sock_cls:
        mock_sock = MagicMock()
        mock_sock_cls.return_value.__enter__ = lambda s: s
        mock_sock_cls.return_value.__exit__ = MagicMock(return_value=False)
        mock_sock_cls.return_value.bind = MagicMock()
        with patch("controlplane_tool.net_utils.socket.socket") as ms:
            instance = MagicMock()
            instance.bind = MagicMock()
            ms.return_value.__enter__ = lambda s: instance
            ms.return_value.__exit__ = MagicMock(return_value=False)
            instance.setsockopt = MagicMock()
            result = is_port_free(9999)
    assert result is True


def test_is_port_free_returns_false_when_port_in_use() -> None:
    with patch("controlplane_tool.net_utils.socket.socket") as ms:
        instance = MagicMock()
        instance.__enter__ = lambda s: instance
        instance.__exit__ = MagicMock(return_value=False)
        instance.setsockopt = MagicMock()
        instance.bind = MagicMock(side_effect=OSError("address in use"))
        ms.return_value = instance
        result = is_port_free(80)
    assert result is False


# ---------------------------------------------------------------------------
# pick_local_port
# ---------------------------------------------------------------------------

def test_pick_local_port_returns_preferred_when_free() -> None:
    with patch("controlplane_tool.net_utils.is_port_free", return_value=True):
        port = pick_local_port(preferred=9000)
    assert port == 9000


def test_pick_local_port_skips_preferred_when_taken() -> None:
    with patch("controlplane_tool.net_utils.is_port_free", return_value=False):
        port = pick_local_port(preferred=9000)
    assert port != 9000
    assert 1024 < port < 65536


def test_pick_local_port_skips_blocked_ports() -> None:
    # preferred is "free" but blocked → must pick a different port
    with patch("controlplane_tool.net_utils.is_port_free", return_value=True):
        port = pick_local_port(preferred=9000, blocked={9000})
    assert port != 9000


def test_pick_local_port_returns_preferred_not_in_blocked() -> None:
    with patch("controlplane_tool.net_utils.is_port_free", return_value=True):
        port = pick_local_port(preferred=9001, blocked={9000})
    assert port == 9001
