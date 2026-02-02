#!/usr/bin/env bash
set -euo pipefail

VM_NAME=${VM_NAME:-nanofaas-kind}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-40G}
KIND_CLUSTER=${KIND_CLUSTER:-nanofaas}
KIND_VERSION=${KIND_VERSION:-v0.22.0}
KUBECTL_VERSION=${KUBECTL_VERSION:-v1.29.3}

if ! command -v multipass >/dev/null 2>&1; then
  echo "multipass not found. Install it first." >&2
  exit 1
fi

if ! multipass list | awk '{print $1}' | grep -q "^${VM_NAME}$"; then
  multipass launch --name "$VM_NAME" --cpus "$CPUS" --memory "$MEMORY" --disk "$DISK"
fi

INFO_JSON="$(multipass info "$VM_NAME" --format json 2>/dev/null || true)"
VM_IP=""
if [[ "${INFO_JSON}" == \{* ]]; then
  VM_IP="$(INFO_JSON="$INFO_JSON" python3 - <<'PY' || true
import json, os
raw = os.environ.get("INFO_JSON", "")
data = json.loads(raw) if raw else {}
info = data.get("info", {})
vm = next(iter(info.values()), {})
ips = vm.get("ipv4", []) if isinstance(vm, dict) else []
print(ips[0] if ips else "")
PY
  )"
fi

if [[ -z "${VM_IP}" ]]; then
  VM_IP="$(multipass info "$VM_NAME" | grep -oE '[0-9]+(\\.[0-9]+){3}' | head -n1)"
fi

if [[ -z "${VM_IP}" ]]; then
  echo "Failed to determine VM IP. Check 'multipass info ${VM_NAME}' output." >&2
  exit 1
fi

multipass exec "$VM_NAME" -- bash -lc "sudo apt-get update -y"

multipass exec "$VM_NAME" -- bash -lc "if ! command -v docker >/dev/null; then
  sudo apt-get install -y docker.io;
  sudo usermod -aG docker ubuntu;
fi"

multipass exec "$VM_NAME" -- bash -lc "if ! docker buildx version >/dev/null 2>&1; then
  sudo apt-get install -y docker-buildx;
fi"

multipass exec "$VM_NAME" -- bash -lc "if [ ! -d \"\$HOME/.sdkman\" ]; then
  sudo apt-get install -y curl zip unzip;
  curl -s \"https://get.sdkman.io\" | bash;
fi
source \"\$HOME/.sdkman/bin/sdkman-init.sh\"
if [ ! -d \"\$HOME/.sdkman/candidates/java/current\" ]; then
  CANDIDATE=\$(sdk list java | awk '/21\\./ && /tem/ {print \$NF; exit}');
  if [ -z \"\$CANDIDATE\" ]; then
    CANDIDATE=\$(sdk list java | awk '/21\\./ {print \$NF; exit}');
  fi
  if [ -z \"\$CANDIDATE\" ]; then
    echo \"No Java 21 candidate found via SDKMAN\" >&2;
    exit 1;
  fi
  sdk install java \"\$CANDIDATE\";
  sdk default java \"\$CANDIDATE\";
fi"

multipass exec "$VM_NAME" -- bash -lc "if [ -d \"\$HOME/.sdkman\" ]; then
  sudo tee /etc/profile.d/sdkman-java.sh >/dev/null <<'EOF'
export SDKMAN_DIR=\"/home/ubuntu/.sdkman\"
if [ -s \"\$SDKMAN_DIR/bin/sdkman-init.sh\" ]; then
  . \"\$SDKMAN_DIR/bin/sdkman-init.sh\"
fi
if [ -d \"\$SDKMAN_DIR/candidates/java/current\" ]; then
  export JAVA_HOME=\"\$SDKMAN_DIR/candidates/java/current\"
  export PATH=\"\$JAVA_HOME/bin:\$PATH\"
fi
EOF
fi"

multipass exec "$VM_NAME" -- bash -lc "ARCH=\$(uname -m);
case \"\$ARCH\" in
  x86_64|amd64) KIND_ARCH=amd64; KUBECTL_ARCH=amd64 ;;
  aarch64|arm64) KIND_ARCH=arm64; KUBECTL_ARCH=arm64 ;;
  *) echo \"Unsupported arch: \$ARCH\" >&2; exit 1 ;;
esac

echo \"Detected arch: \$ARCH (kind: \$KIND_ARCH, kubectl: \$KUBECTL_ARCH)\"

if command -v kind >/dev/null 2>&1; then
  if ! kind version >/dev/null 2>&1; then
    sudo rm -f /usr/local/bin/kind;
  fi
fi

if ! command -v kind >/dev/null 2>&1; then
  curl -sSL -o /tmp/kind https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-linux-\${KIND_ARCH};
  chmod +x /tmp/kind;
  sudo mv /tmp/kind /usr/local/bin/kind;
fi

if command -v kubectl >/dev/null 2>&1; then
  if ! kubectl version --client --short >/dev/null 2>&1; then
    sudo rm -f /usr/local/bin/kubectl;
  fi
fi

if ! command -v kubectl >/dev/null 2>&1; then
  curl -sSL -o /tmp/kubectl https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/\${KUBECTL_ARCH}/kubectl;
  chmod +x /tmp/kubectl;
  sudo mv /tmp/kubectl /usr/local/bin/kubectl;
fi"

multipass exec "$VM_NAME" -- bash -lc "if ! kind get clusters | grep -q '^${KIND_CLUSTER}$'; then
  cat >/tmp/kind-config.yaml <<CONF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  apiServerAddress: \"0.0.0.0\"
  apiServerPort: 6443
nodes:
  - role: control-plane
kubeadmConfigPatches:
  - |
    kind: ClusterConfiguration
    apiServer:
      certSANs:
        - \"${VM_IP}\"
        - \"127.0.0.1\"
        - \"0.0.0.0\"
CONF
  kind create cluster --name ${KIND_CLUSTER} --config /tmp/kind-config.yaml;
fi"

mkdir -p "$HOME/.kube"
KCFG="$HOME/.kube/${KIND_CLUSTER}-kind.yaml"

multipass exec "$VM_NAME" -- bash -lc "kubectl config view --raw" | \
  sed -E "s#server: https://(127\\.0\\.0\\.1|0\\.0\\.0\\.0|localhost):6443#server: https://${VM_IP}:6443#g" > "$KCFG"

echo "KUBECONFIG written to $KCFG"
