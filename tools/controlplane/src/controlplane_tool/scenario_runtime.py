"""
scenario_runtime.py

Shared runtime helpers for local E2E scenario execution (M9+).

Replaces the inline bash utility functions previously scattered across
scripts/lib/e2e-container-local-backend.sh and
scripts/lib/e2e-deploy-host-backend.sh.
"""
from __future__ import annotations

import shutil
import time
import urllib.error
import urllib.request
from pathlib import Path


def wait_for_http_ok(url: str, max_attempts: int = 60, interval_seconds: float = 1.0) -> bool:
    """Poll *url* until it returns HTTP 2xx.  Returns True on success, False on timeout."""
    for _ in range(max_attempts):
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                if resp.status < 300:
                    return True
        except Exception:
            pass
        time.sleep(interval_seconds)
    return False


def wait_for_http_any_status(url: str, max_attempts: int = 30, interval_seconds: float = 1.0) -> bool:
    """Poll *url* until any response is received (any HTTP status code)."""
    for _ in range(max_attempts):
        try:
            urllib.request.urlopen(url, timeout=5)
            return True
        except urllib.error.HTTPError:
            return True
        except Exception:
            pass
        time.sleep(interval_seconds)
    return False


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
        with log.open("w") as log_fh:
            import os
            self._proc = subprocess.Popen(
                ["python3", str(script)],
                stdout=log_fh,
                stderr=log_fh,
                env=os.environ.copy(),
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
