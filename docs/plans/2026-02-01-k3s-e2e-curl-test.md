# K3s E2E Test with Curl Verification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a comprehensive E2E test that deploys nanofaas to k3s, verifies health/readiness, deploys a function, and uses a curl container to verify function invocation - all in an ephemeral Multipass VM.

**Architecture:** The test uses a self-contained Multipass VM with k3s. The test script builds images locally in the VM, imports them to k3s, deploys control-plane and function-runtime as Kubernetes Deployments with proper health probes, then uses a Kubernetes Job with curl to invoke the function from within the cluster. The VM is deleted on exit.

**Tech Stack:** Bash, Multipass, k3s, kubectl, curl, Docker

---

## Task 1: Create the Enhanced E2E Script Structure

**Files:**
- Create: `scripts/e2e-k3s-curl.sh`

**Step 1: Create the script with header and configuration**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Configuration with defaults
VM_NAME=${VM_NAME:-nanofaas-k3s-e2e-$(date +%s)}
CPUS=${CPUS:-4}
MEMORY=${MEMORY:-8G}
DISK=${DISK:-30G}
NAMESPACE=${NAMESPACE:-nanofaas-e2e}
CONTROL_IMAGE="nanofaas/control-plane:e2e"
RUNTIME_IMAGE="nanofaas/function-runtime:e2e"
KEEP_VM=${KEEP_VM:-false}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() { echo -e "${GREEN}[k3s-e2e]${NC} $*"; }
warn() { echo -e "${YELLOW}[k3s-e2e]${NC} $*"; }
error() { echo -e "${RED}[k3s-e2e]${NC} $*" >&2; }

cleanup() {
    local exit_code=$?
    if [[ "${KEEP_VM}" == "true" ]]; then
        warn "KEEP_VM=true, VM '${VM_NAME}' preserved for debugging"
        warn "SSH: multipass shell ${VM_NAME}"
        warn "Delete: multipass delete ${VM_NAME} && multipass purge"
        return
    fi
    log "Cleaning up VM ${VM_NAME}..."
    multipass delete "${VM_NAME}" 2>/dev/null || true
    multipass purge 2>/dev/null || true
    exit $exit_code
}

trap cleanup EXIT
```

**Step 2: Verify the script is created and executable**

Run:
```bash
chmod +x scripts/e2e-k3s-curl.sh
head -20 scripts/e2e-k3s-curl.sh
```

Expected: Script header visible, no errors

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add k3s curl e2e test script skeleton"
```

---

## Task 2: Add VM Creation and Dependency Installation

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add VM setup functions**

Append to the script after the cleanup function:

```bash
check_prerequisites() {
    if ! command -v multipass >/dev/null 2>&1; then
        error "multipass not found. Install: brew install multipass"
        exit 1
    fi
    log "Prerequisites check passed"
}

create_vm() {
    log "Creating VM ${VM_NAME} (cpus=${CPUS}, memory=${MEMORY}, disk=${DISK})..."
    multipass launch --name "${VM_NAME}" --cpus "${CPUS}" --memory "${MEMORY}" --disk "${DISK}"
    log "VM created successfully"
}

vm_exec() {
    multipass exec "${VM_NAME}" -- bash -lc "$*"
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

    log "Dependencies installed"
}

install_k3s() {
    log "Installing k3s..."

    vm_exec "curl -sfL https://get.k3s.io | sudo sh -s - --disable traefik"

    # Wait for k3s to be ready
    vm_exec "for i in \$(seq 1 60); do
        if sudo k3s kubectl get nodes --no-headers 2>/dev/null | grep -q ' Ready'; then
            echo 'k3s node ready'
            exit 0
        fi
        sleep 2
    done
    echo 'k3s node not ready after 120s' >&2
    exit 1"

    # Setup kubeconfig for ubuntu user
    vm_exec "mkdir -p /home/ubuntu/.kube"
    vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config"
    vm_exec "sudo chown ubuntu:ubuntu /home/ubuntu/.kube/config"
    vm_exec "chmod 600 /home/ubuntu/.kube/config"

    log "k3s installed and ready"
}
```

**Step 2: Verify the functions are added**

Run:
```bash
grep -c "install_k3s\|install_dependencies\|create_vm" scripts/e2e-k3s-curl.sh
```

Expected: Output shows 3 or more matches

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add VM creation and dependency installation"
```

---

## Task 3: Add Build and Image Import Functions

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add build and image functions**

Append to the script:

```bash
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

import_images_to_k3s() {
    log "Importing images to k3s..."
    vm_exec "sudo docker save ${CONTROL_IMAGE} -o /tmp/control-plane.tar"
    vm_exec "sudo docker save ${RUNTIME_IMAGE} -o /tmp/function-runtime.tar"
    vm_exec "sudo k3s ctr images import /tmp/control-plane.tar"
    vm_exec "sudo k3s ctr images import /tmp/function-runtime.tar"
    vm_exec "sudo rm -f /tmp/control-plane.tar /tmp/function-runtime.tar"
    log "Images imported to k3s"
}
```

**Step 2: Verify the functions are added**

Run:
```bash
grep -c "sync_project\|build_jars\|build_images\|import_images" scripts/e2e-k3s-curl.sh
```

Expected: 4 or more matches

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add build and image import functions"
```

---

## Task 4: Add Kubernetes Deployment Functions

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add K8s deployment functions**

Append to the script:

```bash
create_namespace() {
    log "Creating namespace ${NAMESPACE}..."
    vm_exec "kubectl create namespace ${NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -"
    log "Namespace created"
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
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8081
          name: management
        env:
        - name: POD_NAMESPACE
          value: \"${NAMESPACE}\"
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
        imagePullPolicy: Never
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
```

**Step 2: Verify the deployment functions exist**

Run:
```bash
grep -c "deploy_control_plane\|deploy_function_runtime\|create_namespace" scripts/e2e-k3s-curl.sh
```

Expected: 3 or more matches

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add Kubernetes deployment functions with health probes"
```

---

## Task 5: Add Health Verification Functions

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add health check functions**

Append to the script:

```bash
wait_for_deployment() {
    local name=$1
    local timeout=${2:-180}

    log "Waiting for deployment ${name} to be ready (timeout: ${timeout}s)..."

    vm_exec "kubectl rollout status deployment/${name} -n ${NAMESPACE} --timeout=${timeout}s"

    log "Deployment ${name} is ready"
}

verify_health_endpoints() {
    log "Verifying health endpoints..."

    # Get control-plane pod name
    local pod_name
    pod_name=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].metadata.name}'")

    # Check /actuator/health
    log "Checking /actuator/health..."
    vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health" | grep -q '"status":"UP"'
    log "  /actuator/health: UP"

    # Check /actuator/health/liveness
    log "Checking /actuator/health/liveness..."
    vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/liveness" | grep -q '"status":"UP"'
    log "  /actuator/health/liveness: UP"

    # Check /actuator/health/readiness
    log "Checking /actuator/health/readiness..."
    vm_exec "kubectl exec -n ${NAMESPACE} ${pod_name} -- curl -sf http://localhost:8081/actuator/health/readiness" | grep -q '"status":"UP"'
    log "  /actuator/health/readiness: UP"

    log "All health endpoints verified"
}

verify_pods_running() {
    log "Verifying all pods are running..."

    # Check control-plane pod
    local cp_status
    cp_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=control-plane -o jsonpath='{.items[0].status.phase}'")
    if [[ "${cp_status}" != "Running" ]]; then
        error "control-plane pod is not Running (status: ${cp_status})"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=control-plane"
        exit 1
    fi
    log "  control-plane: Running"

    # Check function-runtime pod
    local fr_status
    fr_status=$(vm_exec "kubectl get pods -n ${NAMESPACE} -l app=function-runtime -o jsonpath='{.items[0].status.phase}'")
    if [[ "${fr_status}" != "Running" ]]; then
        error "function-runtime pod is not Running (status: ${fr_status})"
        vm_exec "kubectl describe pod -n ${NAMESPACE} -l app=function-runtime"
        exit 1
    fi
    log "  function-runtime: Running"

    log "All pods running"
}
```

**Step 2: Verify the health functions exist**

Run:
```bash
grep -c "wait_for_deployment\|verify_health_endpoints\|verify_pods_running" scripts/e2e-k3s-curl.sh
```

Expected: 3 or more matches

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add health verification functions"
```

---

## Task 6: Add Function Registration and Curl Verification

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add function test functions**

Append to the script:

```bash
register_function() {
    log "Registering test function..."

    # Register function via control-plane service
    vm_exec "kubectl run curl-register --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
        curl -sf -X POST http://control-plane:8080/v1/functions \
        -H 'Content-Type: application/json' \
        -d '{
            \"name\": \"echo-test\",
            \"image\": \"${RUNTIME_IMAGE}\",
            \"timeoutMs\": 5000,
            \"concurrency\": 2,
            \"queueSize\": 20,
            \"maxRetries\": 3,
            \"executionMode\": \"POOL\",
            \"endpointUrl\": \"http://function-runtime:8080/invoke\"
        }'"

    log "Function registered"
}

invoke_function_with_curl() {
    log "Invoking function with curl container..."

    # Create a Job that invokes the function and checks the response
    vm_exec "cat <<'EOF' | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: curl-invoke-test
  namespace: ${NAMESPACE}
spec:
  ttlSecondsAfterFinished: 60
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: curl
        image: curlimages/curl:latest
        command:
        - /bin/sh
        - -c
        - |
          set -e
          echo 'Invoking function...'
          RESPONSE=\$(curl -sf -X POST http://control-plane:8080/v1/functions/echo-test:invoke \
            -H 'Content-Type: application/json' \
            -d '{\"input\": {\"message\": \"hello-k3s-test\"}}')
          echo \"Response: \${RESPONSE}\"

          # Verify response contains expected values
          if echo \"\${RESPONSE}\" | grep -q '\"status\":\"success\"'; then
            echo 'SUCCESS: status is success'
          else
            echo 'FAIL: status is not success'
            exit 1
          fi

          if echo \"\${RESPONSE}\" | grep -q '\"message\":\"hello-k3s-test\"'; then
            echo 'SUCCESS: message echoed correctly'
          else
            echo 'FAIL: message not echoed correctly'
            exit 1
          fi

          echo 'All verifications passed!'
EOF"

    log "Waiting for curl job to complete..."
    vm_exec "kubectl wait --for=condition=complete job/curl-invoke-test -n ${NAMESPACE} --timeout=60s"

    # Get job logs
    log "Job logs:"
    vm_exec "kubectl logs job/curl-invoke-test -n ${NAMESPACE}"

    log "Function invocation verified successfully"
}

test_async_invocation() {
    log "Testing async function invocation..."

    # Enqueue async invocation
    local exec_id
    exec_id=$(vm_exec "kubectl run curl-enqueue --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
        curl -sf -X POST http://control-plane:8080/v1/functions/echo-test:enqueue \
        -H 'Content-Type: application/json' \
        -d '{\"input\": {\"message\": \"async-test\"}}'" | grep -oP '"executionId":"\K[^"]+')

    log "Async execution ID: ${exec_id}"

    # Poll for completion
    log "Polling for execution completion..."
    for i in $(seq 1 20); do
        local status
        status=$(vm_exec "kubectl run curl-poll-${i} --rm -i --restart=Never --image=curlimages/curl:latest -n ${NAMESPACE} -- \
            curl -sf http://control-plane:8080/v1/executions/${exec_id}" 2>/dev/null | grep -oP '"status":"\K[^"]+' || echo "pending")

        if [[ "${status}" == "success" ]]; then
            log "Async execution completed successfully"
            return 0
        elif [[ "${status}" == "failed" ]]; then
            error "Async execution failed"
            exit 1
        fi
        sleep 1
    done

    error "Async execution did not complete within timeout"
    exit 1
}
```

**Step 2: Verify the test functions exist**

Run:
```bash
grep -c "register_function\|invoke_function_with_curl\|test_async_invocation" scripts/e2e-k3s-curl.sh
```

Expected: 3 or more matches

**Step 3: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add function registration and curl verification"
```

---

## Task 7: Add Main Execution Flow

**Files:**
- Modify: `scripts/e2e-k3s-curl.sh`

**Step 1: Add main function and execution**

Append to the script:

```bash
print_summary() {
    log ""
    log "=========================================="
    log "        E2E TEST COMPLETED SUCCESSFULLY"
    log "=========================================="
    log ""
    log "Summary:"
    log "  - VM: ${VM_NAME}"
    log "  - Namespace: ${NAMESPACE}"
    log "  - Control-plane image: ${CONTROL_IMAGE}"
    log "  - Function-runtime image: ${RUNTIME_IMAGE}"
    log ""
    log "Tests passed:"
    log "  [✓] VM creation and k3s installation"
    log "  [✓] Docker image build and import"
    log "  [✓] Kubernetes deployment with health probes"
    log "  [✓] Health endpoint verification (liveness/readiness)"
    log "  [✓] Function registration"
    log "  [✓] Sync function invocation with curl"
    log "  [✓] Async function invocation and polling"
    log ""
}

main() {
    log "Starting k3s E2E test..."
    log "Configuration:"
    log "  VM_NAME=${VM_NAME}"
    log "  CPUS=${CPUS}, MEMORY=${MEMORY}, DISK=${DISK}"
    log "  NAMESPACE=${NAMESPACE}"
    log "  KEEP_VM=${KEEP_VM}"
    log ""

    # Phase 1: Setup
    check_prerequisites
    create_vm
    install_dependencies
    install_k3s

    # Phase 2: Build
    sync_project
    build_jars
    build_images
    import_images_to_k3s

    # Phase 3: Deploy
    create_namespace
    deploy_control_plane
    deploy_function_runtime

    # Phase 4: Verify deployment
    wait_for_deployment "control-plane" 180
    wait_for_deployment "function-runtime" 120
    verify_pods_running
    verify_health_endpoints

    # Phase 5: Test functions
    register_function
    invoke_function_with_curl
    test_async_invocation

    # Done
    print_summary
}

# Run main
main "$@"
```

**Step 2: Verify the script is complete**

Run:
```bash
tail -30 scripts/e2e-k3s-curl.sh
```

Expected: Shows main function and execution

**Step 3: Make script executable and verify**

Run:
```bash
chmod +x scripts/e2e-k3s-curl.sh
bash -n scripts/e2e-k3s-curl.sh && echo "Syntax OK"
```

Expected: "Syntax OK" (no syntax errors)

**Step 4: Commit**

```bash
git add scripts/e2e-k3s-curl.sh
git commit -m "feat(e2e): add main execution flow and summary"
```

---

## Task 8: Add function-runtime Health Endpoint

**Files:**
- Modify: `function-runtime/src/main/resources/application.yml`

**Step 1: Read current configuration**

Run:
```bash
cat function-runtime/src/main/resources/application.yml
```

**Step 2: Add actuator health configuration if missing**

If the file doesn't have actuator health configured, create/update it:

```yaml
server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

**Step 3: Verify configuration**

Run:
```bash
grep -A5 "management:" function-runtime/src/main/resources/application.yml
```

Expected: Shows health configuration

**Step 4: Add actuator dependency if missing**

Check `function-runtime/build.gradle` for actuator dependency:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

**Step 5: Commit**

```bash
git add function-runtime/src/main/resources/application.yml function-runtime/build.gradle
git commit -m "feat(runtime): add health endpoint configuration"
```

---

## Task 9: Update CLAUDE.md with New Script

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Read current CLAUDE.md**

Run:
```bash
cat CLAUDE.md
```

**Step 2: Add documentation for new script**

Add under the E2E tests section:

```markdown
# K3s E2E with Curl (self-contained VM)
./scripts/e2e-k3s-curl.sh              # Full test with VM cleanup
KEEP_VM=true ./scripts/e2e-k3s-curl.sh # Keep VM for debugging
```

**Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add e2e-k3s-curl.sh documentation"
```

---

## Task 10: Run and Verify the Complete Test

**Step 1: Run the E2E test**

Run:
```bash
./scripts/e2e-k3s-curl.sh
```

Expected: All phases complete successfully, summary shows all tests passed

**Step 2: If test fails, debug with KEEP_VM**

Run:
```bash
KEEP_VM=true ./scripts/e2e-k3s-curl.sh
```

Then SSH into VM:
```bash
multipass shell nanofaas-k3s-e2e-<timestamp>
kubectl get pods -n nanofaas-e2e
kubectl logs -n nanofaas-e2e deployment/control-plane
```

**Step 3: Final commit after successful test**

```bash
git add -A
git commit -m "test(e2e): verify k3s curl e2e test passes"
```

---

## Summary

This plan creates a comprehensive E2E test that:

1. **Creates an ephemeral Multipass VM** with k3s
2. **Builds and imports Docker images** into k3s containerd
3. **Deploys nanofaas components** with proper readiness/liveness probes
4. **Verifies health endpoints** (/actuator/health, /liveness, /readiness)
5. **Registers a test function** via the control-plane API
6. **Invokes the function using a curl container** inside the cluster
7. **Tests async invocation** with polling for completion
8. **Cleans up the VM** automatically (unless KEEP_VM=true)

The test runs entirely within the VM, using Kubernetes-native curl Jobs for verification, which ensures the function is accessible from within the cluster network.
