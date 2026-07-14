#!/usr/bin/env python3
"""Exercise the native NanoFaaS CLI without a deployed control plane."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import threading


class FunctionListHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - HTTP handler API
        if self.path != "/v1/functions":
            self.send_error(404)
            return

        body = b'[{"name":"smoke","image":"example/smoke:latest"}]'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


def run(binary: Path, *args: str) -> str:
    completed = subprocess.run(
        [str(binary), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    if not completed.stdout.strip():
        raise RuntimeError(f"native CLI produced no output for {' '.join(args)}")
    return completed.stdout


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: native-cli-smoke.py <native-cli-binary>")

    binary = Path(sys.argv[1])
    if not binary.is_file():
        raise SystemExit(f"native CLI binary not found: {binary}")

    help_output = run(binary, "--help")
    version_output = run(binary, "--version")
    if "Usage:" not in help_output or "nanofaas" not in version_output:
        raise RuntimeError("native CLI help or version output is invalid")

    server = ThreadingHTTPServer(("127.0.0.1", 0), FunctionListHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        output = run(binary, "--endpoint", f"http://127.0.0.1:{server.server_port}", "fn", "list")
    finally:
        server.shutdown()
        thread.join()
        server.server_close()

    if "smoke\texample/smoke:latest" not in output:
        raise RuntimeError("native CLI did not print the stubbed function")

    print("Native CLI smoke checks OK")


if __name__ == "__main__":
    main()
