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
    scaling_config: dict[str, object] | None = None

    def to_body(self) -> dict[str, object]:
        body: dict[str, object] = {
            "name": self.name,
            "image": self.image,
            "executionMode": self.execution_mode,
            "timeoutMs": self.timeout_ms,
            "concurrency": self.concurrency,
            "queueSize": self.queue_size,
            "maxRetries": self.max_retries,
        }
        if self.scaling_config is not None:
            body["scalingConfig"] = self.scaling_config
        return body


@dataclass
class RegisterFunctions:
    """Register functions via POST /v1/functions REST API. No CLI dependency.

    ``on_conflict`` controls HTTP 409 (function already registered) handling:
    ``"fail"`` (default) raises; ``"skip"`` keeps the existing registration;
    ``"replace"`` deletes the existing function and registers the new spec
    (needed when the spec differs, e.g. to apply a scalingConfig).
    """

    task_id: str
    title: str
    control_plane_url: str
    specs: list[FunctionSpec] = field(default_factory=list)
    on_conflict: str = "fail"

    def run(self) -> None:
        base = self.control_plane_url.rstrip("/")
        url = f"{base}/v1/functions"
        for spec in self.specs:
            try:
                self._post(url, spec)
            except urllib.error.HTTPError as exc:
                if exc.code != 409 or self.on_conflict == "fail":
                    raise RuntimeError(
                        f"Failed to register function '{spec.name}': HTTP {exc.code}"
                    ) from exc
                if self.on_conflict == "skip":
                    continue
                self._delete(base, spec.name)
                try:
                    self._post(url, spec)
                except urllib.error.HTTPError as exc2:
                    raise RuntimeError(
                        f"Failed to re-register function '{spec.name}': HTTP {exc2.code}"
                    ) from exc2

    @staticmethod
    def _post(url: str, spec: FunctionSpec) -> None:
        body = json.dumps(spec.to_body()).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=30):
            pass

    @staticmethod
    def _delete(base: str, name: str) -> None:
        req = urllib.request.Request(f"{base}/v1/functions/{name}", method="DELETE")
        try:
            with urllib.request.urlopen(req, timeout=30):
                pass
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise RuntimeError(
                    f"Failed to delete function '{name}': HTTP {exc.code}"
                ) from exc
