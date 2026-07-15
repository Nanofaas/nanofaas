#!/usr/bin/env python3
"""
Mock callback server for testing watchdog callbacks.
Records all callbacks and provides verification endpoints.
"""

import json
import os
import sys
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# Store received callbacks
callbacks = []
callbacks_lock = threading.Lock()

PORT = int(os.environ.get("CALLBACK_PORT", "9999"))
SCENARIO = os.environ.get("CALLBACK_SCENARIO", "success")


class CallbackHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[callback] {format % args}", file=sys.stderr)

    def do_GET(self):
        """Verification endpoints"""
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())

        elif self.path == "/callbacks":
            # Return all received callbacks
            with callbacks_lock:
                response = {"callbacks": callbacks.copy()}
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

        elif self.path == "/callbacks/count":
            with callbacks_lock:
                count = len(callbacks)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"count": count}).encode())

        elif self.path == "/callbacks/last":
            with callbacks_lock:
                last = callbacks[-1] if callbacks else None
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"callback": last}).encode())

        elif self.path == "/callbacks/clear":
            with callbacks_lock:
                callbacks.clear()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"cleared": True}).encode())

        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        """Callback endpoint - records all POSTs"""
        # Extract execution ID from path
        # Expected: /v1/internal/executions/{id}:complete
        path_parts = self.path.split("/")

        # Read request body
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            payload = json.loads(body) if body else {}
        except json.JSONDecodeError:
            payload = {"raw": body.decode("utf-8", errors="replace")}

        # Extract headers
        headers = {
            "X-Trace-Id": self.headers.get("X-Trace-Id"),
            "Content-Type": self.headers.get("Content-Type"),
        }

        # Record callback
        callback_record = {
            "path": self.path,
            "payload": payload,
            "headers": headers,
        }

        with callbacks_lock:
            callbacks.append(callback_record)

        print(f"[callback] Received: {json.dumps(callback_record)}", file=sys.stderr)

        # Handle test scenarios
        if SCENARIO == "success":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"accepted": True}).encode())

        elif SCENARIO == "error_500":
            self.send_response(500)
            self.end_headers()

        elif SCENARIO == "error_503":
            self.send_response(503)
            self.end_headers()

        elif SCENARIO == "slow":
            import time
            time.sleep(5)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"accepted": True}).encode())

        elif SCENARIO == "fail_then_succeed":
            # Fail first 2 attempts, succeed on 3rd
            with callbacks_lock:
                attempt = len(callbacks)
            if attempt < 3:
                self.send_response(500)
                self.end_headers()
            else:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"accepted": True}).encode())

        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"scenario": SCENARIO}).encode())


def main():
    server = HTTPServer(("0.0.0.0", PORT), CallbackHandler)
    print(f"[callback] Mock callback server listening on port {PORT}, scenario: {SCENARIO}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
