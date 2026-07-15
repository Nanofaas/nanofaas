import json
import importlib.util
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).parents[1] / "handler.py"
SPEC = importlib.util.spec_from_file_location("json_transform_handler", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
handle = MODULE.handle


CASES = json.loads(
    (Path(__file__).parents[3] / "contract-tests" / "json-transform.json").read_text()
)["cases"]


@pytest.mark.parametrize("contract_case", CASES, ids=lambda case: case["name"])
def test_shared_contract(contract_case):
    assert handle(contract_case["input"]) == contract_case["expected"]
