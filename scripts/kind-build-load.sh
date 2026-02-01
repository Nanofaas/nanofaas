#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-mcfaas-kind}
KIND_CLUSTER=${KIND_CLUSTER:-mcfaas}
REMOTE_DIR=${REMOTE_DIR:-/home/ubuntu/mcFaas}

if ! command -v multipass >/dev/null 2>&1; then
  echo "multipass not found. Install it first." >&2
  exit 1
fi

if ! multipass list | awk '{print $1}' | grep -q "^${VM_NAME}$"; then
  echo "VM ${VM_NAME} not found. Run scripts/setup-multipass-kind.sh first." >&2
  exit 1
fi

multipass exec "$VM_NAME" -- bash -lc "rm -rf ${REMOTE_DIR}"

multipass transfer --recursive . "$VM_NAME":"$REMOTE_DIR"

multipass exec "$VM_NAME" -- bash -lc "if ! command -v java >/dev/null 2>&1; then
  echo \"Java not found in VM. Re-run scripts/setup-multipass-kind.sh to install SDKMAN + Java 17.\" >&2;
  exit 1;
fi
cd ${REMOTE_DIR} && ./gradlew :control-plane:bootJar :function-runtime:bootJar"

multipass exec "$VM_NAME" -- bash -lc "BUILD_CMD='docker build';
if docker buildx version >/dev/null 2>&1; then
  BUILD_CMD='docker buildx build --load';
fi
cd ${REMOTE_DIR} && \$BUILD_CMD -t mcfaas/control-plane:0.5.0 control-plane/"
multipass exec "$VM_NAME" -- bash -lc "BUILD_CMD='docker build';
if docker buildx version >/dev/null 2>&1; then
  BUILD_CMD='docker buildx build --load';
fi
cd ${REMOTE_DIR} && \$BUILD_CMD -t mcfaas/function-runtime:0.5.0 function-runtime/"

multipass exec "$VM_NAME" -- bash -lc "kind load docker-image mcfaas/control-plane:0.5.0 --name ${KIND_CLUSTER}"
multipass exec "$VM_NAME" -- bash -lc "kind load docker-image mcfaas/function-runtime:0.5.0 --name ${KIND_CLUSTER}"
