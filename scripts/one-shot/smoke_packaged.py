#!/usr/bin/env python3
"""Verify packaged Linux startup and HTTP errors without mounting a host socket."""
import argparse
import http.client
import json
import subprocess
import time
import urllib.error
import urllib.request


def docker(*args):
    return subprocess.run(["docker", *args], check=True, text=True,
                          capture_output=True, timeout=120).stdout.strip()


def request(port, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data,
                                 headers={"Content-Type":"application/json"}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read(1048577)
        assert len(data) <= 1048576, "oversized response"
        return response.status, json.loads(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("control_plane_image")
    args = parser.parse_args()
    image = docker("image", "inspect", "--format", "{{.Id}}", args.control_plane_image)
    container = None
    try:
        container = docker("run", "-d", "-p", "127.0.0.1::8080", image,
                           "--server.port=8080", "--management.server.port=8081",
                           "--logging.level.root=WARN", "--nanofaas.offload.one-shot.enabled=false",
                           "--nanofaas.forecasting.enabled=false", "--nanofaas.p2p.enabled=false")
        address = docker("port", container, "8080/tcp")
        assert address.startswith("127.0.0.1:") and "\n" not in address, address
        port = int(address.rsplit(":", 1)[1])
        deadline = time.monotonic() + 60
        while True:
            try:
                status, body = request(port, "GET", "/v1/functions/absent")
                assert status == 404 and body["error"] == "FUNCTION_NOT_FOUND", (status, body)
                break
            except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.RemoteDisconnected):
                if time.monotonic() >= deadline:
                    raise
                assert docker("inspect", "--format", "{{.State.Running}}", container) == "true", "runtime exited"
                time.sleep(.1)
        status, body = request(port, "POST", "/v1/functions", {})
        assert status == 400 and body["error"] == "VALIDATION_ERROR" and body["details"], (status, body)
        print(json.dumps({"controlPlaneImage":image,"status":"PASS","errors":[404,400]}))
    finally:
        if container is not None:
            logs = subprocess.run(["docker","logs",container], text=True, capture_output=True, timeout=30)
            print(logs.stdout, end="")
            print(logs.stderr, end="")
            docker("rm", "-f", container)


if __name__ == "__main__":
    main()
