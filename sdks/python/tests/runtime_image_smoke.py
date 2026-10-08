"""Run with a built word-stats image: python runtime_image_smoke.py IMAGE."""
import http.server
import json
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


def main(image):
    delivered = threading.Event()
    callbacks = []

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            callbacks.append((self.path, dict(self.headers), json.loads(
                self.rfile.read(int(self.headers["Content-Length"])))))
            self.send_response(204)
            self.end_headers()
            delivered.set()

        def log_message(self, *_args):
            pass

    callback = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Callback)
    thread = threading.Thread(target=callback.serve_forever, daemon=True)
    thread.start()
    with socket.socket() as free_port:
        free_port.bind(("127.0.0.1", 0))
        port = free_port.getsockname()[1]
    name = f"nanofaas-audit-python-{uuid.uuid4().hex}"
    try:
        subprocess.run(["docker", "run", "-d", "--name", name, "--network", "host",
                        "-e", f"PORT={port}", image, "python", "-m", "uvicorn",
                        "nanofaas.runtime.app:app", "--host", "127.0.0.1", "--port", str(port)],
                       check=True, capture_output=True)
        subprocess.run(["docker", "exec", name, "python", "-c",
                        "import httpx, importlib.util; assert importlib.util.find_spec('pytest') is None; assert importlib.util.find_spec('requests') is None"],
                       check=True, capture_output=True)
        deadline = time.monotonic() + 15
        while True:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                    assert response.status == 200
                break
            except (OSError, urllib.error.URLError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
        request = urllib.request.Request(f"http://127.0.0.1:{port}/invoke",
            data=json.dumps({"input": {"text": "hello hello world"}}).encode(),
            headers={"Content-Type": "application/json", "X-Execution-Id": "image-callback",
                     "X-Trace-Id": "image-trace", "X-Dispatch-Attempt": "2",
                     "X-Callback-Url": f"http://127.0.0.1:{callback.server_port}/callbacks"})
        with urllib.request.urlopen(request, timeout=3) as response:
            assert response.status == 200
        assert delivered.wait(3), "Runtime image did not deliver callback"
        path, headers, body = callbacks[0]
        assert path == "/callbacks/image-callback:complete"
        assert headers["X-Trace-Id"] == "image-trace"
        assert headers["X-Dispatch-Attempt"] == "2"
        assert body["output"]["wordCount"] == 3
        print("Runtime-only Python image callback: PASS")
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        callback.shutdown()
        callback.server_close()
        thread.join(2)


if __name__ == "__main__":
    main(sys.argv[1])
