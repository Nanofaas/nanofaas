from nanofaas.sdk import nanofaas_function, context
from nanofaas.sdk.response import HandlerResponse

logger = context.get_logger(__name__)

_ROMAN_TABLE = [
    (1000, "M"), (900, "CM"), (500, "D"), (400, "CD"),
    (100, "C"),  (90, "XC"), (50, "L"),  (40, "XL"),
    (10, "X"),   (9, "IX"),  (5, "V"),   (4, "IV"),  (1, "I"),
]


def _to_roman(n: int) -> str:
    parts = []
    for value, symbol in _ROMAN_TABLE:
        while n >= value:
            parts.append(symbol)
            n -= value
    return "".join(parts)


@nanofaas_function
def handle(input_data):
    logger.info(f"roman-numeral invoked, executionId={context.get_execution_id()}")

    if not isinstance(input_data, dict):
        return HandlerResponse({"error": "Input must be a JSON object"}, 422)

    if "number" not in input_data:
        return HandlerResponse({"error": "missing required field: number"}, 422)

    n = input_data["number"]
    if not isinstance(n, (int, float)) or isinstance(n, bool):
        return HandlerResponse({"error": "field 'number' must be an integer"}, 422)

    n = int(n)

    if not 1 <= n <= 3999:
        return HandlerResponse({"error": f"number must be between 1 and 3999, got: {n}"}, 422)

    return {"roman": _to_roman(n)}
