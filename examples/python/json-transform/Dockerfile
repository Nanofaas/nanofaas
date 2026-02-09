# Stage 1: Install dependencies
FROM python:3.11-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/
COPY function-sdk-python/ /tmp/sdk/
RUN /uv/bin/uv pip install --system --target /deps /tmp/sdk/

# Stage 2: Minimal runtime
FROM python:3.11-slim
LABEL org.opencontainers.image.source=https://github.com/miciav/nanofaas
WORKDIR /app
COPY --from=builder /deps /usr/local/lib/python3.11/site-packages/
COPY examples/python/json-transform/ /app/
ENV HANDLER_MODULE=handler PORT=8080 PYTHONPATH=/app
EXPOSE 8080
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
