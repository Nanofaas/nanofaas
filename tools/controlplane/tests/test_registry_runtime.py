"""
Tests for registry_runtime.LocalRegistry (M11).

Gate: Image naming conventions must be stable and consistent with what K3sCurlRunner
pushes to the local registry and what Helm chart values expect.
"""
from __future__ import annotations

from controlplane_tool.registry_runtime import LocalRegistry


def test_local_registry_default_address() -> None:
    registry = LocalRegistry()
    assert registry.address == "localhost:5000"


def test_local_registry_strips_trailing_slash() -> None:
    registry = LocalRegistry("localhost:5000/")
    assert registry.address == "localhost:5000"


def test_local_registry_image_constructs_full_ref() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.image("nanofaas/control-plane", "e2e") == "localhost:5000/nanofaas/control-plane:e2e"


def test_local_registry_image_uses_e2e_tag_by_default() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.image("nanofaas/control-plane") == "localhost:5000/nanofaas/control-plane:e2e"


def test_control_plane_image_uses_canonical_repo() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.control_plane_image() == "localhost:5000/nanofaas/control-plane:e2e"


def test_function_runtime_image_uses_canonical_repo() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.function_runtime_image() == "localhost:5000/nanofaas/function-runtime:e2e"


def test_local_registry_supports_custom_tag() -> None:
    registry = LocalRegistry("myregistry.local:5001")
    assert registry.control_plane_image("latest") == "myregistry.local:5001/nanofaas/control-plane:latest"
    assert registry.function_runtime_image("rc1") == "myregistry.local:5001/nanofaas/function-runtime:rc1"


def test_local_registry_address_used_directly_in_image() -> None:
    registry = LocalRegistry("192.168.64.2:5000")
    img = registry.image("my/repo", "tag")
    assert img.startswith("192.168.64.2:5000/")
