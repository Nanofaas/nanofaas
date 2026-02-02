"""Test handler for unit tests"""


def handle(request):
    """Echo handler that uppercases the input"""
    input_value = request.get("input", "") if isinstance(request, dict) else ""
    return {"echo": str(input_value).upper()}
