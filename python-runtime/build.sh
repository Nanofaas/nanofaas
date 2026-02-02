#!/bin/bash
set -euo pipefail

VERSION=${VERSION:-0.5.0}
IMAGE=${IMAGE:-nanofaas/python-runtime:$VERSION}

cd "$(dirname "$0")"

echo "Building $IMAGE..."
docker build -t "$IMAGE" .

echo ""
echo "Built: $IMAGE"
echo ""
echo "To run locally:"
echo "  docker run -p 8080:8080 -e HANDLER_MODULE=handler -v \$(pwd)/handler.py:/app/handler.py $IMAGE"
