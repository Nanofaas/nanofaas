#!/usr/bin/env bash
set -euo pipefail

# Configuration with defaults
VM_NAME=${VM_NAME:-nanofaas-cli-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
LOCAL_REGISTRY=${LOCAL_REGISTRY:-localhost:5000}
CONTROL_IMAGE=${CONTROL_PLANE_IMAGE:-${LOCAL_REGISTRY}/nanofaas/control-plane:e2e}
RUNTIME_IMAGE=${FUNCTION_RUNTIME_IMAGE:-${LOCAL_REGISTRY}/nanofaas/function-runtime:e2e}
KEEP_VM=${KEEP_VM:-false}
CONTROL_IMAGE_REPOSITORY=${CONTROL_IMAGE%:*}
CONTROL_IMAGE_TAG=${CONTROL_IMAGE##*:}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/e2e-k3s-common.sh"
e2e_set_log_prefix "cli-e2e"
e2e_test_init

pass() {
    e2e_pass "$1"
}

fail() {
    e2e_fail "$1 - $2"
}

# assert_exit_zero: run a command and expect exit 0
assert_exit_zero() {
    local label="$1"; shift
    if "$@" >/dev/null 2>&1; then
        pass "${label}"
    else
        fail "${label}" "expected exit 0, got $?"
    fi
}

# assert_exit_nonzero: run a command and expect exit != 0
assert_exit_nonzero() {
    local label="$1"; shift
    if "$@" >/dev/null 2>&1; then
        fail "${label}" "expected non-zero exit, got 0"
    else
        pass "${label}"
    fi
}

cleanup() {
    local exit_code=$?
    e2e_cleanup_vm
    exit "${exit_code}"
}

trap cleanup EXIT

check_prerequisites() {
    e2e_require_multipass
    log "Prerequisites check passed"
}

create_vm() {
    log "Creating VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"

    # Get VM IP for SSH-based execution (multipass exec hangs in background processes)
    VM_IP=$(multipass info "${VM_NAME}" --format json | python3 -c "import sys,json; d=json.load(sys.stdin); print(list(d['info'].values())[0]['ipv4'][0])")
    log "VM created successfully (IP: ${VM_IP})"

    # Setup SSH access: add local key to VM authorized_keys
    if [[ -f "${HOME}/.ssh/id_rsa.pub" ]]; then
        cat "${HOME}/.ssh/id_rsa.pub" | multipass exec "${VM_NAME}" -- bash -c "cat >> /home/ubuntu/.ssh/authorized_keys"
    elif [[ -f "${HOME}/.ssh/id_ed25519.pub" ]]; then
        cat "${HOME}/.ssh/id_ed25519.pub" | multipass exec "${VM_NAME}" -- bash -c "cat >> /home/ubuntu/.ssh/authorized_keys"
    else
        error "No SSH public key found (~/.ssh/id_rsa.pub or id_ed25519.pub)"
        exit 1
    fi

    # Verify SSH connectivity
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -o ConnectTimeout=10 "ubuntu@${VM_IP}" "echo SSH OK" >/dev/null 2>&1
    log "SSH access configured"
}

# NOTE: uses SSH-based vm_exec — see create_vm() for SSH setup.
# multipass exec hangs for background CLI processes (known multipass bug).
vm_exec() {
    # Inclusion of NANOFAAS_ENDPOINT and NANOFAAS_NAMESPACE if they are set
    local env_vars=""
    if [[ -n "${NANOFAAS_ENDPOINT:-}" ]]; then env_vars+="export NANOFAAS_ENDPOINT=${NANOFAAS_ENDPOINT}; "; fi
    if [[ -n "${NANOFAAS_NAMESPACE:-}" ]]; then env_vars+="export NANOFAAS_NAMESPACE=${NANOFAAS_NAMESPACE}; "; fi

    # Path to CLI binary
    local cli_path="/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin"

    # Use SSH instead of multipass exec — multipass exec hangs at 100% CPU
    # when run from background/non-TTY processes (known multipass bug)
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
        -o ConnectTimeout=10 "ubuntu@${VM_IP}" \
        "export KUBECONFIG=/home/ubuntu/.kube/config; export PATH=\$PATH:${cli_path}; ${env_vars} $*"
}

install_dependencies() {
    log "Installing dependencies in VM..."

    vm_exec "sudo apt-get update -y"
    vm_exec "sudo apt-get install -y curl ca-certificates tar unzip openjdk-21-jdk"

    # Install Docker
    vm_exec "if ! command -v docker >/dev/null 2>&1; then
        curl -fsSL https://get.docker.com | sudo sh
        sudo usermod -aG docker ubuntu
    fi"

    # Install Helm for platform lifecycle tests
    vm_exec 'if ! command -v helm >/dev/null 2>&1; then
        curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
    fi'

    log "Dependencies installed"
}

install_k3s() {
    e2e_install_k3s
}

sync_project() {
    log "Syncing project to VM..."
    vm_exec "rm -rf /home/ubuntu/nanofaas"
    multipass transfer --recursive "${PROJECT_ROOT}" "${VM_NAME}:/home/ubuntu/nanofaas"
    log "Project synced"
}

build_jars() {
    log "Building JARs in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :control-plane:bootJar :function-runtime:bootJar --no-daemon -q"
    log "JARs built"
}

build_images() {
    log "Building Docker images in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${CONTROL_IMAGE} -f control-plane/Dockerfile control-plane/"
    vm_exec "cd /home/ubuntu/nanofaas && sudo docker build -t ${RUNTIME_IMAGE} -f function-runtime/Dockerfile function-runtime/"
    log "Docker images built"
}

push_images_to_registry() {
    e2e_push_images_to_registry "${CONTROL_IMAGE}" "${RUNTIME_IMAGE}"
}

create_namespace() {
    log "Creating namespace ${NAMESPACE}..."
    vm_exec "kubectl create namespace ${NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -"

    # Grant the default service account RBAC permissions so the control-plane
    # can create/manage Deployments, Services, and HPA in the namespace
    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: nanofaas-control-plane
  namespace: ${NAMESPACE}
rules:
- apiGroups: [\"\", \"apps\", \"autoscaling\"]
  resources: [\"deployments\", \"services\", \"pods\", \"horizontalpodautoscalers\", \"pods/log\"]
  verbs: [\"*\"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: nanofaas-control-plane
  namespace: ${NAMESPACE}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: nanofaas-control-plane
subjects:
- kind: ServiceAccount
  name: default
  namespace: ${NAMESPACE}
EOF"

    log "Namespace and RBAC created"
}

deploy_control_plane() {
    log "Deploying control-plane..."

    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane
  namespace: ${NAMESPACE}
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
        image: ${CONTROL_IMAGE}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${NAMESPACE}\"
        - name: SYNC_QUEUE_ADMISSION_ENABLED
          value: \"false\"
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
  namespace: ${NAMESPACE}
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

    log "Control-plane deployment created"
}

deploy_function_runtime() {
    log "Deploying function-runtime..."

    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: function-runtime
  namespace: ${NAMESPACE}
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
        image: ${RUNTIME_IMAGE}
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
  namespace: ${NAMESPACE}
spec:
  selector:
    app: function-runtime
  ports:
  - name: http
    port: 8080
    targetPort: 8080
EOF"

    log "Function-runtime deployment created"
}

deploy_echo_deployment_mode() {
    log "Deploying echo-deploy function as DEPLOYMENT mode..."

    # Create a Deployment + Service with the function label for k8s commands
    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fn-echo-deploy
  namespace: ${NAMESPACE}
  labels:
    function: echo-deploy
spec:
  replicas: 1
  selector:
    matchLabels:
      function: echo-deploy
  template:
    metadata:
      labels:
        function: echo-deploy
    spec:
      containers:
      - name: function
        image: ${RUNTIME_IMAGE}
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: fn-echo-deploy
  namespace: ${NAMESPACE}
spec:
  selector:
    function: echo-deploy
  ports:
  - port: 8080
    targetPort: 8080
EOF"

    wait_for_deployment "fn-echo-deploy" 120

    # Register function with control-plane pointing to this deployment
    local svc_ip
    svc_ip=$(vm_exec "kubectl get svc fn-echo-deploy -n ${NAMESPACE} -o jsonpath='{.spec.clusterIP}'")

    vm_exec "cat <<EOF > /tmp/echo-deploy.json
{
    \"name\": \"echo-deploy\",
    \"image\": \"${RUNTIME_IMAGE}\",
    \"timeoutMs\": 5000,
    \"concurrency\": 2,
    \"queueSize\": 20,
    \"maxRetries\": 3,
    \"executionMode\": \"DEPLOYMENT\",
    \"endpointUrl\": \"http://${svc_ip}:8080/invoke\"
}
EOF"

    vm_exec "nanofaas fn apply -f /tmp/echo-deploy.json"
    log "  echo-deploy function registered: OK"
}

wait_for_deployment() {
    local name=$1
    local timeout=${2:-180}

    log "Waiting for deployment ${name} to be ready (timeout: ${timeout}s)..."

    vm_exec "kubectl rollout status deployment/${name} -n ${NAMESPACE} --timeout=${timeout}s"

    log "Deployment ${name} is ready"
}

build_cli() {
    log "Building CLI in VM..."
    vm_exec "cd /home/ubuntu/nanofaas && ./gradlew :nanofaas-cli:installDist --no-daemon -q"
    log "CLI built"
}

setup_cli_env() {
    log "Setting up CLI environment..."

    # Add CLI to PATH in .bashrc for subsequent vm_exec calls
    vm_exec "echo 'export PATH=\$PATH:/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin' >> /home/ubuntu/.bashrc"

    # Get the ClusterIP of the control-plane service
    log "  Retrieving control-plane ClusterIP..."
    local cluster_ip
    cluster_ip=$(vm_exec "kubectl get svc control-plane -n ${NAMESPACE} -o jsonpath='{.spec.clusterIP}'")

    if [[ -z "${cluster_ip}" ]]; then
        error "Failed to retrieve control-plane ClusterIP"
        exit 1
    fi

    # Set environment variables to point to ClusterIP
    export NANOFAAS_ENDPOINT="http://${cluster_ip}:8080"
    export NANOFAAS_NAMESPACE="${NAMESPACE}"

    log "CLI environment configured (Endpoint: ${NANOFAAS_ENDPOINT})"
}

# ─── Health & Infrastructure Verification ────────────────────────────────────

verify_health_endpoints() {
    log "Verifying control-plane health endpoints..."

    local pod_name
    pod_name=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].metadata.name}'")

    local health
    health=$(vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health")
    if echo "${health}" | grep -q '"status":"UP"'; then
        pass "health /actuator/health = UP"
    else
        fail "health /actuator/health" "status not UP: ${health}"
    fi

    local liveness
    liveness=$(vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/liveness")
    if echo "${liveness}" | grep -q '"status":"UP"'; then
        pass "health /liveness = UP"
    else
        fail "health /liveness" "status not UP: ${liveness}"
    fi

    local readiness
    readiness=$(vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/readiness")
    if echo "${readiness}" | grep -q '"status":"UP"'; then
        pass "health /readiness = UP"
    else
        fail "health /readiness" "status not UP: ${readiness}"
    fi
}

verify_pods_running() {
    log "Verifying all pods are running..."

    local cp_status
    cp_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].status.phase}'")
    if [[ "${cp_status}" == "Running" ]]; then
        pass "control-plane pod Running"
    else
        fail "control-plane pod Running" "status: ${cp_status}"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=control-plane" || true
    fi

    local fr_status
    fr_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=function-runtime -o jsonpath='{.items[0].status.phase}'")
    if [[ "${fr_status}" == "Running" ]]; then
        pass "function-runtime pod Running"
    else
        fail "function-runtime pod Running" "status: ${fr_status}"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=function-runtime" || true
    fi
}

# ─── CLI Config & Help Tests ─────────────────────────────────────────────────

test_cli_config() {
    log "Testing CLI config..."

    vm_exec "ls -l /home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin/nanofaas" >/dev/null
    pass "CLI binary exists"

    local help_out
    help_out=$(vm_exec "nanofaas --help")
    if echo "${help_out}" | grep -q "Usage: nanofaas"; then
        pass "CLI --help shows usage"
    else
        fail "CLI --help shows usage" "Output: ${help_out}"
    fi
}

test_cli_endpoint_flag() {
    log "Testing --endpoint flag override..."
    local output
    output=$(vm_exec "nanofaas --endpoint ${NANOFAAS_ENDPOINT} fn list" 2>&1) || true
    # The command should succeed (endpoint is valid)
    if vm_exec "nanofaas --endpoint ${NANOFAAS_ENDPOINT} fn list" >/dev/null 2>&1; then
        pass "--endpoint flag override"
    else
        fail "--endpoint flag override" "command failed with explicit --endpoint"
    fi
}

# ─── Function CRUD Tests ─────────────────────────────────────────────────────

test_cli_fn_list_initial() {
    log "Testing 'nanofaas fn list' (initial, should be empty)..."
    local output
    output=$(vm_exec "nanofaas fn list")
    if [[ -z "${output}" ]]; then
        pass "fn list initial is empty"
    else
        # Not empty is also ok if there are pre-existing functions
        pass "fn list initial returns output"
    fi
}

test_cli_fn_apply() {
    log "Testing 'nanofaas fn apply'..."

    # Create a temporary function spec file in the VM
    vm_exec "cat <<EOF > /tmp/echo-test.json
{
    \"name\": \"echo-test\",
    \"image\": \"${RUNTIME_IMAGE}\",
    \"timeoutMs\": 5000,
    \"concurrency\": 2,
    \"queueSize\": 20,
    \"maxRetries\": 3,
    \"executionMode\": \"POOL\",
    \"endpointUrl\": \"http://function-runtime:8080/invoke\"
}
EOF"

    log "  Applying function spec..."
    local apply_out
    if apply_out=$(vm_exec "nanofaas fn apply -f /tmp/echo-test.json 2>&1"); then
        pass "fn apply echo-test"
    else
        fail "fn apply echo-test" "Output: ${apply_out}"
        vm_exec "kubectl logs -l app=control-plane -n ${NAMESPACE} --tail=50" || true
        return 1
    fi

    log "  Waiting for system to stabilize..."
    sleep 10
}

test_cli_fn_list_after_apply() {
    log "Testing 'nanofaas fn list' contains echo-test..."
    local output
    output=$(vm_exec "nanofaas fn list")
    if echo "${output}" | grep -q "echo-test"; then
        pass "fn list contains echo-test"
    else
        fail "fn list contains echo-test" "Output: ${output}"
    fi

    # Verify tab-separated format: name\timage
    if echo "${output}" | grep -q "echo-test.*${RUNTIME_IMAGE}"; then
        pass "fn list output format (name + image)"
    else
        fail "fn list output format (name + image)" "Expected tab-separated name and image. Output: ${output}"
    fi
}

test_cli_fn_get() {
    log "Testing 'nanofaas fn get echo-test'..."
    local output
    output=$(vm_exec "nanofaas fn get echo-test")
    if echo "${output}" | grep -q "echo-test"; then
        pass "fn get echo-test"
    else
        fail "fn get echo-test" "Output: ${output}"
    fi
}

test_cli_fn_get_nonexistent() {
    log "Testing 'nanofaas fn get nonexistent' fails..."
    if vm_exec "nanofaas fn get this-function-does-not-exist" >/dev/null 2>&1; then
        fail "fn get nonexistent exits non-zero" "expected failure but got exit 0"
    else
        pass "fn get nonexistent exits non-zero"
    fi
}

test_cli_fn_apply_idempotent() {
    log "Testing 'nanofaas fn apply' idempotent (same spec again)..."
    if vm_exec "nanofaas fn apply -f /tmp/echo-test.json" >/dev/null 2>&1; then
        pass "fn apply idempotent (same spec)"
    else
        fail "fn apply idempotent (same spec)" "apply of same spec failed"
    fi
}

test_cli_fn_apply_invalid_file() {
    log "Testing 'nanofaas fn apply -f /nonexistent' fails..."
    if vm_exec "nanofaas fn apply -f /tmp/nonexistent-file.yaml" >/dev/null 2>&1; then
        fail "fn apply invalid file exits non-zero" "expected failure but got exit 0"
    else
        pass "fn apply invalid file exits non-zero"
    fi
}

test_cli_fn_delete() {
    log "Testing 'nanofaas fn delete echo-test'..."
    if vm_exec "nanofaas fn delete echo-test" >/dev/null 2>&1; then
        pass "fn delete echo-test"
    else
        fail "fn delete echo-test" "delete command failed"
    fi
}

test_cli_fn_delete_nonexistent() {
    log "Testing 'nanofaas fn delete nonexistent' is idempotent..."
    if vm_exec "nanofaas fn delete this-function-does-not-exist" >/dev/null 2>&1; then
        pass "fn delete nonexistent is idempotent (exit 0)"
    else
        fail "fn delete nonexistent is idempotent (exit 0)" "expected exit 0 for idempotent delete"
    fi
}

test_cli_fn_list_empty() {
    log "Testing 'nanofaas fn list' is empty after delete..."
    local output
    output=$(vm_exec "nanofaas fn list")
    if [[ -z "${output}" ]]; then
        pass "fn list empty after delete"
    else
        # May contain echo-deploy, that's ok — check echo-test is gone
        if echo "${output}" | grep -q "echo-test"; then
            fail "fn list empty after delete" "echo-test still present: ${output}"
        else
            pass "fn list does not contain echo-test after delete"
        fi
    fi
}

# ─── Invocation Tests ────────────────────────────────────────────────────────

test_cli_invoke() {
    log "Testing 'nanofaas invoke' (sync)..."
    local response
    response=$(vm_exec "nanofaas invoke echo-test -d '{\"input\": {\"message\": \"hello-from-cli\"}}'")
    if echo "${response}" | grep -q '"status":"success"'; then
        pass "invoke sync status=success"
    else
        fail "invoke sync status=success" "Response: ${response}"
    fi
    if echo "${response}" | grep -q '"message":"hello-from-cli"'; then
        pass "invoke sync echoes message"
    else
        fail "invoke sync echoes message" "Response: ${response}"
    fi
}

test_cli_invoke_with_file_data() {
    log "Testing 'nanofaas invoke -d @file'..."

    # Create input file on VM
    vm_exec "echo '{\"input\": {\"message\": \"from-file\"}}' > /tmp/invoke-input.json"

    local response
    response=$(vm_exec "nanofaas invoke echo-test -d @/tmp/invoke-input.json")
    if echo "${response}" | grep -q '"message":"from-file"'; then
        pass "invoke with @file data"
    else
        fail "invoke with @file data" "Response: ${response}"
    fi
}

test_cli_invoke_with_headers() {
    log "Testing 'nanofaas invoke' with optional headers..."
    local response
    response=$(vm_exec "nanofaas invoke echo-test -d '{\"input\": {\"message\": \"headers-test\"}}' --timeout-ms 10000 --idempotency-key e2e-idem-1 --trace-id e2e-trace-1")
    if echo "${response}" | grep -q '"status":"success"'; then
        pass "invoke with --timeout-ms --idempotency-key --trace-id"
    else
        fail "invoke with --timeout-ms --idempotency-key --trace-id" "Response: ${response}"
    fi
}

test_cli_invoke_invalid_json() {
    log "Testing 'nanofaas invoke' with invalid JSON fails..."
    if vm_exec "nanofaas invoke echo-test -d '<<<not json>>>'" >/dev/null 2>&1; then
        fail "invoke invalid JSON exits non-zero" "expected failure but got exit 0"
    else
        pass "invoke invalid JSON exits non-zero"
    fi
}

test_cli_invoke_nonexistent_function() {
    log "Testing 'nanofaas invoke nonexistent' fails..."
    if vm_exec "nanofaas invoke this-function-does-not-exist -d '{\"x\":1}'" >/dev/null 2>&1; then
        fail "invoke nonexistent function exits non-zero" "expected failure but got exit 0"
    else
        pass "invoke nonexistent function exits non-zero"
    fi
}

# ─── Async Enqueue Tests ─────────────────────────────────────────────────────

test_cli_enqueue() {
    log "Testing 'nanofaas enqueue'..."
    local response
    response=$(vm_exec "nanofaas enqueue echo-test -d '{\"input\": {\"message\": \"async-hello\"}}'")
    if echo "${response}" | grep -q "executionId"; then
        pass "enqueue returns executionId"
    else
        fail "enqueue returns executionId" "Response: ${response}"
    fi

    # Extract execution ID for exec get test
    LAST_EXEC_ID=$(echo "${response}" | grep -o '"executionId":"[^"]*"' | head -1 | cut -d'"' -f4)
    export LAST_EXEC_ID
    log "  Captured execution ID: ${LAST_EXEC_ID}"
}

test_cli_enqueue_with_headers() {
    log "Testing 'nanofaas enqueue' with optional headers..."
    local response
    response=$(vm_exec "nanofaas enqueue echo-test -d '{\"input\": {\"x\": 1}}' --idempotency-key e2e-async-idem --trace-id e2e-async-trace")
    if echo "${response}" | grep -q "executionId"; then
        pass "enqueue with --idempotency-key --trace-id"
    else
        fail "enqueue with --idempotency-key --trace-id" "Response: ${response}"
    fi
}

# ─── Execution Status Tests ──────────────────────────────────────────────────

test_cli_exec_get() {
    log "Testing 'nanofaas exec get'..."
    if [[ -z "${LAST_EXEC_ID:-}" ]]; then
        warn "  Skipping exec get: no execution ID from enqueue"
        return
    fi

    # Wait a bit for the execution to complete
    sleep 5

    local output
    output=$(vm_exec "nanofaas exec get ${LAST_EXEC_ID}")
    if echo "${output}" | grep -q "executionId"; then
        pass "exec get returns execution data"
    else
        fail "exec get returns execution data" "Output: ${output}"
    fi

    # Verify the executionId matches
    if echo "${output}" | grep -q "${LAST_EXEC_ID}"; then
        pass "exec get executionId matches"
    else
        fail "exec get executionId matches" "Expected ${LAST_EXEC_ID} in: ${output}"
    fi
}

test_cli_exec_get_watch() {
    log "Testing 'nanofaas exec get --watch'..."

    # Enqueue a fresh invocation to get a new execution ID
    local response
    response=$(vm_exec "nanofaas enqueue echo-test -d '{\"input\": {\"message\": \"watch-test\"}}'")
    local exec_id
    exec_id=$(echo "${response}" | grep -o '"executionId":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -z "${exec_id}" ]]; then
        fail "exec get --watch (setup)" "failed to get executionId from enqueue"
        return
    fi

    # Watch with short interval and timeout — should reach terminal state
    local watch_output
    if watch_output=$(vm_exec "nanofaas exec get ${exec_id} --watch --interval PT1S --timeout PT30S" 2>&1); then
        # Should contain the terminal status
        if echo "${watch_output}" | grep -qE '"status":"(success|error|timeout)"'; then
            pass "exec get --watch reaches terminal state"
        else
            fail "exec get --watch reaches terminal state" "Output: ${watch_output}"
        fi
    else
        fail "exec get --watch" "command failed: ${watch_output}"
    fi
}

# ─── Kubernetes Helper Tests ─────────────────────────────────────────────────

test_cli_k8s_pods() {
    log "Testing 'nanofaas k8s pods echo-deploy'..."
    local output
    output=$(vm_exec "nanofaas k8s pods echo-deploy")
    if echo "${output}" | grep -q "fn-echo-deploy"; then
        pass "k8s pods lists echo-deploy pod"
    else
        fail "k8s pods lists echo-deploy pod" "Output: ${output}"
    fi

    # Verify output contains Running phase
    if echo "${output}" | grep -q "Running"; then
        pass "k8s pods shows Running phase"
    else
        fail "k8s pods shows Running phase" "Output: ${output}"
    fi
}

test_cli_k8s_pods_nonexistent() {
    log "Testing 'nanofaas k8s pods nonexistent' produces empty output..."
    local output
    output=$(vm_exec "nanofaas k8s pods this-function-does-not-exist" 2>&1) || true
    if [[ -z "${output}" ]] || ! echo "${output}" | grep -q "fn-"; then
        pass "k8s pods nonexistent produces empty/no-pod output"
    else
        fail "k8s pods nonexistent produces empty/no-pod output" "Output: ${output}"
    fi
}

test_cli_k8s_describe() {
    log "Testing 'nanofaas k8s describe echo-deploy'..."
    local output
    output=$(vm_exec "nanofaas k8s describe echo-deploy")
    if echo "${output}" | grep -q "deployment.*present"; then
        pass "k8s describe shows deployment present"
    else
        fail "k8s describe shows deployment present" "Output: ${output}"
    fi

    if echo "${output}" | grep -q "service.*present"; then
        pass "k8s describe shows service present"
    else
        fail "k8s describe shows service present" "Output: ${output}"
    fi

    if echo "${output}" | grep -q "hpa.*missing"; then
        pass "k8s describe shows hpa missing"
    else
        fail "k8s describe shows hpa missing" "Output: ${output}"
    fi
}

test_cli_k8s_describe_nonexistent() {
    log "Testing 'nanofaas k8s describe nonexistent' shows all missing..."
    local output
    output=$(vm_exec "nanofaas k8s describe nonexistent-fn")

    local all_missing=true
    for resource in deployment service hpa; do
        if ! echo "${output}" | grep -q "${resource}.*missing"; then
            all_missing=false
            break
        fi
    done

    if [[ "${all_missing}" == "true" ]]; then
        pass "k8s describe nonexistent shows all missing"
    else
        fail "k8s describe nonexistent shows all missing" "Output: ${output}"
    fi
}

test_cli_k8s_logs() {
    log "Testing 'nanofaas k8s logs echo-deploy'..."
    local output
    if output=$(vm_exec "nanofaas k8s logs echo-deploy" 2>&1); then
        # Should produce some output (at least startup logs)
        if [[ -n "${output}" ]]; then
            pass "k8s logs echo-deploy returns output"
        else
            # Empty logs is also valid — the container may not have logged yet
            pass "k8s logs echo-deploy (empty, but no error)"
        fi
    else
        fail "k8s logs echo-deploy" "command failed: ${output}"
    fi
}

test_cli_k8s_logs_nonexistent() {
    log "Testing 'nanofaas k8s logs nonexistent' fails (no pods)..."
    if vm_exec "nanofaas k8s logs this-function-does-not-exist" >/dev/null 2>&1; then
        fail "k8s logs nonexistent exits non-zero" "expected failure but got exit 0"
    else
        pass "k8s logs nonexistent exits non-zero"
    fi
}

test_cli_k8s_logs_custom_container() {
    log "Testing 'nanofaas k8s logs echo-deploy --container function'..."
    if output=$(vm_exec "nanofaas k8s logs echo-deploy --container function" 2>&1); then
        pass "k8s logs --container function"
    else
        fail "k8s logs --container function" "command failed: ${output}"
    fi
}

# ─── Platform Lifecycle Tests ────────────────────────────────────────────────

test_cli_platform_lifecycle() {
    log "Testing 'nanofaas platform install/status/uninstall'..."

    local release="nanofaas-platform-e2e"
    local ns="${NAMESPACE}-platform"

    # Ensure clean state for repeatable runs
    vm_exec "helm uninstall ${release} -n ${ns} >/dev/null 2>&1 || true"
    vm_exec "kubectl delete namespace ${ns} --ignore-not-found=true --wait=true >/dev/null 2>&1 || true"

    local install_output
    if install_output=$(vm_exec "nanofaas platform install --release ${release} -n ${ns} \
        --chart /home/ubuntu/nanofaas/helm/nanofaas \
        --control-plane-repository ${CONTROL_IMAGE_REPOSITORY} \
        --control-plane-tag ${CONTROL_IMAGE_TAG} \
        --control-plane-pull-policy Always \
        --demos-enabled=false" 2>&1); then
        pass "platform install succeeds"
    else
        fail "platform install succeeds" "Output: ${install_output}"
        return 1
    fi

    if echo "${install_output}" | grep -qE "endpoint[[:space:]]+http://[0-9.]+:30080"; then
        pass "platform install prints NodePort endpoint"
    else
        fail "platform install prints NodePort endpoint" "Output: ${install_output}"
    fi

    local status_output
    if status_output=$(vm_exec "nanofaas platform status -n ${ns}" 2>&1); then
        pass "platform status succeeds"
    else
        fail "platform status succeeds" "Output: ${status_output}"
        return 1
    fi

    if echo "${status_output}" | grep -q $'deployment\tnanofaas-control-plane\t1/1'; then
        pass "platform status shows deployment ready"
    else
        fail "platform status shows deployment ready" "Output: ${status_output}"
    fi

    if echo "${status_output}" | grep -q $'service\tcontrol-plane\tNodePort'; then
        pass "platform status shows NodePort service"
    else
        fail "platform status shows NodePort service" "Output: ${status_output}"
    fi

    local uninstall_output
    if uninstall_output=$(vm_exec "nanofaas platform uninstall --release ${release} -n ${ns}" 2>&1); then
        pass "platform uninstall succeeds"
    else
        fail "platform uninstall succeeds" "Output: ${uninstall_output}"
        return 1
    fi

    if vm_exec "nanofaas platform status -n ${ns}" >/dev/null 2>&1; then
        fail "platform status fails after uninstall" "expected non-zero exit, got 0"
    else
        pass "platform status fails after uninstall"
    fi

    # Keep cluster tidy before VM cleanup
    vm_exec "kubectl delete namespace ${ns} --ignore-not-found=true --wait=true >/dev/null 2>&1 || true"
}

# ─── Summary ─────────────────────────────────────────────────────────────────

print_summary() {
    log ""
    log "=========================================="
    if [[ ${E2E_FAIL} -eq 0 ]]; then
        log "    CLI E2E: ALL ${E2E_PASS} TESTS PASSED"
    else
        error "    CLI E2E: ${E2E_FAIL} FAILED / ${E2E_PASS} PASSED"
    fi
    log "=========================================="
    log ""
    log "VM: ${VM_NAME} | Namespace: ${NAMESPACE}"
    log ""
    for t in "${E2E_TESTS_RUN[@]}"; do
        if [[ "${t}" == "[PASS]"* ]]; then
            log "  ${t}"
        else
            error "  ${t}"
        fi
    done
    log ""
    log "Total: $((E2E_PASS + E2E_FAIL)) tests, ${E2E_PASS} passed, ${E2E_FAIL} failed"
    log ""

    if [[ ${E2E_FAIL} -gt 0 ]]; then
        exit 1
    fi
}

# ─── Main ────────────────────────────────────────────────────────────────────

main() {
    log "Starting CLI E2E test..."
    log "Configuration:"
    log "  VM_NAME=${VM_NAME}"
    log "  CPUS=${CPUS}, MEMORY=${MEMORY}, DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  LOCAL_REGISTRY=${LOCAL_REGISTRY}"
    log "  KEEP_VM=${KEEP_VM}"
    log ""

    # Phase 1: Setup
    check_prerequisites
    create_vm
    install_dependencies
    install_k3s
    e2e_setup_local_registry "${LOCAL_REGISTRY}"

    # Phase 2: Build Nanofaas components
    sync_project
    build_jars
    build_images
    push_images_to_registry

    # Phase 3: Deploy Nanofaas
    create_namespace
    deploy_control_plane
    deploy_function_runtime

    # Phase 4: Verify deployment
    wait_for_deployment "control-plane" 180
    wait_for_deployment "function-runtime" 120
    verify_pods_running
    verify_health_endpoints

    # Phase 5: Build and Setup CLI
    build_cli
    setup_cli_env

    # Phase 6: CLI config tests
    test_cli_config
    test_cli_endpoint_flag

    # Phase 7: Function CRUD tests
    test_cli_fn_list_initial
    test_cli_fn_apply
    test_cli_fn_list_after_apply
    test_cli_fn_get
    test_cli_fn_get_nonexistent
    test_cli_fn_apply_idempotent
    test_cli_fn_apply_invalid_file

    # Phase 8: Invocation tests (sync)
    test_cli_invoke
    test_cli_invoke_with_file_data
    test_cli_invoke_with_headers
    test_cli_invoke_invalid_json
    test_cli_invoke_nonexistent_function

    # Phase 9: Async invocation tests
    test_cli_enqueue
    test_cli_enqueue_with_headers

    # Phase 10: Execution status tests
    test_cli_exec_get
    test_cli_exec_get_watch

    # Phase 11: K8s resource tests (requires DEPLOYMENT mode function)
    deploy_echo_deployment_mode
    test_cli_k8s_pods
    test_cli_k8s_pods_nonexistent
    test_cli_k8s_describe
    test_cli_k8s_describe_nonexistent
    test_cli_k8s_logs
    test_cli_k8s_logs_nonexistent
    test_cli_k8s_logs_custom_container

    # Phase 12: Platform lifecycle tests (Helm + NodePort)
    test_cli_platform_lifecycle

    # Phase 13: Cleanup & deletion tests
    test_cli_fn_delete
    test_cli_fn_delete_nonexistent
    test_cli_fn_list_empty

    # Done
    print_summary
}

# Run main
main "$@"
