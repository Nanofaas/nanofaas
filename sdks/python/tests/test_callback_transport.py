from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

import pytest
import asyncio
from nanofaas.runtime.callback_transport import post_callback


@pytest.fixture
def callback_server():
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def handle_request(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            calls.append((self.command, self.path, body, dict(self.headers)))
            if self.path.startswith("/redirect/"):
                status = int(self.path.rsplit("/", 1)[1])
                self.send_response(status)
                self.send_header("Location", "/status/204")
            else:
                status = int(self.path.rsplit("/", 1)[1])
                self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        do_POST = handle_request
        do_GET = handle_request

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize("status", [204, 400, 408, 429, 500])
def test_post_status_and_payload(callback_server, status):
    base, calls = callback_server
    headers = {"Content-Type": "application/json", "X-Dispatch-Attempt": "4"}
    assert asyncio.run(post_callback(base + f"/status/{status}", b'{"ok":true}', headers, 1)) == status
    method, path, body, received = calls[-1]
    assert method == "POST"
    assert body == b'{"ok":true}'
    normalized = {key.lower(): value for key, value in received.items()}
    assert normalized["content-type"] == "application/json"
    assert normalized["x-dispatch-attempt"] == "4"


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
def test_redirect_behavior(callback_server, status):
    base, calls = callback_server
    headers = {"Content-Type": "application/json", "X-Trace-Id": "trace-1"}
    assert asyncio.run(post_callback(base + f"/redirect/{status}", b"{}", headers, 1)) == 204
    preserves_post = status in (307, 308)
    assert [(method, path, body) for method, path, body, _ in calls] == [
        ("POST", f"/redirect/{status}", b"{}"),
        ("POST" if preserves_post else "GET", "/status/204", b"{}" if preserves_post else b""),
    ]
    for _, _, _, received in calls:
        normalized = {key.lower(): value for key, value in received.items()}
        assert normalized["x-trace-id"] == "trace-1"
