import pytest
from nanofaas.sdk import context, decorator, logging as sdk_logging
import logging
import json
import io

def test_context_management():
    context.set_context("exec-123", "trace-456")
    assert context.get_execution_id() == "exec-123"
    assert context.get_trace_id() == "trace-456"

def test_decorator_registration():
    @decorator.nanofaas_function
    def my_handler(payload):
        return {"ok": True}
    
    assert decorator.get_registered_handler() == my_handler
    assert my_handler({"test": 1}) == {"ok": True}

def test_json_logging(capsys):
    # Setup logging to capture output
    log_capture = io.StringIO()
    handler = logging.StreamHandler(log_capture)
    handler.setFormatter(sdk_logging.JsonFormatter())
    logger = logging.getLogger("test_logger")
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    
    context.set_context("exec-789", "trace-000")
    logger.info("Test message")
    
    output = log_capture.getvalue()
    log_data = json.loads(output)
    
    assert log_data["message"] == "Test message"
    assert log_data["execution_id"] == "exec-789"
    assert log_data["trace_id"] == "trace-000"
    assert "timestamp" in log_data
    assert log_data["level"] == "INFO"
