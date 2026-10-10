"""Explicit console logging configuration; importing the SDK preserves host loggers."""
import json
import logging
import os
from datetime import datetime, timezone
from . import context


class JsonFormatter(logging.Formatter):
    """Emit the shared NanoFaaS logging contract as one UTF-8 JSON line."""
    def __init__(self, service: str | None = None):
        super().__init__()
        self.service = service or os.environ.get("NANOFAAS_LOG_SERVICE") or "function-runtime"

    def format(self, record: logging.LogRecord) -> str:
        """Serialize a record with the currently scoped invocation identifiers."""
        event = {
            "schemaVersion": 1,
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat().replace("+00:00", "Z"),
            "level": {"WARNING": "WARN", "CRITICAL": "FATAL"}.get(record.levelname, record.levelname),
            "service": self.service,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key, value in (("executionId", context.get_execution_id()), ("traceId", context.get_trace_id())):
            if value:
                event[key] = value
        attributes = getattr(record, "attributes", None)
        if attributes:
            event["attributes"] = attributes
        if record.exc_info:
            event["exception"] = self.formatException(record.exc_info)
        try:
            return json.dumps(event, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError):
            # Non-serializable application attributes must not suppress runtime logs.
            event.pop("attributes", None)
            return json.dumps(event, ensure_ascii=False, allow_nan=False)


def configure_logging(level: int = logging.INFO, *, format: str | None = None,
                      service: str | None = None, force: bool = True) -> None:
    """Configure the process explicitly; force=False preserves an existing host logger.

    Format defaults to NANOFAAS_LOG_FORMAT (text). JsonFormatter remains available
    directly for applications that own their handlers.
    """
    root = logging.getLogger()
    if root.handlers and not force:
        return
    selected = format or os.environ.get("NANOFAAS_LOG_FORMAT", "text")
    if selected not in ("json", "text"):
        raise ValueError("NANOFAAS_LOG_FORMAT must be json or text")
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter(service) if selected == "json" else logging.Formatter("%(levelname)s %(name)s %(message)s"))
    for previous in root.handlers[:]:
        root.removeHandler(previous)
    root.setLevel(level)
    root.addHandler(handler)
