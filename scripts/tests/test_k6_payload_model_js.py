import shutil
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
JS_TEST = REPO_ROOT / "k6" / "tests" / "payload-model.test.mjs"


def test_k6_payload_model_js_unit_tests_pass():
    node = shutil.which("node")
    if node is None:
        raise AssertionError("node is required to run k6 payload unit tests")

    proc = subprocess.run(
        [node, "--test", str(JS_TEST)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0, proc.stdout + "\n" + proc.stderr
