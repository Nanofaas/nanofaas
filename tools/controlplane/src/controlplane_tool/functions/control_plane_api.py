"""
control_plane_api.py

URL and request builders for the nanofaas control-plane HTTP API.

This module centralises the API surface used by Python scenario runners so
that individual backends never hard-code raw URL strings.  It replaces the
ad-hoc curl snippets currently scattered across shell backend scripts.

Milestones that depend on this module: M9, M10, M11, M12.
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ControlPlaneApi:
    """Builds URLs and request bodies for the nanofaas control-plane API."""

    base_url: str
    mgmt_port: int | None = None

    def __post_init__(self) -> None:
        self.base_url = self.base_url.rstrip("/")

    # ------------------------------------------------------------------
    # URL builders
    # ------------------------------------------------------------------

    @property
    def register_url(self) -> str:
        """POST /v1/functions — register a new function."""
        return f"{self.base_url}/v1/functions"

    def function_url(self, name: str) -> str:
        """GET/DELETE /v1/functions/{name}."""
        return f"{self.base_url}/v1/functions/{name}"

    def invoke_url(self, name: str) -> str:
        """POST /v1/functions/{name}:invoke — synchronous invocation."""
        return f"{self.base_url}/v1/functions/{name}:invoke"

    def enqueue_url(self, name: str) -> str:
        """POST /v1/functions/{name}:enqueue — asynchronous enqueue."""
        return f"{self.base_url}/v1/functions/{name}:enqueue"

    def replicas_url(self, name: str) -> str:
        """PUT /v1/functions/{name}/replicas — scale managed deployment."""
        return f"{self.base_url}/v1/functions/{name}/replicas"

    @property
    def health_url(self) -> str:
        """GET actuator/health — readiness probe (management port)."""
        if self.mgmt_port is not None:
            host = self.base_url.split("//", 1)[-1].split(":")[0]
            scheme = self.base_url.split("//")[0]
            return f"{scheme}//{host}:{self.mgmt_port}/actuator/health"
        return f"{self.base_url}/actuator/health"

    # ------------------------------------------------------------------
    # Request body builders
    # ------------------------------------------------------------------

    def register_body(
        self,
        name: str,
        image: str,
        *,
        execution_mode: str = "DEPLOYMENT",
        timeout_ms: int = 5000,
        concurrency: int = 2,
        queue_size: int = 20,
        max_retries: int = 3,
        endpoint_url: str | None = None,
    ) -> dict[str, object]:
        body: dict[str, object] = {
            "name": name,
            "image": image,
            "timeoutMs": timeout_ms,
            "concurrency": concurrency,
            "queueSize": queue_size,
            "maxRetries": max_retries,
            "executionMode": execution_mode,
        }
        if endpoint_url is not None:
            body["endpointUrl"] = endpoint_url
        return body

    def scale_body(self, replicas: int) -> dict[str, object]:
        return {"replicas": replicas}

    def invoke_body(
        self,
        payload: dict[str, object] | None = None,
    ) -> dict[str, object]:
        return {"input": payload or {}}
