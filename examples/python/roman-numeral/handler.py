from nanofaas.sdk import nanofaas_function, context

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

    if "number" not in input_data:
        return {"error": "missing required field: number"}

    try:
        n = int(input_data["number"])
    except (TypeError, ValueError):
        return {"error": "field 'number' must be an integer"}

    if not 1 <= n <= 3999:
        return {"error": f"number must be between 1 and 3999, got: {n}"}

    return {"roman": _to_roman(n)}
