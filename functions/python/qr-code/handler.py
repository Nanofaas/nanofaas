import base64
from io import BytesIO

import qrcode
from nanofaas.sdk import nanofaas_function
from nanofaas.sdk.response import HandlerResponse


def _error(message):
    return HandlerResponse({"error": message}, 422)


@nanofaas_function
def handle(input_data):
    if not isinstance(input_data, dict):
        return _error("Input must be a JSON object")
    if "text" not in input_data:
        return _error("missing required field: text")
    text = input_data["text"]
    if not isinstance(text, str) or not text:
        return _error("field 'text' must be a non-empty string")
    if len(text.encode()) > 1024:
        return _error("field 'text' must be at most 1024 UTF-8 bytes")
    size = input_data.get("size", 256)
    if not isinstance(size, int) or isinstance(size, bool) or not 128 <= size <= 1024:
        return _error("field 'size' must be an integer between 128 and 1024")
    output = BytesIO()
    qrcode.make(text).resize((size, size)).save(output, format="PNG")
    return HandlerResponse(base64.b64encode(output.getvalue()).decode(), 200, {"Content-Type": "image/png"}, "base64")
