#!/usr/bin/env bash

# Shared helpers for k3s-based E2E scripts.
# Callers are expected to define:
#   - log/warn/error functions (optional)
#   - vm_exec() for remote command execution where required

e2e_log() {
    if declare -F log >/dev/null 2>&1; then
        log "$@"
    else
        echo "[e2e] $*"
    fi
}

e2e_error() {
    if declare -F error >/dev/null 2>&1; then
        error "$@"
    else
        echo "[e2e] $*" >&2
    fi
}

e2e_require_multipass() {
    if ! command -v multipass >/dev/null 2>&1; then
        e2e_error "multipass not found. Install: brew install multipass"
        return 1
    fi
}

e2e_require_vm_exec() {
    if ! declare -F vm_exec >/dev/null 2>&1; then
        e2e_error "vm_exec function not defined by caller"
        return 1
    fi
}

e2e_create_vm() {
    local vm_name=${1:?vm_name is required}
    local cpus=${2:-4}
    local memory=${3:-8G}
    local disk=${4:-30G}

    if multipass list 2>/dev/null | awk '{print $1}' | grep -q "^${vm_name}$"; then
        e2e_log "VM ${vm_name} already exists, ensuring it is running..."
        multipass start "${vm_name}" >/dev/null 2>&1 || true
        return 0
    fi

    e2e_log "Creating VM ${vm_name} (cpus=${cpus}, memory=${memory}, disk=${disk})..."
    multipass launch --name "${vm_name}" --cpus "${cpus}" --memory "${memory}" --disk "${disk}"
    e2e_log "VM created successfully"
}

e2e_ensure_vm_running() {
    local vm_name=${1:?vm_name is required}
    local cpus=${2:-4}
    local memory=${3:-8G}
    local disk=${4:-30G}

    if multipass info "${vm_name}" &>/dev/null; then
        local state
        state=$(multipass info "${vm_name}" --format csv | tail -1 | cut -d, -f2)
        if [[ "${state}" == "Running" ]]; then
            e2e_log "VM '${vm_name}' already running, reusing..."
            return 0
        fi
        e2e_log "VM '${vm_name}' exists with state '${state}', starting..."
        multipass start "${vm_name}"
        return 0
    fi

    e2e_create_vm "${vm_name}" "${cpus}" "${memory}" "${disk}"
}

e2e_install_vm_dependencies() {
    e2e_require_vm_exec || return 1
    local install_helm=${1:-false}
    local helm_label=""
    if [[ "${install_helm}" == "true" ]]; then
        helm_label=", helm"
    fi

    e2e_log "Installing VM dependencies (docker, jdk21${helm_label})..."
    vm_exec "sudo apt-get update -y"
    vm_exec "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates tar unzip openjdk-21-jdk-headless"
    vm_exec 'if ! command -v docker >/dev/null 2>&1; then
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker ubuntu
    fi'

    if [[ "${install_helm}" == "true" ]]; then
        vm_exec 'if ! command -v helm >/dev/null 2>&1; then
            curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        fi'
    fi
    e2e_log "VM dependencies installed"
}

e2e_sync_project_to_vm() {
    local project_root=${1:?project_root is required}
    local vm_name=${2:?vm_name is required}
    local remote_dir=${3:-/home/ubuntu/nanofaas}
    local sync_tar=/tmp/nanofaas-e2e-sync.tar

    e2e_log "Syncing project to VM (${remote_dir})..."
    local tmp_tar
    tmp_tar=$(mktemp "/tmp/nanofaas-e2e-sync-XXXXXX.tar")
    tar -C "${project_root}" \
        --exclude='.git' \
        --exclude='.gradle' \
        --exclude='.idea' \
        --exclude='.worktrees' \
        --exclude='.DS_Store' \
        --exclude='build' \
        --exclude='*/build' \
        --exclude='target' \
        --exclude='*/target' \
        -cf "${tmp_tar}" .

    if declare -F vm_exec >/dev/null 2>&1; then
        vm_exec "rm -rf ${remote_dir} && mkdir -p ${remote_dir}"
    else
        multipass exec "${vm_name}" -- bash -lc "rm -rf ${remote_dir} && mkdir -p ${remote_dir}"
    fi
    multipass transfer "${tmp_tar}" "${vm_name}:${sync_tar}"
    if declare -F vm_exec >/dev/null 2>&1; then
        vm_exec "tar -xf ${sync_tar} -C ${remote_dir} && rm -f ${sync_tar}"
    else
        multipass exec "${vm_name}" -- bash -lc "tar -xf ${sync_tar} -C ${remote_dir} && rm -f ${sync_tar}"
    fi
    rm -f "${tmp_tar}"
    e2e_log "Project synced"
}

e2e_build_core_jars() {
    e2e_require_vm_exec || return 1
    local remote_dir=${1:-/home/ubuntu/nanofaas}
    local quiet=${2:-true}
    local quiet_flag=""
    if [[ "${quiet}" == "true" ]]; then
        quiet_flag="-q"
    fi

    e2e_log "Cleaning stale core build outputs..."
    vm_exec "cd ${remote_dir} && rm -rf control-plane/build function-runtime/build"

    e2e_log "Building core boot jars..."
    vm_exec "cd ${remote_dir} && ./gradlew :control-plane:bootJar :function-runtime:bootJar --no-daemon --rerun-tasks ${quiet_flag}"
    e2e_log "Core boot jars built"
}

e2e_build_core_images() {
    e2e_require_vm_exec || return 1
    local remote_dir=${1:-/home/ubuntu/nanofaas}
    local control_image=${2:?control_image is required}
    local runtime_image=${3:?runtime_image is required}

    e2e_log "Building core images..."
    vm_exec "cd ${remote_dir} && sudo docker build -t ${control_image} -f control-plane/Dockerfile control-plane/"
    vm_exec "cd ${remote_dir} && sudo docker build -t ${runtime_image} -f function-runtime/Dockerfile function-runtime/"
    e2e_log "Core images built"
}

e2e_create_namespace() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    e2e_log "Creating namespace ${namespace}..."
    vm_exec "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"
    e2e_log "Namespace ready"
}

e2e_deploy_control_plane() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local image=${2:?image is required}
    local pod_namespace=${3:-${namespace}}
    local sync_queue_enabled=${4:-}

    e2e_log "Deploying control-plane in namespace ${namespace}..."
    if [[ -n "${sync_queue_enabled}" ]]; then
        vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane
  namespace: ${namespace}
  labels:
    app: control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app: control-plane
  template:
    metadata:
      labels:
        app: control-plane
    spec:
      containers:
      - name: control-plane
        image: ${image}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${pod_namespace}\"
        - name: SYNC_QUEUE_ENABLED
          value: \"${sync_queue_enabled}\"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8081
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8081
          initialDelaySeconds: 15
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
---
apiVersion: v1
kind: Service
metadata:
  name: control-plane
  namespace: ${namespace}
spec:
  selector:
    app: control-plane
  ports:
  - name: api
    port: 8080
    targetPort: 8080
  - name: management
    port: 8081
    targetPort: 8081
EOF"
    else
        vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane
  namespace: ${namespace}
  labels:
    app: control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app: control-plane
  template:
    metadata:
      labels:
        app: control-plane
    spec:
      containers:
      - name: control-plane
        image: ${image}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${pod_namespace}\"
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8081
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8081
          initialDelaySeconds: 15
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
---
apiVersion: v1
kind: Service
metadata:
  name: control-plane
  namespace: ${namespace}
spec:
  selector:
    app: control-plane
  ports:
  - name: api
    port: 8080
    targetPort: 8080
  - name: management
    port: 8081
    targetPort: 8081
EOF"
    fi
    e2e_log "Control-plane deployed"
}

e2e_deploy_function_runtime() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local image=${2:?image is required}

    e2e_log "Deploying function-runtime in namespace ${namespace}..."
    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: function-runtime
  namespace: ${namespace}
  labels:
    app: function-runtime
spec:
  replicas: 1
  selector:
    matchLabels:
      app: function-runtime
  template:
    metadata:
      labels:
        app: function-runtime
    spec:
      containers:
      - name: function-runtime
        image: ${image}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: function-runtime
  namespace: ${namespace}
spec:
  selector:
    app: function-runtime
  ports:
  - name: http
    port: 8080
    targetPort: 8080
EOF"
    e2e_log "Function-runtime deployed"
}

e2e_wait_for_deployment() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local name=${2:?deployment name is required}
    local timeout=${3:-180}

    e2e_log "Waiting for deployment ${name} to be ready (timeout: ${timeout}s)..."
    vm_exec "kubectl rollout status deployment/${name} -n ${namespace} --timeout=${timeout}s"
    e2e_log "Deployment ${name} is ready"
}

e2e_verify_core_pods_running() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}

    e2e_log "Verifying control-plane/function-runtime pods are running..."
    local cp_status
    cp_status=$(vm_exec "kubectl get pods -n ${namespace} -l app=control-plane -o jsonpath='{.items[0].status.phase}'")
    if [[ "${cp_status}" != "Running" ]]; then
        e2e_error "control-plane pod is not Running (status: ${cp_status})"
        vm_exec "kubectl describe pod -n ${namespace} -l app=control-plane"
        return 1
    fi

    local fr_status
    fr_status=$(vm_exec "kubectl get pods -n ${namespace} -l app=function-runtime -o jsonpath='{.items[0].status.phase}'")
    if [[ "${fr_status}" != "Running" ]]; then
        e2e_error "function-runtime pod is not Running (status: ${fr_status})"
        vm_exec "kubectl describe pod -n ${namespace} -l app=function-runtime"
        return 1
    fi
    e2e_log "Core pods running"
}

e2e_verify_control_plane_health() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local pod_name
    pod_name=$(vm_exec "kubectl get pods -n ${namespace} -l app=control-plane -o jsonpath='{.items[0].metadata.name}'")

    e2e_log "Verifying control-plane health endpoints..."
    vm_exec "kubectl exec -n ${namespace} ${pod_name} -- curl -sf http://localhost:8081/actuator/health" | grep -q '"status":"UP"'
    vm_exec "kubectl exec -n ${namespace} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/liveness" | grep -q '"status":"UP"'
    vm_exec "kubectl exec -n ${namespace} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/readiness" | grep -q '"status":"UP"'
    e2e_log "Control-plane health endpoints are UP"
}

e2e_dump_core_pod_logs() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local tail_lines=${2:-50}

    e2e_log "--- control-plane logs (last ${tail_lines} lines) ---"
    vm_exec "kubectl logs -n ${namespace} -l app=control-plane --tail=${tail_lines}" 2>/dev/null || true
    e2e_log "--- function-runtime logs (last ${tail_lines} lines) ---"
    vm_exec "kubectl logs -n ${namespace} -l app=function-runtime --tail=${tail_lines}" 2>/dev/null || true
}

e2e_register_pool_function() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local name=${2:?function name is required}
    local image=${3:?image is required}
    local endpoint_url=${4:?endpoint_url is required}
    local timeout_ms=${5:-5000}
    local concurrency=${6:-2}
    local queue_size=${7:-20}
    local max_retries=${8:-3}
    local runner_name=${9:-curl-register}

    e2e_log "Registering function '${name}'..."
    local payload
    payload="{\"name\":\"${name}\",\"image\":\"${image}\",\"timeoutMs\":${timeout_ms},\"concurrency\":${concurrency},\"queueSize\":${queue_size},\"maxRetries\":${max_retries},\"executionMode\":\"POOL\",\"endpointUrl\":\"${endpoint_url}\"}"
    e2e_kubectl_curl_control_plane "${namespace}" "${runner_name}" "POST" "/v1/functions" "${payload}" "20" >/dev/null
    e2e_log "Function '${name}' registered"
}

e2e_kubectl_curl_control_plane() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local runner_name=${2:?runner_name is required}
    local method=${3:?method is required}
    local path=${4:?path is required}
    local body_json=${5:-}
    local max_time=${6:-35}

    local service_ip
    service_ip=$(vm_exec "kubectl get svc -n ${namespace} control-plane -o jsonpath='{.spec.clusterIP}'")

    if [[ -n "${body_json}" ]]; then
        local body_b64
        body_b64=$(printf '%s' "${body_json}" | base64 | tr -d '\n')
        vm_exec "echo '${body_b64}' | base64 -d | curl -s --max-time ${max_time} -X ${method} http://${service_ip}:8080${path} -H 'Content-Type: application/json' --data-binary @-"
        return
    fi

    vm_exec "curl -s --max-time ${max_time} -X ${method} http://${service_ip}:8080${path}"
}

e2e_extract_json_by_field() {
    local text=${1:-}
    local field=${2:?field is required}
    echo "${text}" | grep "\"${field}\"" | head -1
}

e2e_extract_execution_id() {
    local json_line=${1:-}
    echo "${json_line}" | sed -n 's/.*"executionId":"\([^"]*\)".*/\1/p'
}

e2e_extract_execution_status() {
    local json_line=${1:-}
    echo "${json_line}" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p'
}

e2e_extract_bool_field() {
    local json_line=${1:-}
    local field=${2:?field is required}
    echo "${json_line}" | sed -n "s/.*\"${field}\":\\([a-z]*\\).*/\\1/p"
}

e2e_extract_numeric_field() {
    local json_line=${1:-}
    local field=${2:?field is required}
    echo "${json_line}" | sed -n "s/.*\"${field}\":\\([0-9]*\\).*/\\1/p"
}

e2e_invoke_sync_message() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local function_name=${2:?function_name is required}
    local message=${3:?message is required}
    local runner_name=${4:-curl-invoke}

    local raw
    raw=$(e2e_kubectl_curl_control_plane \
        "${namespace}" \
        "${runner_name}" \
        "POST" \
        "/v1/functions/${function_name}:invoke" \
        "{\"input\": {\"message\": \"${message}\"}}")
    e2e_extract_json_by_field "${raw}" "executionId"
}

e2e_enqueue_message() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local function_name=${2:?function_name is required}
    local message=${3:?message is required}
    local runner_name=${4:-curl-enqueue}

    local raw
    raw=$(e2e_kubectl_curl_control_plane \
        "${namespace}" \
        "${runner_name}" \
        "POST" \
        "/v1/functions/${function_name}:enqueue" \
        "{\"input\": {\"message\": \"${message}\"}}")
    e2e_extract_json_by_field "${raw}" "executionId"
}

e2e_fetch_execution() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local execution_id=${2:?execution_id is required}
    local runner_name=${3:-curl-status}

    local raw
    raw=$(e2e_kubectl_curl_control_plane \
        "${namespace}" \
        "${runner_name}" \
        "GET" \
        "/v1/executions/${execution_id}" \
        "" \
        "10")
    e2e_extract_json_by_field "${raw}" "executionId"
}

e2e_wait_execution_success() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local execution_id=${2:?execution_id is required}
    local attempts=${3:-20}
    local sleep_seconds=${4:-1}
    local runner_prefix=${5:-curl-poll}

    local i
    for i in $(seq 1 "${attempts}"); do
        local json status
        json=$(e2e_fetch_execution "${namespace}" "${execution_id}" "${runner_prefix}-${i}" || true)
        status=$(e2e_extract_execution_status "${json}")
        if [[ "${status}" == "success" ]]; then
            return 0
        fi
        if [[ "${status}" == "failed" ]]; then
            return 1
        fi
        sleep "${sleep_seconds}"
    done
    return 1
}

e2e_enqueue_message_burst() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local function_name=${2:?function_name is required}
    local message_prefix=${3:?message_prefix is required}
    local count=${4:?count is required}
    local runner_prefix=${5:-curl-queue}

    local i
    for i in $(seq 1 "${count}"); do
        e2e_enqueue_message \
            "${namespace}" \
            "${function_name}" \
            "${message_prefix}-${i}" \
            "${runner_prefix}-${i}" >/dev/null
    done
}

e2e_get_control_plane_pod_name() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    vm_exec "kubectl get pods -n ${namespace} -l app=control-plane -o jsonpath='{.items[0].metadata.name}'"
}

e2e_fetch_control_plane_prometheus() {
    e2e_require_vm_exec || return 1
    local namespace=${1:?namespace is required}
    local pod_name
    pod_name=$(e2e_get_control_plane_pod_name "${namespace}")
    vm_exec "kubectl exec -n ${namespace} ${pod_name} -- curl -sf http://localhost:8081/actuator/prometheus"
}

e2e_install_k3s() {
    e2e_require_vm_exec || return 1

    e2e_log "Installing k3s..."
    vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"

    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after 120s' >&2
    exit 1"

    vm_exec "mkdir -p /home/ubuntu/.kube"
    vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
    vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"
    vm_exec "chmod 600 /home/ubuntu/.kube/config"

    e2e_log "k3s installed and ready"
}

e2e_setup_local_registry() {
    e2e_require_vm_exec || return 1

    local registry=${1:-localhost:5000}
    local host=${registry%:*}
    local port=${registry##*:}
    local container_name=${2:-nanofaas-e2e-registry}

    if [[ -z "${host}" || -z "${port}" || "${host}" == "${registry}" ]]; then
        e2e_error "Registry must include host:port (got '${registry}')"
        return 1
    fi

    e2e_log "Starting local registry ${registry}..."
    vm_exec "sudo docker rm -f ${container_name} >/dev/null 2>&1 || true"
    vm_exec "sudo docker run -d --restart unless-stopped --name ${container_name} -p ${port}:5000 registry:2 >/dev/null"

    vm_exec "for i in \$(seq 1 30); do
        if curl -fsS http://${registry}/v2/ >/dev/null 2>&1; then
            echo 'registry ready'
            exit 0
        fi
        sleep 1
    done
    echo 'registry not ready after 30s' >&2
    exit 1"

    e2e_log "Configuring k3s to pull from ${registry}..."
    vm_exec "cat <<EOF | sudo tee /etc/rancher/k3s/registries.yaml >/dev/null
mirrors:
  \"${registry}\":
    endpoint:
      - \"http://${registry}\"
configs:
  \"${registry}\":
    tls:
      insecure_skip_verify: true
EOF"
    vm_exec "sudo systemctl restart k3s"
    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after k3s restart' >&2
    exit 1"

    e2e_log "Local registry configured for k3s"
}

e2e_push_images_to_registry() {
    e2e_require_vm_exec || return 1
    if [[ "$#" -eq 0 ]]; then
        e2e_error "e2e_push_images_to_registry requires at least one image"
        return 1
    fi

    local img
    for img in "$@"; do
        vm_exec "sudo docker push ${img}"
    done
    e2e_log "Pushed images to registry"
}

e2e_import_images_to_k3s() {
    e2e_require_vm_exec || return 1
    if [[ "$#" -eq 0 ]]; then
        e2e_error "e2e_import_images_to_k3s requires at least one image"
        return 1
    fi

    e2e_log "Importing images to k3s..."
    local img
    for img in "$@"; do
        local tarname
        tarname=$(echo "${img}" | tr '/:' '_')
        vm_exec "sudo docker save ${img} -o /tmp/${tarname}.tar"
        vm_exec "sudo k3s ctr images import /tmp/${tarname}.tar"
        vm_exec "sudo rm -f /tmp/${tarname}.tar"
    done
    e2e_log "Images imported to k3s"
}
