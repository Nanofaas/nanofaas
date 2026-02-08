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
def word_stats_client():
    # Manually reset registry
    decorator._registered_handler = None
    
    path = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../examples/python/word-stats/handler.py'))
    print(f"DEBUG: loading handler from {path}")
    
    spec = importlib.util.spec_from_file_location("handler_word_stats", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    
    return TestClient(app)

def test_word_stats_example(word_stats_client):
    payload = {
        "input": {
            "text": "Hello world! Hello nanoFaaS.",
            "topN": 2
        }
    }
    response = word_stats_client.post("/invoke", 
                         json=payload,
                         headers={"X-Execution-Id": "test-word-stats"})
    
    assert response.status_code == 200
    data = response.json()
    assert data["wordCount"] == 4
    assert data["uniqueWords"] == 3
