"""
scenario_runtime.py

Shared runtime helpers for local E2E scenario execution (M9+).

Replaces the inline bash utility functions previously scattered across
scripts/lib/e2e-container-local-backend.sh and
scripts/lib/e2e-deploy-host-backend.sh.
"""
from __future__ import annotations

import shutil
from pathlib import Path

import httpx
from tenacity import RetryError, Retrying, retry_if_exception_type, stop_after_attempt, wait_fixed

from controlplane_tool.process_streaming import spawn_logged_process


def wait_for_http_ok(url: str, max_attempts: int = 60, interval_seconds: float = 1.0) -> bool:
    """Poll *url* until it returns HTTP 2xx.  Returns True on success, False on timeout."""
    try:
        for attempt in Retrying(
            stop=stop_after_attempt(max_attempts),
            wait=wait_fixed(interval_seconds),
        ):
            with attempt:
                response = httpx.get(url, timeout=5)
                if response.status_code >= 300:
                    raise RuntimeError(f"non-2xx: {response.status_code}")
    except RetryError:
        return False
    return True


def wait_for_http_any_status(url: str, max_attempts: int = 30, interval_seconds: float = 1.0) -> bool:
    """Poll *url* until any response is received (any HTTP status code)."""
    try:
        for attempt in Retrying(
            stop=stop_after_attempt(max_attempts),
            wait=wait_fixed(interval_seconds),
            retry=retry_if_exception_type(httpx.RequestError),
        ):
            with attempt:
                httpx.get(url, timeout=5)
    except RetryError:
        return False
    return True


def select_container_runtime(preferred: str | None = None) -> str | None:
    """Detect a working Docker-compatible container runtime.

    Tries *preferred* first, then docker / podman / nerdctl in order.
    Returns the first runtime that is found on PATH; None if none found.
    """
    candidates = [preferred] if preferred else []
    candidates += ["docker", "podman", "nerdctl"]

    for candidate in candidates:
        if candidate and shutil.which(candidate):
            return candidate
    return None


class FakeControlPlane:
    """Minimal HTTP server that records POST /v1/functions requests.

    Used by the deploy-host E2E to capture registration payloads sent by
    the nanofaas CLI without standing up a real control-plane.
    """

    def __init__(self, port: int, request_body_path: Path) -> None:
        self.port = port
        self.request_body_path = request_body_path
        self._proc: object = None

    def start(self, work_dir: Path) -> None:
        import json
        import subprocess

        script = work_dir / "fake-control-plane.py"
        log = work_dir / "fake-control-plane.log"

        script.write_text(
            f"""\
import http.server, json, pathlib, sys
port = {self.port}
request_body_path = pathlib.Path(r"{self.request_body_path}")

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args): return
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        if self.path != "/v1/functions":
            self.send_response(404); self.end_headers(); return
        request_body_path.write_text(body, encoding="utf-8")
        try:
            p = json.loads(body)
            resp = json.dumps({{"name": p.get("name",""), "image": p.get("image","")}}).encode()
        except Exception:
            resp = b'{{}}'
        self.send_response(201)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(resp)))
        self.end_headers(); self.wfile.write(resp)

http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
""",
            encoding="utf-8",
        )
        import os
        self._proc = spawn_logged_process(
            ["python3", str(script)],
            cwd=work_dir,
            env=os.environ.copy(),
            log_path=log,
        )

    def stop(self) -> None:
        if self._proc is not None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except Exception:
                self._proc.kill()

    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"
