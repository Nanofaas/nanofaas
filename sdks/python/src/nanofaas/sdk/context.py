"""Per-invocation context storage for the nanofaas Python SDK.

Execution metadata (execution ID, trace ID) is stored in :mod:`contextvars`
so that each concurrent request carries its own isolated copy, even when
multiple coroutines are in flight on the same event-loop thread.
"""
import contextvars
import logging

_execution_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "execution_id", default=None
)
_trace_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "trace_id", default=None
)


def get_execution_id() -> str | None:
    """Return the execution ID for the current invocation context.

    :returns: The execution ID string, or ``None`` if not set.
    :rtype: str | None
    """
    return _execution_id.get()


def get_trace_id() -> str | None:
    """Return the trace ID for the current invocation context.

    :returns: The trace ID string, or ``None`` if not set.
    :rtype: str | None
    """
    return _trace_id.get()


def set_context(execution_id: str | None, trace_id: str | None) -> None:
    """Populate the invocation context for the current async task.

    Should be called once per request, before the handler is invoked, so
    that downstream log statements and helper calls can read the correct IDs
    without requiring explicit parameter passing.

    :param execution_id: Unique identifier for the current execution.
    :type execution_id: str | None
    :param trace_id: Distributed-tracing identifier propagated from the
        control-plane via the ``X-Trace-Id`` header.
    :type trace_id: str | None
    """
    _execution_id.set(execution_id)
    _trace_id.set(trace_id)


_headers: contextvars.ContextVar[dict[str, str] | None] = contextvars.ContextVar(
    "headers", default=None
)


def get_headers() -> dict[str, str]:
    """Return the incoming request headers for the current invocation context.

    :returns: A mapping of header name to value; empty if none were set.
    :rtype: dict[str, str]
    """
    return _headers.get() or {}


def set_headers(headers: dict[str, str] | None) -> None:
    """Populate the request headers for the current invocation context.

    :param headers: Filtered request headers (hop-by-hop and dedicated control
        headers already excluded by the caller).
    :type headers: dict[str, str] | None
    """
    _headers.set(headers)


def get_logger(name: str) -> logging.Logger:
    """Return a standard :class:`logging.Logger` by name.

    The returned logger inherits the JSON handler installed by
    :func:`nanofaas.sdk.logging.configure_logging` and automatically
    enriches records with the current execution and trace IDs.

    :param name: Logger name, typically ``__name__`` of the calling module.
    :type name: str
    :returns: A configured :class:`logging.Logger` instance.
    :rtype: logging.Logger
    """
    return logging.getLogger(name)
