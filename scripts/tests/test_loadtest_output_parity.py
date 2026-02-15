from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))

from loadtest_output_parity import compare_case_outputs, extract_output, semantically_equal


def test_extract_output_prefers_output_field():
    payload = {"executionId": "x", "output": {"ok": True}}
    assert extract_output(payload) == {"ok": True}


def test_semantically_equal_accepts_numeric_equivalence():
    assert semantically_equal({"count": 5}, {"count": 5.0})


def test_semantically_equal_accepts_different_key_order():
    left = {"a": 1, "b": {"x": 2, "y": [1, 2]}}
    right = {"b": {"y": [1, 2], "x": 2}, "a": 1}
    assert semantically_equal(left, right)


def test_compare_case_outputs_detects_mismatch():
    outputs = [
        ("word-stats-java", {"wordCount": 10}),
        ("word-stats-python", {"wordCount": 9}),
    ]
    mismatches = compare_case_outputs(outputs)
    assert len(mismatches) == 1
    assert mismatches[0][0] == "word-stats-python"
