FROM docker.io/library/python:3.12-alpine AS builder
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/
COPY sdks/python/ /tmp/sdk/
# Everything is installed here, so pip and its cache never reach the runtime image.
RUN /uv/bin/uv pip install --system --compile-bytecode --target /deps /tmp/sdk/ 'qrcode[pil]'

FROM docker.io/library/python:3.12-alpine
WORKDIR /app
COPY --from=builder /deps /usr/local/lib/python3.12/site-packages/
COPY functions/python/qr-code/ /app/
ENV HANDLER_MODULE=handler PORT=8080 PYTHONPATH=/app
EXPOSE 8080
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
