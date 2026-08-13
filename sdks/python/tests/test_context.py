def test_headers_default_empty_dict():
    from nanofaas.sdk import context
    assert context.get_headers() == {}


def test_set_and_get_headers():
    from nanofaas.sdk import context
    context.set_headers({"authorization": "Bearer x"})
    assert context.get_headers()["authorization"] == "Bearer x"


def test_set_headers_none_yields_empty_dict():
    from nanofaas.sdk import context
    context.set_headers(None)
    assert context.get_headers() == {}
