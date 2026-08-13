import importlib.util
from pathlib import Path

from nanofaas.sdk import context

SPEC = importlib.util.spec_from_file_location("handler_envelope", Path(__file__).parents[1] / "handler.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_returns_body_and_real_header_not_forged_input_header():
    context.set_headers({"x-e2e-token": "header-sentinel"})
    assert MODULE.handle({"message": "body-sentinel", "headers": {"x-e2e-token": "forged"}}) == {
        "body": "body-sentinel", "header": "header-sentinel"
    }
