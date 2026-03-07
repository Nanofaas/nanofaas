from typing import Callable, Any

_registered_handler: Callable[[Any], Any] | None = None


def nanofaas_function(func: Callable[[Any], Any]):
    global _registered_handler
    _registered_handler = func
    return func


def get_registered_handler():
    return _registered_handler
