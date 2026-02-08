import contextvars
import logging

_execution_id = contextvars.ContextVar("execution_id", default=None)
_trace_id = contextvars.ContextVar("trace_id", default=None)

def get_execution_id() -> str | None:
    return _execution_id.get()

def get_trace_id() -> str | None:
    return _trace_id.get()

def set_context(execution_id: str | None, trace_id: str | None):
    _execution_id.set(execution_id)
    _trace_id.set(trace_id)

def get_logger(name: str):
    return logging.getLogger(name)
