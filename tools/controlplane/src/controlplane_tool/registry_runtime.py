"""
registry_runtime.py

Helpers for local container image registry naming conventions
used across k3s and VM-backed E2E scenarios.
"""
from __future__ import annotations


class LocalRegistry:
    """Convenience wrapper for local Docker registry image naming."""

    DEFAULT_ADDRESS = "localhost:5000"

    def __init__(self, address: str = DEFAULT_ADDRESS) -> None:
        self.address = address.rstrip("/")

    def image(self, repo: str, tag: str = "e2e") -> str:
        return f"{self.address}/{repo}:{tag}"

    def control_plane_image(self, tag: str = "e2e") -> str:
        return self.image("nanofaas/control-plane", tag)

    def function_runtime_image(self, tag: str = "e2e") -> str:
        return self.image("nanofaas/function-runtime", tag)

    @staticmethod
    def split_image_ref(image: str) -> tuple[str, str]:
        repository, separator, tag = image.rpartition(":")
        if not separator:
            return image, "latest"
        return repository, tag
