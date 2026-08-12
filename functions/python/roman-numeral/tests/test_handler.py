import pytest
import json
from pathlib import Path
from unittest.mock import patch
from handler import handle, _to_roman
from nanofaas.sdk.response import HandlerResponse


KNOWN_VALUES = [
    (1,    "I"),
    (4,    "IV"),
    (5,    "V"),
    (9,    "IX"),
    (10,   "X"),
    (14,   "XIV"),
    (40,   "XL"),
    (42,   "XLII"),
    (90,   "XC"),
    (100,  "C"),
    (400,  "CD"),
    (500,  "D"),
    (900,  "CM"),
    (1000, "M"),
    (1994, "MCMXCIV"),
    (2024, "MMXXIV"),
    (3999, "MMMCMXCIX"),
]


@pytest.mark.parametrize("number,expected", KNOWN_VALUES)
def test_to_roman_known_values(number, expected):
    assert _to_roman(number) == expected


def _invoke(payload):
    with patch("nanofaas.sdk.context.get_execution_id", return_value="test-id"):
        return handle(payload)


def test_handle_valid_number():
    result = _invoke({"number": 42})
    assert result == {"roman": "XLII"}  # unchanged: plain value, implicit 200


def test_handle_missing_field():
    result = _invoke({})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422
    assert result.output == {"error": "missing required field: number"}


def test_handle_out_of_range_high():
    result = _invoke({"number": 4000})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422
    assert "3999" in result.output["error"]


def test_handle_out_of_range_zero():
    result = _invoke({"number": 0})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422


def test_handle_non_integer():
    result = _invoke({"number": "abc"})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422


SHARED_CASES = json.loads(
    (Path(__file__).parents[3] / "test-data" / "roman-numeral" / "correctness.json").read_text()
)["cases"]


@pytest.mark.parametrize("contract_case", SHARED_CASES, ids=lambda case: case["name"])
def test_shared_contract(contract_case):
    result = _invoke(contract_case["input"])
    actual = result.output if isinstance(result, HandlerResponse) else result
    assert actual == contract_case["expected"]
    if isinstance(result, HandlerResponse):
        assert result.status_code == contract_case.get("expectedStatusCode", 200)
