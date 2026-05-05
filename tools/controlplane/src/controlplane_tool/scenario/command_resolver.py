"""
command_resolver.py - Stateless placeholder substitution for scenario plan steps.

Extracted from E2eRunner to isolate env-building from orchestration.
"""
from __future__ import annotations

import re
from typing import Callable

from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest


class CommandResolver:
    """Resolves <placeholder> tokens in scenario step commands and env dicts."""

    _MULTIPASS_IP_RE = re.compile(r"<multipass-ip:([^>]+)>")

    def __init__(
        self,
        host_resolver: Callable[[VmRequest], str] | None,
    ) -> None:
        self._host_resolver = host_resolver

    def _replace(self, text: str, replacements: dict[str, str]) -> str:
        for key, value in replacements.items():
            text = text.replace(f"<{key}>", value)
        return text

    def resolve_placeholder_text(self, text: str) -> str:
        """Return text unchanged - override point for runtime IP injection."""
        return text

    def resolve_command(
        self,
        command: list[str],
        env: dict[str, str],
    ) -> list[str]:
        return [self._replace(token, env) for token in command]

    def _resolve_ip(self, vm: VmOrchestrator, vm_request: VmRequest) -> str:
        """Resolve the real IP of a VM, using an injected resolver if provided."""
        if self._host_resolver is not None:
            return self._host_resolver(vm_request)
        return vm.resolve_multipass_ipv4(vm_request)

    def _resolve_placeholder_text(
        self,
        value: str,
        vm_request: VmRequest | None,
        cache: dict[str, str],
        vm: VmOrchestrator,
    ) -> str:
        def _replace(m: re.Match) -> str:
            key = m.group(1)
            if key not in cache and vm_request is not None:
                cache[key] = self._resolve_ip(vm, vm_request)
            return cache.get(key, m.group(0))

        return self._MULTIPASS_IP_RE.sub(_replace, value)

    def _resolve_command(
        self,
        command: list[str],
        vm_request: VmRequest | None,
        cache: dict[str, str],
        vm: VmOrchestrator,
    ) -> list[str]:
        """Substitute <multipass-ip:name> placeholders with real IPs."""
        if not any(self._MULTIPASS_IP_RE.search(arg) for arg in command):
            return command
        return [self._resolve_placeholder_text(arg, vm_request, cache, vm) for arg in command]

    def _resolve_env(
        self,
        env: dict[str, str],
        vm_request: VmRequest | None,
        cache: dict[str, str],
        vm: VmOrchestrator,
    ) -> dict[str, str]:
        if not any(self._MULTIPASS_IP_RE.search(value) for value in env.values()):
            return env
        return {
            key: self._resolve_placeholder_text(value, vm_request, cache, vm)
            for key, value in env.items()
        }
