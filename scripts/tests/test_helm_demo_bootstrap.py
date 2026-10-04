"""Run the rendered hook with real curl against a controlled HTTP API."""
from __future__ import annotations

import json
import socket
import subprocess
import threading
import textwrap
from pathlib import Path
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest



@contextmanager
def demo_api():
    state = {"functions": {}, "failures": {}, "calls": [], "missing_on_get": False}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            state["calls"].append(("GET", self.path))
            if self.path == "/actuator/health/readiness":
                self.reply(200, {"status": "UP"})
                return
            name = self.path.rsplit("/", 1)[-1]
            if name in state["functions"] and not state["missing_on_get"]:
                self.reply(200, state["functions"][name])
            else:
                self.reply(404, {"error": "missing"})

        def do_POST(self):
            spec = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            name = spec["name"]
            state["calls"].append(("POST", name))
            failure = state["failures"].get(name)
            if failure == "transport":
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
            elif failure:
                self.reply(failure, {"error": "registration failed"})
            elif name in state["functions"]:
                self.reply(409, {"error": "already registered"})
            else:
                state["functions"][name] = spec
                self.reply(201, spec)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield state, server.server_port
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def run_bootstrap(tmp_path, port, *, second_env=None):
    values = {
        "controlPlane": {"service": {"name": "127.0.0.1", "ports": {"http": port, "actuator": port}}},
        "demos": {"functions": [
            {"name": "first-demo", "image": "example:first", "executionMode": "LOCAL"},
            {"name": "second-demo", "image": "example:second", "executionMode": "LOCAL", "env": second_env or {}},
        ]},
    }
    path = tmp_path / "values.yaml"
    path.write_text(json.dumps(values))
    chart = Path(__file__).resolve().parents[2] / "deploy/helm/nanofaas"
    rendered = subprocess.run(["helm", "template", "nanofaas", str(chart), "--show-only",
                               "templates/demo-register-job.yaml", "-f", str(path)],
                              check=True, capture_output=True, text=True).stdout
    script = textwrap.dedent(rendered.split("            - |", 1)[1])
    return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=5)


def test_bootstrap_replay_preserves_existing_user_edits(tmp_path):
    with demo_api() as (state, port):
        assert run_bootstrap(tmp_path, port).returncode == 0
        state["functions"]["first-demo"]["image"] = "user:edited"
        result = run_bootstrap(tmp_path, port)
        assert result.returncode == 0, result.stderr
        assert set(state["functions"]) == {"first-demo", "second-demo"}
        assert state["functions"]["first-demo"]["image"] == "user:edited"
        assert ("GET", "/v1/functions/first-demo") in state["calls"]
        assert ("GET", "/v1/functions/second-demo") in state["calls"]


def test_bootstrap_retry_after_second_demo_failure_converges(tmp_path):
    with demo_api() as (state, port):
        state["failures"]["second-demo"] = 500
        assert run_bootstrap(tmp_path, port).returncode != 0
        assert set(state["functions"]) == {"first-demo"}
        state["failures"].clear()
        result = run_bootstrap(tmp_path, port)
        assert result.returncode == 0, result.stderr
        assert set(state["functions"]) == {"first-demo", "second-demo"}


@pytest.mark.parametrize("failure", [400, 500, "transport"])
def test_bootstrap_does_not_ignore_registration_errors(tmp_path, failure):
    with demo_api() as (state, port):
        state["failures"]["first-demo"] = failure
        result = run_bootstrap(tmp_path, port)
        assert result.returncode != 0
        assert state["functions"] == {}
        assert ("POST", "second-demo") not in state["calls"]


def test_bootstrap_does_not_accept_conflict_without_readable_function(tmp_path):
    with demo_api() as (state, port):
        state["failures"]["first-demo"] = 409
        state["missing_on_get"] = True
        assert run_bootstrap(tmp_path, port).returncode != 0
        assert state["functions"] == {}


def test_bootstrap_payload_keeps_apostrophes_literal(tmp_path):
    with demo_api() as (state, port):
        result = run_bootstrap(tmp_path, port, second_env={"LABEL": "O'Reilly"})
        assert result.returncode == 0, result.stderr
        assert state["functions"]["second-demo"]["env"]["LABEL"] == "O'Reilly"
