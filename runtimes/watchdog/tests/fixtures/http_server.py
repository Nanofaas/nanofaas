#!/usr/bin/env python3
"""
Mock HTTP server for testing HTTP mode.
Supports various test scenarios based on environment variables.
"""

import json
import os
import sys
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

# Test scenario from environment
SCENARIO = os.environ.get("TEST_SCENARIO", "success")
STARTUP_DELAY = int(os.environ.get("STARTUP_DELAY_MS", "0")) / 1000
INVOKE_DELAY = int(os.environ.get("INVOKE_DELAY_MS", "0")) / 1000
PORT = int(os.environ.get("PORT", "8080"))
PID_FILE = os.environ.get("RUNTIME_PID_FILE")


class TestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress default logging
        pass

    def do_GET(self):
        """Health check endpoint"""
        if self.path == "/health":
            if SCENARIO == "slow_startup":
                # Already past startup delay if we got here
                pass
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok"}).encode())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        """Invoke endpoint"""
        if self.path != "/invoke":
            self.send_response(404)
            self.end_headers()
            return

        # Read request body
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            payload = json.loads(body) if body else {}
        except json.JSONDecodeError:
            payload = {}

        # Handle test scenarios
        if SCENARIO == "success":
            time.sleep(INVOKE_DELAY)
            response = {"result": payload.get("input", "").upper() if isinstance(payload.get("input"), str) else payload}
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

        elif SCENARIO == "echo":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(payload).encode())

        elif SCENARIO == "error_500":
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "Internal server error"}).encode())

        elif SCENARIO == "error_400":
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "Bad request"}).encode())

        elif SCENARIO == "slow_invoke":
            time.sleep(INVOKE_DELAY)
            response = {"result": "slow"}
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

        elif SCENARIO == "hang":
            # Hang forever (for timeout testing)
            time.sleep(3600)

        elif SCENARIO == "invalid_json":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"not valid json {{{")

        elif SCENARIO == "large_response":
            response = {"data": "x" * 1000000}  # 1MB response
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

        elif SCENARIO == "crash_on_invoke":
            os._exit(1)

        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"scenario": SCENARIO}).encode())


def main():
    if PID_FILE:
        with open(PID_FILE, "w", encoding="utf-8") as pid_file:
            pid_file.write(str(os.getpid()))

    # Startup delay simulation
    if STARTUP_DELAY > 0:
        print(f"Simulating startup delay of {STARTUP_DELAY}s", file=sys.stderr)
        time.sleep(STARTUP_DELAY)

    if SCENARIO == "crash_on_startup":
        print("Crashing on startup!", file=sys.stderr)
        sys.exit(1)

    if SCENARIO == "hang_on_startup":
        print("Hanging on startup...", file=sys.stderr)
        time.sleep(3600)

    server = HTTPServer(("0.0.0.0", PORT), TestHandler)
    print(f"Mock HTTP server listening on port {PORT}, scenario: {SCENARIO}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
