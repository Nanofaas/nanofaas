from __future__ import annotations

from dataclasses import dataclass
import json
from urllib.parse import quote

import httpx
from tenacity import RetryError, Retrying, stop_after_delay, wait_fixed


@dataclass(frozen=True)
class SutFixture:
    function_name: str
    registered: bool
    warmup_status_code: int


class SutPreflight:
    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8080",
        fixture_name: str = "tool-metrics-echo",
        deployment_demo_name: str = "demo-word-stats-deployment",
        deployment_demo_image: str = "word-stats:test",
        request_timeout_seconds: float = 4.0,
        ready_timeout_seconds: float = 30.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.fixture_name = fixture_name
        self.deployment_demo_name = deployment_demo_name
        self.deployment_demo_image = deployment_demo_image
        self.request_timeout_seconds = request_timeout_seconds
        self.ready_timeout_seconds = ready_timeout_seconds

    def ensure_fixture(self) -> SutFixture:
        self._wait_control_plane_ready()

        function_path = f"/v1/functions/{quote(self.fixture_name, safe='')}"
        status, _ = self._request("GET", function_path)
        created = False
        if status == 404:
            register_payload = {
                "name": self.fixture_name,
                "image": "local",
                "executionMode": "LOCAL",
                "timeoutMs": 3000,
                "concurrency": 1,
                "queueSize": 10,
                "maxRetries": 0,
            }
            create_status, create_body = self._request("POST", "/v1/functions", register_payload)
            if create_status not in {201, 409}:
                raise RuntimeError(
                    f"fixture registration failed ({create_status}): {self._trim(create_body)}"
                )
            created = create_status == 201
        elif status != 200:
            raise RuntimeError(f"fixture lookup failed ({status})")

        warmup_status, warmup_body = self._request(
            "POST",
            f"{function_path}:invoke",
            {
                "input": {"probe": "ok"},
                "metadata": {"source": "controlplane-tool"},
            },
        )
        if warmup_status != 200:
            raise RuntimeError(
                f"warm-up invocation failed ({warmup_status}): {self._trim(warmup_body)}"
            )

        self._ensure_deployment_demo()

        return SutFixture(
            function_name=self.fixture_name,
            registered=created,
            warmup_status_code=warmup_status,
        )

    def _ensure_deployment_demo(self) -> None:
        path = f"/v1/functions/{quote(self.deployment_demo_name, safe='')}"
        status, body = self._request("GET", path)
        if status == 404:
            payload = {
                "name": self.deployment_demo_name,
                "image": self.deployment_demo_image,
                "executionMode": "DEPLOYMENT",
                "timeoutMs": 5000,
                "concurrency": 1,
                "queueSize": 10,
                "maxRetries": 0,
            }
            register_status, register_body = self._request("POST", "/v1/functions", payload)
            if register_status not in {201, 409}:
                raise RuntimeError(
                    "deployment demo registration failed "
                    f"({register_status}): {self._trim(register_body)}"
                )
            status, body = self._request("GET", path)

        if status != 200:
            raise RuntimeError(f"deployment demo lookup failed ({status})")

        execution_mode = self._extract_execution_mode(body)
        if execution_mode != "DEPLOYMENT":
            raise RuntimeError(
                "deployment demo verification failed: "
                f"expected executionMode=DEPLOYMENT, got {execution_mode!r}"
            )

    def _wait_control_plane_ready(self) -> None:
        try:
            for attempt in Retrying(
                stop=stop_after_delay(self.ready_timeout_seconds),
                wait=wait_fixed(0.5),
            ):
                with attempt:
                    status, _ = self._request("GET", "/v1/functions")
                    if status != 200:
                        raise RuntimeError(f"not ready: HTTP {status}")
        except RetryError as exc:
            raise RuntimeError("control-plane API not ready on /v1/functions") from exc

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
    ) -> tuple[int, str]:
        url = f"{self.base_url}{path}"
        try:
            response = httpx.request(
                method,
                url,
                json=payload,
                headers={"Accept": "application/json"},
                timeout=self.request_timeout_seconds,
            )
            return (response.status_code, response.text)
        except httpx.RequestError as exc:
            raise RuntimeError(f"{method} {path} request failed: {exc}") from exc

    def _trim(self, text: str, limit: int = 240) -> str:
        stripped = text.strip().replace("\n", " ")
        if len(stripped) <= limit:
            return stripped
        return stripped[:limit] + "..."

    def _extract_execution_mode(self, body: str) -> str | None:
        if not body.strip():
            return None
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            return None
        if not isinstance(data, dict):
            return None
        value = data.get("executionMode")
        if isinstance(value, str):
            return value
        return None
