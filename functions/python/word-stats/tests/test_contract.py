import json
import importlib.util
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).parents[1] / "handler.py"
SPEC = importlib.util.spec_from_file_location("word_stats_handler", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
handle = MODULE.handle


CASES = json.loads(
    (
        Path(__file__).parents[3] / "test-data" / "word-stats" / "runtime-contract.json"
    ).read_text()
)["cases"]


@pytest.mark.parametrize("contract_case", CASES, ids=lambda case: case["name"])
def test_shared_contract(contract_case):
    assert handle(contract_case["input"]) == contract_case["expected"]


def test_large_integer_top_n_does_not_overflow():
    assert handle({"text": "a b c", "topN": 10**400})["topWords"] == [
        {"word": "a", "count": 1},
        {"word": "b", "count": 1},
        {"word": "c", "count": 1},
    ]
