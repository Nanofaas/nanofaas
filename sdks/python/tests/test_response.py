from nanofaas.sdk.response import HandlerResponse


def test_handler_response_defaults():
    r = HandlerResponse({"error": "not found"}, 404)
    assert r.output == {"error": "not found"}
    assert r.status_code == 404
    assert r.headers == {}
    assert r.encoding is None


def test_handler_response_with_headers_and_encoding():
    r = HandlerResponse(b"binary", 200, headers={"Content-Type": "application/pdf"}, encoding="base64")
    assert r.headers["Content-Type"] == "application/pdf"
    assert r.encoding == "base64"
