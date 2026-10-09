"""Live packaging regression; set NANOFAAS_QR_NATIVE_IMAGE to a built image."""

import base64
import json
import os
import struct
import subprocess
import time
import urllib.error
import urllib.request

import pytest


def test_native_qr_container_returns_png():
    image = os.environ.get("NANOFAAS_QR_NATIVE_IMAGE")
    if not image:
        pytest.skip("set NANOFAAS_QR_NATIVE_IMAGE to run the native packaging regression")

    container = subprocess.check_output(
        ["docker", "run", "--detach", "--publish", "127.0.0.1::8080", image], text=True
    ).strip()
    try:
        inspection = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
        port = inspection["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        url = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(url, timeout=1):
                    break
            except urllib.error.HTTPError:
                break  # Any HTTP response means the SDK is accepting requests.
            except (urllib.error.URLError, OSError):
                if time.monotonic() >= deadline:
                    pytest.fail(subprocess.check_output(["docker", "logs", container], text=True))
                time.sleep(0.2)

        request = urllib.request.Request(
            url + "/invoke",
            data=json.dumps({"input": {"text": "https://example.org/invite/abc", "size": 256}}).encode(),
            headers={"Content-Type": "application/json", "X-Execution-Id": "native-qr-packaging"},
        )
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            pytest.fail(f"native QR returned HTTP {error.code}: {error.read().decode()}")
        with response:
            assert response.status == 200
            assert response.headers["Content-Type"] == "image/png"
            assert response.headers["X-NanoFaaS-Encoding"] == "base64"
            png = base64.b64decode(json.load(response), validate=True)
        assert png[:8] == b"\x89PNG\r\n\x1a\n"
        assert png[12:16] == b"IHDR"
        assert struct.unpack(">II", png[16:24]) == (256, 256)
    except Exception:
        logs = subprocess.run(["docker", "logs", container], capture_output=True, text=True)
        print((logs.stdout + logs.stderr)[:12000])
        raise
    finally:
        subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True)
