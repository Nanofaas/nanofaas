from __future__ import annotations

import json
import pytest
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
from controlplane_tool.scenario.tasks.functions import FunctionSpec, RegisterFunctions


class _CapturingHandler(BaseHTTPRequestHandler):
    captured: list[dict] = []

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        _CapturingHandler.captured.append(json.loads(body))
        self.send_response(201)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args: object) -> None:
        pass


def _start_server() -> tuple[HTTPServer, str]:
    server = HTTPServer(("127.0.0.1", 0), _CapturingHandler)
    port = server.server_address[1]
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://127.0.0.1:{port}"


def test_register_functions_posts_to_rest_api() -> None:
    _CapturingHandler.captured.clear()
    server, url = _start_server()
    try:
        specs = [
            FunctionSpec(name="word-stats-java", image="registry/word-stats-java:e2e"),
            FunctionSpec(name="json-transform-java", image="registry/json-transform-java:e2e"),
        ]
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=url,
            specs=specs,
        )
        task.run()
        assert len(_CapturingHandler.captured) == 2
        names = [b["name"] for b in _CapturingHandler.captured]
        assert "word-stats-java" in names
        assert "json-transform-java" in names
    finally:
        server.shutdown()


def test_register_functions_body_matches_expected_schema() -> None:
    _CapturingHandler.captured.clear()
    server, url = _start_server()
    try:
        specs = [FunctionSpec(name="my-fn", image="registry/my-fn:e2e")]
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=url,
            specs=specs,
        )
        task.run()
        body = _CapturingHandler.captured[0]
        assert body["name"] == "my-fn"
        assert body["image"] == "registry/my-fn:e2e"
        assert "timeoutMs" in body
        assert "executionMode" in body
    finally:
        server.shutdown()


def test_register_functions_raises_on_http_error() -> None:
    class _ErrorHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b"internal error")

        def log_message(self, *args: object) -> None:
            pass

    server = HTTPServer(("127.0.0.1", 0), _ErrorHandler)
    port = server.server_address[1]
    Thread(target=server.serve_forever, daemon=True).start()
    try:
        task = RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=f"http://127.0.0.1:{port}",
            specs=[FunctionSpec(name="fn", image="img")],
        )
        with pytest.raises(Exception):
            task.run()
    finally:
        server.shutdown()


def test_register_functions_no_cli_dependency() -> None:
    import inspect
    from controlplane_tool.scenario.tasks import functions
    source = inspect.getsource(functions)
    assert "nanofaas-cli" not in source
    assert "fn apply" not in source
    assert "subprocess" not in source
