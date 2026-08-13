FROM docker.io/library/python:3.11-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/
COPY sdks/python/ /tmp/sdk/
RUN /uv/bin/uv pip install --system --target /deps /tmp/sdk/
FROM docker.io/library/python:3.11-slim
WORKDIR /app
COPY --from=builder /deps /usr/local/lib/python3.11/site-packages/
COPY functions/python/handler-envelope/ /app/
ENV HANDLER_MODULE=handler PORT=8080 PYTHONPATH=/app
EXPOSE 8080
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
