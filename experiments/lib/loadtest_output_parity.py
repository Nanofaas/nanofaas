from __future__ import annotations

from math import isclose
from numbers import Number


def extract_output(payload):
    if isinstance(payload, dict) and "output" in payload:
        return payload["output"]
    return payload


def semantically_equal(left, right, tolerance: float = 1e-9) -> bool:
    if isinstance(left, Number) and isinstance(right, Number):
        return isclose(float(left), float(right), rel_tol=tolerance, abs_tol=tolerance)

    if isinstance(left, dict) and isinstance(right, dict):
        if set(left.keys()) != set(right.keys()):
            return False
        return all(semantically_equal(left[k], right[k], tolerance) for k in left.keys())

    if isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return False
        return all(semantically_equal(a, b, tolerance) for a, b in zip(left, right))

    return left == right


def normalize_case_output(case_name: str | None, output):
    if case_name != "word-stats" or not isinstance(output, dict):
        return output

    top_words = output.get("topWords")
    if not isinstance(top_words, list):
        return output
    if not all(isinstance(item, dict) and "word" in item for item in top_words):
        return output

    normalized = dict(output)
    normalized["topWords"] = sorted(
        top_words,
        key=lambda item: (
            str(item.get("word", "")),
            item.get("count", 0),
        ),
    )
    return normalized


def compare_case_outputs(
    outputs_by_function: list[tuple[str, object]],
    tolerance: float = 1e-9,
    case_name: str | None = None,
):
    if not outputs_by_function:
        return []

    baseline_fn, baseline_output = outputs_by_function[0]
    baseline_output = normalize_case_output(case_name, baseline_output)
    mismatches = []
    for function_name, function_output in outputs_by_function[1:]:
        function_output = normalize_case_output(case_name, function_output)
        if not semantically_equal(baseline_output, function_output, tolerance):
            mismatches.append((function_name, baseline_fn, baseline_output, function_output))
    return mismatches
