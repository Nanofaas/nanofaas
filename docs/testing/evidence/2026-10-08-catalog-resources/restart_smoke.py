"""Run against a JVM JAR or native binary: python3 restart_smoke.py ARTIFACT."""
import json
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from pathlib import Path

artifact = Path(sys.argv[1]).resolve()
command = ["java", "-jar", str(artifact)] if artifact.suffix == ".jar" else [str(artifact)]
resources = {"requests": {"cpu": 0.05, "memoryMiB": 128},
             "limits": {"cpu": 1, "memoryMiB": 256}}

with tempfile.TemporaryDirectory(prefix="nanofaas-catalog-smoke-") as temporary:
    directory = Path(temporary)
    catalog = directory / "functions.json"
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"

    def request(method, path, payload=None):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode()

    @contextmanager
    def running(label):
        log = directory / (label + ".log")
        with log.open("w") as output:
            process = subprocess.Popen(command + [f"--server.port={port}",
                                       "--management.server.port=0",
                                       f"--nanofaas.registry.path={catalog}"],
                                       stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError(log.read_text())
                    try:
                        status, _ = request("GET", "/v1/functions")
                        if status == 200:
                            break
                    except (OSError, TimeoutError):
                        pass
                    time.sleep(0.1)
                else:
                    raise AssertionError("Startup timed out:\n" + log.read_text())
                yield
            finally:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    def verify_invocation():
        status, function = request("GET", "/v1/functions/catalog-resource-probe")
        assert status == 200, (status, function)
        assert function["resources"] == resources, function
        status, invocation = request("POST", "/v1/functions/catalog-resource-probe:invoke",
                                     {"input": {"probe": "restart"}})
        assert status == 200, (status, invocation)
        assert invocation["status"] == "success", invocation
        assert invocation["output"] == {"probe": "restart"}, invocation

    definition = {"name": "catalog-resource-probe", "image": "local:probe",
                  "executionMode": "LOCAL", "resources": resources}
    with running("first-start"):
        status, body = request("POST", "/v1/functions", definition)
        assert status == 201, (status, body)
        verify_invocation()
        invalid = dict(definition, name="invalid-resources",
                       resources={"requests": {"cpu": 2, "memoryMiB": 512},
                                  "limits": {"cpu": 1, "memoryMiB": 256},
                                  "requestWithinLimit": True})
        status, body = request("POST", "/v1/functions", invalid)
        assert status == 400, (status, body)
        unknown = dict(definition, name="unknown-resources", resources={"limit": {}})
        status, body = request("POST", "/v1/functions", unknown)
        assert status == 400, (status, body)
    snapshot = json.loads(catalog.read_text())
    assert "requestWithinLimit" not in snapshot["functions"][0]["spec"]["resources"]
    with running("restart"):
        verify_invocation()
    snapshot["functions"][0]["spec"]["resources"]["requestWithinLimit"] = False
    catalog.write_text(json.dumps(snapshot))
    with running("legacy-restart"):
        verify_invocation()
    print(json.dumps({"artifact": str(artifact), "registration": 201,
                      "invocation_before_restart": 200, "invocation_after_restart": 200,
                      "legacy_catalog_invocation": 200, "invalid_resources": 400,
                      "unknown_resource_field": 400, "resource_settings_preserved": True}))
