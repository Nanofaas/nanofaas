"""Optional response envelope for the nanofaas Python SDK.

A handler may return a :class:`HandlerResponse` instead of a plain value to control
the HTTP status code, a safe set of response headers, and whether ``output`` is
base64-encoded binary. Returning a plain value keeps today's behavior (implicit 200,
no extra headers).
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class HandlerResponse:
    output: object
    status_code: int
    headers: dict[str, str] = field(default_factory=dict)
    encoding: str | None = None
