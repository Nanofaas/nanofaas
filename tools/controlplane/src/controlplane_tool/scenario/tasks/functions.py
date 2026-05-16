from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass
class FunctionSpec:
    name: str
    image: str
    execution_mode: str = "DEPLOYMENT"
    timeout_ms: int = 5000
    concurrency: int = 2
    queue_size: int = 20
    max_retries: int = 3

    def to_body(self) -> dict[str, object]:
        return {
            "name": self.name,
            "image": self.image,
            "executionMode": self.execution_mode,
            "timeoutMs": self.timeout_ms,
            "concurrency": self.concurrency,
            "queueSize": self.queue_size,
            "maxRetries": self.max_retries,
        }


@dataclass
class RegisterFunctions:
    """Register functions via POST /v1/functions REST API. No CLI dependency."""

    task_id: str
    title: str
    control_plane_url: str
    specs: list[FunctionSpec] = field(default_factory=list)

    def run(self) -> None:
        url = f"{self.control_plane_url.rstrip('/')}/v1/functions"
        for spec in self.specs:
            body = json.dumps(spec.to_body()).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=body,
                method="POST",
                headers={"Content-Type": "application/json"},
            )
            try:
                with urllib.request.urlopen(req, timeout=30):
                    pass
            except urllib.error.HTTPError as exc:
                raise RuntimeError(
                    f"Failed to register function '{spec.name}': HTTP {exc.code}"
                ) from exc
