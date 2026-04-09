"""Handler registration for the nanofaas Python SDK.

This module exposes the :func:`nanofaas_function` decorator, which registers a
single callable as the active function handler for the current runtime
instance. Only one handler can be active at a time; re-applying the decorator
overwrites the previous registration.
"""
from typing import Callable, Any

_registered_handler: Callable[[Any], Any] | None = None


def nanofaas_function(func: Callable[[Any], Any]) -> Callable[[Any], Any]:
    """Register *func* as the nanofaas function handler.

    Applying this decorator to a callable makes it the target of all
    ``/invoke`` requests received by the runtime. The function must accept
    a single positional argument (the invocation input) and return a
    JSON-serialisable value. Both synchronous and ``async`` callables are
    supported.

    :param func: The handler callable to register.
    :type func: Callable[[Any], Any]
    :returns: The original *func* unchanged (decorator pass-through).
    :rtype: Callable[[Any], Any]

    Example::

        @nanofaas_function
        def handle(input):
            return {"echo": input}
    """
    global _registered_handler
    _registered_handler = func
    return func


def get_registered_handler() -> Callable[[Any], Any] | None:
    """Return the currently registered handler, or ``None`` if not set.

    :returns: The registered handler callable, or ``None``.
    :rtype: Callable[[Any], Any] | None
    """
    return _registered_handler
