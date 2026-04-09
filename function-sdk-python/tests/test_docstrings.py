"""Docstring coverage tests for the nanofaas Python SDK."""

# ── decorator ────────────────────────────────────────────────────────────────

def test_decorator_module_docstring():
    import nanofaas.sdk.decorator as m
    assert m.__doc__ and m.__doc__.strip()

def test_nanofaas_function_decorator_docstring():
    from nanofaas.sdk.decorator import nanofaas_function
    assert nanofaas_function.__doc__ and nanofaas_function.__doc__.strip()

def test_get_registered_handler_docstring():
    from nanofaas.sdk.decorator import get_registered_handler
    assert get_registered_handler.__doc__ and get_registered_handler.__doc__.strip()

# ── context ──────────────────────────────────────────────────────────────────

def test_context_module_docstring():
    import nanofaas.sdk.context as m
    assert m.__doc__ and m.__doc__.strip()

def test_get_execution_id_docstring():
    from nanofaas.sdk.context import get_execution_id
    assert get_execution_id.__doc__ and get_execution_id.__doc__.strip()

def test_get_trace_id_docstring():
    from nanofaas.sdk.context import get_trace_id
    assert get_trace_id.__doc__ and get_trace_id.__doc__.strip()

def test_set_context_docstring():
    from nanofaas.sdk.context import set_context
    assert set_context.__doc__ and set_context.__doc__.strip()

def test_get_logger_docstring():
    from nanofaas.sdk.context import get_logger
    assert get_logger.__doc__ and get_logger.__doc__.strip()

# ── logging ──────────────────────────────────────────────────────────────────

def test_logging_module_docstring():
    import nanofaas.sdk.logging as m
    assert m.__doc__ and m.__doc__.strip()

def test_json_formatter_class_docstring():
    from nanofaas.sdk.logging import JsonFormatter
    assert JsonFormatter.__doc__ and JsonFormatter.__doc__.strip()

def test_json_formatter_format_docstring():
    from nanofaas.sdk.logging import JsonFormatter
    assert JsonFormatter.format.__doc__ and JsonFormatter.format.__doc__.strip()

def test_configure_logging_docstring():
    from nanofaas.sdk.logging import configure_logging
    assert configure_logging.__doc__ and configure_logging.__doc__.strip()

# ── runtime app ──────────────────────────────────────────────────────────────

def test_app_module_docstring():
    import nanofaas.runtime.app as m
    assert m.__doc__ and m.__doc__.strip()

def test_app_instance_docstring():
    from nanofaas.runtime.app import app
    assert app.__doc__ and app.__doc__.strip()

def test_lifespan_docstring():
    from nanofaas.runtime.app import lifespan
    assert lifespan.__doc__ and lifespan.__doc__.strip()

def test_send_callback_docstring():
    from nanofaas.runtime.app import send_callback
    assert send_callback.__doc__ and send_callback.__doc__.strip()

def test_invoke_docstring():
    from nanofaas.runtime.app import invoke
    assert invoke.__doc__ and invoke.__doc__.strip()

def test_health_docstring():
    from nanofaas.runtime.app import health
    assert health.__doc__ and health.__doc__.strip()

def test_metrics_docstring():
    from nanofaas.runtime.app import metrics
    assert metrics.__doc__ and metrics.__doc__.strip()
