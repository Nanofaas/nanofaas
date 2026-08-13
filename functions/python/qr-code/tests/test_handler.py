import base64
from io import BytesIO

import pytest
from PIL import Image
from nanofaas.sdk.response import HandlerResponse

from handler import handle


def test_returns_base64_png_envelope():
    result = handle({"text": "https://example.org/invite/abc", "size": 256})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 200
    assert result.headers == {"Content-Type": "image/png"}
    assert result.encoding == "base64"
    png = base64.b64decode(result.output)
    assert png.startswith(b"\x89PNG\r\n\x1a\n")
    assert Image.open(BytesIO(png)).size == (256, 256)


@pytest.mark.parametrize("input_data", [{}, {"text": ""}, {"text": 42}, {"text": "x" * 1025}, {"text": "https://example.org", "size": 127}])
def test_rejects_invalid_input(input_data):
    result = handle(input_data)
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422
