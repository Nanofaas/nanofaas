import pytest
from fastapi.testclient import TestClient
import os
import sys
from unittest.mock import patch, MagicMock

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

from nanofaas.runtime.app import app
from nanofaas.sdk import decorator

@pytest.fixture
def client():
    return TestClient(app)

def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_invoke_without_handler(client):
    # Reset decorator registry for this test
    with patch('nanofaas.sdk.decorator._registered_handler', None):
        response = client.post("/invoke", 
                             json={"input": "test"},
                             headers={"X-Execution-Id": "exec-1"})
        assert response.status_code == 500
        assert "No function registered" in response.json()["detail"]

def test_invoke_success(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {"echo": input_data}
    
    response = client.post("/invoke", 
                         json={"input": "hello"},
                         headers={"X-Execution-Id": "exec-123", "X-Trace-Id": "trace-456"})
    
    assert response.status_code == 200
    assert response.json() == {"echo": "hello"}

def test_invoke_missing_execution_id(client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return {}
        
    response = client.post("/invoke", json={"input": "test"})
    assert response.status_code == 400
    assert "Execution ID required" in response.json()["detail"]

@patch("requests.post")
def test_callback_triggered(mock_post, client):
    @decorator.nanofaas_function
    def mock_handler(input_data):
        return "done"
    
    # We use TestClient which runs background tasks synchronously by default if not specified otherwise
    # or we might need to wait if it was async. FastAPI TestClient runs them.
    
    response = client.post("/invoke", 
                         json={"input": "test"},
                         headers={
                             "X-Execution-Id": "exec-cb",
                             "X-Callback-Url": "http://control-plane/callbacks"
                         })
    
    assert response.status_code == 200
    
    # Verify callback
    mock_post.assert_called()
    call_args = mock_post.call_args
    assert "http://control-plane/callbacks/exec-cb:complete" in call_args[0][0]
    assert call_args[1]["json"]["success"] is True
    assert call_args[1]["json"]["output"] == "done"
