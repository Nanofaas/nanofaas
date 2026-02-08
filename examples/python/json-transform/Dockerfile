FROM python:3.11-slim

WORKDIR /app

# Install uv for fast dependency management
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uv/bin/

# Copy SDK and example
COPY function-sdk-python/ /tmp/sdk/
COPY examples/python/json-transform/ /app/

# Install SDK and its dependencies
RUN /uv/bin/uv pip install --system /tmp/sdk/

# Environment configuration
ENV HANDLER_MODULE=handler
ENV PORT=8080
ENV PYTHONPATH=/app

EXPOSE 8080

# Run the FastAPI runtime from the SDK
CMD ["python", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
