"""Structured JSON logging for the nanofaas Python SDK.

This module provides a :class:`JsonFormatter` that serialises log records as
single-line JSON objects, including the current execution and trace IDs from
:mod:`nanofaas.sdk.context`. A convenience function
:func:`configure_logging` wires this formatter to the root logger so that
every ``logging.getLogger()`` call in the process produces structured output
suitable for log-aggregation pipelines.
"""
import json
import logging
from . import context


class JsonFormatter(logging.Formatter):
    """Log formatter that serialises records as JSON lines.

    Each formatted record is a JSON object containing at minimum:

    - ``timestamp`` — human-readable ISO-style timestamp
    - ``level`` — log level name (e.g. ``"INFO"``)
    - ``logger`` — the logger's name
    - ``message`` — the rendered log message
    - ``execution_id`` — current invocation ID from :mod:`context`, or ``null``
    - ``trace_id`` — current trace ID from :mod:`context`, or ``null``

    If the record carries exception information, an additional ``exception``
    key is appended with the formatted traceback.
    """

    def format(self, record: logging.LogRecord) -> str:
        """Serialise *record* to a JSON string.

        :param record: The log record produced by the logging framework.
        :type record: logging.LogRecord
        :returns: A single-line JSON string representing the log entry.
        :rtype: str
        """
        log_record = {
            "timestamp": self.formatTime(record, self.datefmt),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "execution_id": context.get_execution_id(),
            "trace_id": context.get_trace_id(),
        }
        if record.exc_info:
            log_record["exception"] = self.formatException(record.exc_info)
        return json.dumps(log_record)


def configure_logging(level: int = logging.INFO) -> None:
    """Configure the root logger to emit structured JSON output.

    Removes any pre-existing handlers to avoid duplicate log lines, then
    attaches a single :class:`~logging.StreamHandler` with
    :class:`JsonFormatter`. Subsequent calls to ``logging.getLogger()``
    anywhere in the process will inherit this configuration.

    :param level: Minimum log level for the root logger. Defaults to
        :data:`logging.INFO`.
    :type level: int
    """
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()

    # Remove existing handlers to avoid duplicates
    for h in root.handlers[:]:
        root.removeHandler(h)

    root.setLevel(level)
    root.addHandler(handler)
