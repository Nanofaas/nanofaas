# Stage 1: install SDK
FROM docker.io/library/python:3.12-alpine AS builder
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/
COPY sdks/python/ /tmp/sdk/
RUN /uv/bin/uv pip install --system --compile-bytecode --target /deps /tmp/sdk/

# Stage 2: minimal runtime
FROM docker.io/library/python:3.12-alpine
LABEL org.opencontainers.image.source=https://github.com/miciav/nanofaas
WORKDIR /app
COPY --from=builder /deps /usr/local/lib/python3.12/site-packages/
COPY functions/python/roman-numeral/ /app/
ENV HANDLER_MODULE=handler PORT=8080 PYTHONPATH=/app
EXPOSE 8080
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
