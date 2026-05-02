# External SSH VM E2E Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow all VM-based E2E flows to target either a Multipass-managed VM or an externally managed local/remote VM, while using SSH/SCP as the only remote exec/copy transport.

**Architecture:** Split the problem into three layers: VM lifecycle, remote transport, and node/bootstrap logic. Keep Multipass only for lifecycle operations (`launch`, `start`, `recover`, `delete`, `purge`) when the lifecycle is `multipass`; every command execution, file copy, kubeconfig export, and project sync must go through the shared SSH/SCP helpers. Centralize host, user, home, kubeconfig, and remote project directory resolution in `scripts/lib/e2e-k3s-common.sh`, then make every runner consume those helpers instead of hardcoded `/home/ubuntu` paths or direct Multipass assumptions.

**Tech Stack:** Bash, ssh/scp, Multipass, k3s, Gradle, pytest contract tests for shell scripts.

---

### Task 1: Lock the external-VM contract with failing tests

**Files:**
- Modify: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py`

**Step 1: Write the failing tests**

Add assertions for these contracts in `scripts/lib/e2e-k3s-common.sh`:

```python
assert "E2E_VM_LIFECYCLE" in script
assert "e2e_get_vm_lifecycle" in script
assert "e2e_is_external_vm_lifecycle" in script
assert "E2E_VM_HOST" in script
assert "e2e_get_vm_user" in script
assert "e2e_get_vm_home" in script
assert "e2e_get_kubeconfig_path" in script
assert "e2e_get_remote_project_dir" in script
assert "Using externally managed VM host" in script
assert "Skipping VM deletion: external VM lifecycle mode" in script
assert "/home/ubuntu/.kube/config" not in script
```

Also add a shell-level contract test in `scripts/tests/test_e2e_runtime_contract.py` for helper outputs:

```python
out = run_shell(
    f"{source}; "
    "E2E_VM_LIFECYCLE=external; "
    "E2E_VM_HOST=vm.example.test; "
    "E2E_VM_USER=dev; "
    "E2E_VM_HOME=/srv/dev; "
    "printf '%s|%s|%s|%s' "
    "\"$(e2e_get_vm_lifecycle)\" "
    "\"$(e2e_get_vm_host)\" "
    "\"$(e2e_get_kubeconfig_path)\" "
    "\"$(e2e_get_remote_project_dir)\""
)
assert out == "external|vm.example.test|/srv/dev/.kube/config|/srv/dev/nanofaas"
```

**Step 2: Run tests to verify they fail**

Run:

```bash
pytest -q scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py
```

Expected: FAIL because the lifecycle helpers and helper-based paths do not exist yet.

**Step 3: Do not change production code yet**

Stop after confirming the failure mode is the expected missing-contract failure.

**Step 4: Commit the failing tests**

```bash
git add scripts/tests/test_e2e_k3s_common_external_ssh_mode.py scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py
git commit -m "test: define external ssh vm contracts"
```

### Task 2: Add shared VM identity and path helpers in the common library

**Files:**
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Test: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Test: `scripts/tests/test_e2e_runtime_contract.py`

**Step 1: Write the minimal helper API**

Add these functions near the top of `scripts/lib/e2e-k3s-common.sh`:

```bash
e2e_get_vm_lifecycle() { ... }          # multipass | external
e2e_is_external_vm_lifecycle() { ... }  # return 0 only for external
e2e_get_vm_host() { ... }               # E2E_VM_HOST > VM_IP > multipass info
e2e_get_vm_user() { ... }               # E2E_VM_USER or ubuntu
e2e_get_vm_home() { ... }               # E2E_VM_HOME or /root or /home/<user>
e2e_get_kubeconfig_path() { ... }       # E2E_KUBECONFIG_PATH or <home>/.kube/config
e2e_get_remote_project_dir() { ... }    # E2E_REMOTE_PROJECT_DIR or <home>/nanofaas
```

Rules:
- `multipass` remains the default lifecycle.
- `E2E_VM_HOST` is required only when lifecycle is `external`.
- `e2e_get_vm_home()` must special-case `root` to `/root`.
- `e2e_vm_exec()` must export `KUBECONFIG="$(e2e_get_kubeconfig_path)"` instead of `/home/ubuntu/.kube/config`.

**Step 2: Run targeted tests**

Run:

```bash
pytest -q scripts/tests/test_e2e_k3s_common_external_ssh_mode.py -q
pytest -q scripts/tests/test_e2e_runtime_contract.py -q
```

Expected: PASS for helper/path tests, while lifecycle behavior tests may still fail until Task 3.

**Step 3: Refactor remaining direct user/path lookups in the common library**

Replace direct uses of:

```bash
${E2E_VM_USER:-ubuntu}
/home/ubuntu/.kube/config
/home/ubuntu/nanofaas
```

with the new helper API.

**Step 4: Re-run both tests**

Run the same `pytest` commands and confirm they remain green.

**Step 5: Commit**

```bash
git add scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_k3s_common_external_ssh_mode.py scripts/tests/test_e2e_runtime_contract.py
git commit -m "refactor: centralize vm identity and path helpers"
```

### Task 3: Separate VM lifecycle from SSH transport

**Files:**
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Test: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Test: `scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py`

**Step 1: Preserve SSH as the only remote transport**

Do not add new backends. Keep:

```bash
e2e_get_vm_backend() -> ssh
e2e_vm_exec() -> ssh
e2e_copy_to_vm() -> scp
e2e_copy_from_vm() -> scp
```

Multipass must remain only in lifecycle helpers:
- `e2e_create_vm`
- `e2e_ensure_vm_running`
- `e2e_cleanup_vm`
- `e2e_get_vm_ip` when lifecycle is `multipass`

**Step 2: Implement the external lifecycle branch**

In `e2e_ensure_vm_running()`:

```bash
if e2e_is_external_vm_lifecycle; then
    e2e_log "Using externally managed VM host ${host}"
    if ! e2e_ssh_exec "${host}" "true"; then
        e2e_error "SSH reachability check failed for external VM host '${host}'"
        return 1
    fi
    return 0
fi
```

In `e2e_cleanup_vm()`:

```bash
if e2e_is_external_vm_lifecycle; then
    info "Skipping VM deletion: external VM lifecycle mode"
    return 0
fi
```

In `e2e_get_vm_ip()`:
- return `E2E_VM_HOST` for external lifecycle
- keep `multipass info ...` only for multipass lifecycle

**Step 3: Normalize prerequisites**

Add one shared guard such as:

```bash
e2e_require_vm_access() {
    command -v ssh >/dev/null 2>&1 || ...
    if ! e2e_is_external_vm_lifecycle; then
        e2e_require_multipass
    fi
}
```

Then convert runner `check_prerequisites()` functions to call it instead of hardcoding `e2e_require_multipass`.

**Step 4: Run lifecycle tests**

Run:

```bash
pytest -q scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_k3s_common_external_ssh_mode.py scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py
git commit -m "feat: add external vm lifecycle over ssh"
```

### Task 4: Remove hardcoded `/home/ubuntu` and kubeconfig assumptions from all main runners

**Files:**
- Modify: `scripts/e2e-k8s-vm.sh`
- Modify: `scripts/e2e-cli.sh`
- Modify: `scripts/e2e-cli-host-platform.sh`
- Modify: `scripts/e2e-k3s-curl.sh`
- Modify: `scripts/e2e-k3s-helm.sh`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Test: `scripts/tests/test_e2e_runtime_runners.py`

**Step 1: Introduce helper-based local variables at the top of each runner**

Pattern:

```bash
REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}
KUBECONFIG_PATH=$(e2e_get_kubeconfig_path)
VM_HOME=$(e2e_get_vm_home)
VM_USER=$(e2e_get_vm_user)
```

**Step 2: Replace all hardcoded remote paths**

Replace:

```bash
/home/ubuntu/nanofaas
/home/ubuntu/.kube/config
/home/ubuntu/.bashrc
/home/ubuntu/nanofaas/helm/nanofaas
/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin
```

with:

```bash
${REMOTE_DIR}
${KUBECONFIG_PATH}
${VM_HOME}/.bashrc
${REMOTE_DIR}/helm/nanofaas
${REMOTE_DIR}/nanofaas-cli/build/install/nanofaas-cli/bin
```

**Step 3: Update common bootstrap helpers**

In `e2e_install_k3s()` replace the kubeconfig setup with:

```bash
local vm_user vm_home kubeconfig_path
vm_user=$(e2e_get_vm_user)
vm_home=$(e2e_get_vm_home)
kubeconfig_path=$(e2e_get_kubeconfig_path)
vm_exec "mkdir -p ${vm_home}/.kube"
vm_exec "sudo cp /etc/rancher/k3s/k3s.yaml ${kubeconfig_path}"
vm_exec "sudo chown ${vm_user}:${vm_user} ${kubeconfig_path}"
vm_exec "chmod 600 ${kubeconfig_path}"
```

**Step 4: Run runner contract tests**

Run:

```bash
pytest -q scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py
```

Expected: PASS and no remaining `/home/ubuntu` references in the shared path logic.

**Step 5: Commit**

```bash
git add scripts/e2e-k8s-vm.sh scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-k3s-curl.sh scripts/e2e-k3s-helm.sh scripts/lib/e2e-k3s-common.sh scripts/tests/test_e2e_runtime_runners.py scripts/tests/test_e2e_runtime_contract.py
git commit -m "refactor: remove ubuntu-specific vm runner paths"
```

### Task 5: Add reusable host-facing kubeconfig and endpoint helpers

**Files:**
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/e2e-cli-host-platform.sh`
- Modify: `experiments/e2e-loadtest.sh`
- Modify: `experiments/e2e-loadtest-registry.sh`
- Modify: `experiments/e2e-cold-start-metrics.sh`
- Modify: `experiments/e2e-runtime-ab.sh`
- Modify: `experiments/e2e-memory-ab.sh`

**Step 1: Add a shared kubeconfig export helper**

Create:

```bash
e2e_export_kubeconfig_to_host() {
    local vm_name=$1
    local dest=$2
    local kubeconfig_path server_url
    kubeconfig_path=$(e2e_get_kubeconfig_path)
    server_url=${E2E_KUBECONFIG_SERVER:-https://$(e2e_get_vm_host):6443}
    e2e_copy_from_vm "${vm_name}" "${kubeconfig_path}" "${dest}"
    python3 - "${dest}" "${server_url}" <<'PY'
    ...
PY
}
```

Use `E2E_KUBECONFIG_SERVER` only when the kube-apiserver is not reachable at `https://<ssh-host>:6443`.

**Step 2: Add one public-host helper for NodePort URLs**

Add:

```bash
e2e_get_public_host() {
    echo "${E2E_PUBLIC_HOST:-$(e2e_get_vm_host)}"
}
```

Then make `e2e_resolve_nanofaas_url()` and consumers derive URLs from `e2e_get_public_host()`.

**Step 3: Replace ad hoc kubeconfig rewrite logic**

Update `scripts/e2e-cli-host-platform.sh` to call `e2e_export_kubeconfig_to_host` instead of embedding Python rewrite logic inline.

Update the load test and experiment scripts so that:
- host/IP discovery uses the new helper
- external VMs work without calling `multipass`
- existing `NANOFAAS_URL` and `PROM_URL` overrides still take precedence

**Step 4: Run targeted tests and one smoke command**

Run:

```bash
pytest -q scripts/tests/test_e2e_runtime_contract.py scripts/tests/test_e2e_runtime_runners.py
```

Then run a shell smoke command:

```bash
bash -lc 'source scripts/lib/e2e-k3s-common.sh; E2E_VM_LIFECYCLE=external E2E_VM_HOST=vm.example.test E2E_PUBLIC_HOST=api.example.test E2E_KUBECONFIG_SERVER=https://api.example.test:6443 printf "%s|%s\n" "$(e2e_get_public_host)" "$(e2e_resolve_nanofaas_url 30080)"'
```

Expected: `api.example.test|http://api.example.test:30080`

**Step 5: Commit**

```bash
git add scripts/lib/e2e-k3s-common.sh scripts/e2e-cli-host-platform.sh experiments/e2e-loadtest.sh experiments/e2e-loadtest-registry.sh experiments/e2e-cold-start-metrics.sh experiments/e2e-runtime-ab.sh experiments/e2e-memory-ab.sh
git commit -m "feat: share kubeconfig and endpoint helpers for external vms"
```

### Task 6: Update suite orchestration and documentation

**Files:**
- Modify: `scripts/e2e-all.sh`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`

**Step 1: Update suite help and prerequisites**

In `scripts/e2e-all.sh`, replace Multipass-only wording with lifecycle-aware wording:
- Multipass required only for `E2E_VM_LIFECYCLE=multipass`
- SSH required for every VM-based suite
- cleanup message must not recommend `multipass delete` when lifecycle is `external`

**Step 2: Document the supported external-VM environment**

Add a new section with the supported variables:

```bash
E2E_VM_LIFECYCLE=external
E2E_VM_HOST=<ip-or-dns>
E2E_VM_USER=<ssh-user>
E2E_VM_HOME=<optional-home>
E2E_KUBECONFIG_PATH=<optional-remote-kubeconfig>
E2E_REMOTE_PROJECT_DIR=<optional-remote-repo-path>
E2E_PUBLIC_HOST=<optional-nodeport-host>
E2E_KUBECONFIG_SERVER=<optional-https-server-url>
```

**Step 3: Add one local-VM and one remote-VM example**

Examples to document:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/e2e-k3s-curl.sh
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/e2e-cli-host-platform.sh
```

Also state clearly that:
- SSH/SCP are always used for command execution and file transfer
- Multipass is used only to manage a VM when the lifecycle is `multipass`

**Step 4: Run the lightweight docs/suite contract verification**

Run:

```bash
pytest -q scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_runtime_contract.py
```

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/e2e-all.sh README.md docs/testing.md docs/e2e-tutorial.md
git commit -m "docs: describe external ssh vm e2e mode"
```

### Task 7: Final verification matrix

**Files:**
- Verify: `scripts/lib/e2e-k3s-common.sh`
- Verify: `scripts/e2e-k8s-vm.sh`
- Verify: `scripts/e2e-cli.sh`
- Verify: `scripts/e2e-cli-host-platform.sh`
- Verify: `scripts/e2e-k3s-curl.sh`
- Verify: `scripts/e2e-k3s-helm.sh`
- Verify: `experiments/e2e-loadtest.sh`
- Verify: `experiments/e2e-loadtest-registry.sh`

**Step 1: Run the full script contract suite**

Run:

```bash
pytest -q scripts/tests
```

Expected: PASS.

**Step 2: Run one multipass regression smoke**

Run:

```bash
DRY_RUN=true ./scripts/e2e-all.sh --only k3s-curl
```

Expected: the suite still resolves to the normal VM-based flow without external-mode warnings.

**Step 3: Run one external-lifecycle smoke**

Run:

```bash
E2E_VM_LIFECYCLE=external \
E2E_VM_HOST=vm.example.test \
E2E_VM_USER=ubuntu \
DRY_RUN=true ./scripts/e2e-all.sh --only k3s-curl
```

Expected: no Multipass prerequisite failure, and log/help text should reflect SSH/external lifecycle.

**Step 4: If a real external VM is available, run one end-to-end script manually**

Preferred manual smoke:

```bash
E2E_VM_LIFECYCLE=external \
E2E_VM_HOST=<reachable-host> \
E2E_VM_USER=<ssh-user> \
KEEP_VM=true \
./scripts/e2e-k3s-curl.sh
```

Expected: the script reuses the existing VM over SSH, skips Multipass lifecycle operations, and completes the normal deployment/test flow.

**Step 5: Commit final verification-only adjustments**

```bash
git add .
git commit -m "test: verify external ssh vm e2e support"
```
