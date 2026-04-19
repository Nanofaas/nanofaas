"""Docstring coverage tests for the nanofaas Python SDK."""

# ── decorator ────────────────────────────────────────────────────────────────

def test_decorator_module_docstring() -> None:
    import nanofaas.sdk.decorator as m
    assert m.__doc__ and m.__doc__.strip(), "Missing docstring for decorator module"

def test_nanofaas_function_decorator_docstring() -> None:
    from nanofaas.sdk.decorator import nanofaas_function
    assert nanofaas_function.__doc__ and nanofaas_function.__doc__.strip(), "Missing docstring for nanofaas_function"

def test_get_registered_handler_docstring() -> None:
    from nanofaas.sdk.decorator import get_registered_handler
    assert get_registered_handler.__doc__ and get_registered_handler.__doc__.strip(), "Missing docstring for get_registered_handler"

# ── context ──────────────────────────────────────────────────────────────────

def test_context_module_docstring() -> None:
    import nanofaas.sdk.context as m
    assert m.__doc__ and m.__doc__.strip(), "Missing docstring for context module"

def test_get_execution_id_docstring() -> None:
    from nanofaas.sdk.context import get_execution_id
    assert get_execution_id.__doc__ and get_execution_id.__doc__.strip(), "Missing docstring for get_execution_id"

def test_get_trace_id_docstring() -> None:
    from nanofaas.sdk.context import get_trace_id
    assert get_trace_id.__doc__ and get_trace_id.__doc__.strip(), "Missing docstring for get_trace_id"

def test_set_context_docstring() -> None:
    from nanofaas.sdk.context import set_context
    assert set_context.__doc__ and set_context.__doc__.strip(), "Missing docstring for set_context"

def test_get_logger_docstring() -> None:
    from nanofaas.sdk.context import get_logger
    assert get_logger.__doc__ and get_logger.__doc__.strip(), "Missing docstring for get_logger"

# ── logging ──────────────────────────────────────────────────────────────────

def test_logging_module_docstring() -> None:
    import nanofaas.sdk.logging as m
    assert m.__doc__ and m.__doc__.strip(), "Missing docstring for logging module"

def test_json_formatter_class_docstring() -> None:
    from nanofaas.sdk.logging import JsonFormatter
    assert JsonFormatter.__doc__ and JsonFormatter.__doc__.strip(), "Missing docstring for JsonFormatter"

def test_json_formatter_format_docstring() -> None:
    from nanofaas.sdk.logging import JsonFormatter
    assert JsonFormatter.format.__doc__ and JsonFormatter.format.__doc__.strip(), "Missing docstring for JsonFormatter.format"

def test_configure_logging_docstring() -> None:
    from nanofaas.sdk.logging import configure_logging
    assert configure_logging.__doc__ and configure_logging.__doc__.strip(), "Missing docstring for configure_logging"

# ── runtime app ──────────────────────────────────────────────────────────────

def test_app_module_docstring() -> None:
    import nanofaas.runtime.app as m
    assert m.__doc__ and m.__doc__.strip(), "Missing docstring for app module"

def test_app_instance_docstring() -> None:
    from nanofaas.runtime.app import app
    assert app.__doc__ and app.__doc__.strip(), "Missing docstring for app"

def test_lifespan_docstring() -> None:
    from nanofaas.runtime.app import lifespan
    assert lifespan.__doc__ and lifespan.__doc__.strip(), "Missing docstring for lifespan"

def test_send_callback_docstring() -> None:
    from nanofaas.runtime.app import send_callback
    assert send_callback.__doc__ and send_callback.__doc__.strip(), "Missing docstring for send_callback"

def test_invoke_docstring() -> None:
    from nanofaas.runtime.app import invoke
    assert invoke.__doc__ and invoke.__doc__.strip(), "Missing docstring for invoke"

def test_health_docstring() -> None:
    from nanofaas.runtime.app import health
    assert health.__doc__ and health.__doc__.strip(), "Missing docstring for health"

def test_metrics_docstring() -> None:
    from nanofaas.runtime.app import metrics
    assert metrics.__doc__ and metrics.__doc__.strip(), "Missing docstring for metrics"
