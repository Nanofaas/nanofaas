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
