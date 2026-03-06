import asyncio
import functools
from typing import Callable, Any

_registered_handler: Callable[[Any], Any] | None = None


def nanofaas_function(func: Callable[[Any], Any]):
    global _registered_handler

    if asyncio.iscoroutinefunction(func):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            return await func(*args, **kwargs)
    else:
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

    _registered_handler = wrapper
    return wrapper


def get_registered_handler():
    return _registered_handler
