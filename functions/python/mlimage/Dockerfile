# Dockerfile used to build the `mlimage` function.
#
# Make sure to run Docker or Podman from the root directory of the NanoFaaS
# project!
FROM docker.io/library/python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Warning: keep the absolute path structure unchanged, as the NanoFaaS Python
# SDK has not been published on PyPI and must be built and imported locally each
# time.
COPY sdks/python /workspace/sdks/python

# Copy project's metadata.
COPY functions/python/mlimage/pyproject.toml functions/python/mlimage/uv.lock /workspace/functions/python/mlimage/

WORKDIR /workspace/functions/python/mlimage

# Install dependencies into the container. Do not modify uv.lock and install
# development dependencies.
#
# This also builds and install the NanoFaaS Python SDK.
RUN uv sync --frozen --no-dev

# Copy application's code.
COPY functions/python/mlimage/ /workspace/functions/python/mlimage/

# Handled executed by the NanoFaaS Python SDK.
ENV HANDLER_MODULE=handler

EXPOSE 8080

CMD ["uv", "run", "-m", "uvicorn", "nanofaas.runtime.app:app", "--host", "0.0.0.0", "--port", "8080"]
