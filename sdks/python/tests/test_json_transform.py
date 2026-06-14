import pytest
from fastapi.testclient import TestClient
import os
import sys
import importlib.util
from nanofaas.sdk import decorator

# Add necessary paths
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '../src')))

from nanofaas.runtime.app import app

@pytest.fixture
def json_transform_client():
    # Manually reset registry
    decorator._registered_handler = None
    
    path = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../examples/python/json-transform/handler.py'))
    print(f"DEBUG: loading handler from {path}")
    
    spec = importlib.util.spec_from_file_location("handler_json_transform", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    
    return TestClient(app)

def test_json_transform_example(json_transform_client):
    payload = {
        "input": {
            "data": [
                {"dept": "eng", "salary": 100},
                {"dept": "eng", "salary": 200},
                {"dept": "sales", "salary": 150}
            ],
            "groupBy": "dept",
            "operation": "avg",
            "valueField": "salary"
        }
    }
    response = json_transform_client.post("/invoke", 
                         json=payload,
                         headers={"X-Execution-Id": "test-json-transform"})
    
    assert response.status_code == 200
    data = response.json()
    assert data["groupBy"] == "dept"
    assert data["groups"]["eng"] == 150
