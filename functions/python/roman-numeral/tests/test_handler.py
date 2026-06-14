import pytest
from unittest.mock import patch
from handler import handle, _to_roman


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
    assert _invoke({"number": 42}) == {"roman": "XLII"}


def test_handle_missing_field():
    result = _invoke({})
    assert result == {"error": "missing required field: number"}


def test_handle_out_of_range_high():
    result = _invoke({"number": 4000})
    assert "error" in result
    assert "3999" in result["error"]


def test_handle_out_of_range_zero():
    result = _invoke({"number": 0})
    assert "error" in result


def test_handle_non_integer():
    result = _invoke({"number": "abc"})
    assert "error" in result
