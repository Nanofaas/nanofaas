# Ansible VM Provisioning Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move VM provisioning for both Multipass-managed and externally managed VMs to Ansible, keeping SSH/SCP as the only transport, making provisioning idempotent, and ensuring k3s installs/upgrades to the latest official release at run time.

**Architecture:** Keep lifecycle in shell (`multipass` vs `external`) and move configuration into Ansible playbooks invoked from `scripts/lib/e2e-k3s-common.sh`. Bootstrap Ansible on the host idempotently, generate dynamic inventory from the existing SSH helpers, and use playbooks for base packages, optional Helm install, k3s install/upgrade, kubeconfig export, and local registry wiring.

**Tech Stack:** Bash, Ansible, SSH/SCP, GitHub Releases API, pytest shell-contract tests.

---

### Task 1: Lock the Ansible provisioning contract with failing tests

**Files:**
- Modify: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Create: `scripts/tests/test_e2e_ansible_provisioning.py`

**Step 1: Write failing tests for the common library contract**

Add assertions for:
- `e2e_ensure_ansible`
- `e2e_get_ansible_bin`
- `e2e_run_ansible_playbook`
- `e2e_install_vm_dependencies` delegating to Ansible
- `e2e_install_k3s` delegating to Ansible
- `e2e_setup_local_registry` delegating to Ansible

Also assert that playbook files exist under `scripts/ansible/`.

**Step 2: Add shell-level contract tests**

Add runtime tests for:
- ansible bootstrap path resolution
- ansible binary resolution
- inventory host/user/key rendering from external VM settings

**Step 3: Run tests to verify failure**

Run:

```bash
pytest -q scripts/tests/test_e2e_ansible_provisioning.py \
  scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py
```

Expected: FAIL because the ansible helpers and playbooks do not exist yet.

### Task 2: Add host-side Ansible bootstrap and dynamic inventory helpers

**Files:**
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Create: `scripts/ansible/ansible.cfg`
- Create: `scripts/ansible/requirements.txt`
- Test: `scripts/tests/test_e2e_ansible_provisioning.py`
- Test: `scripts/tests/test_e2e_runtime_contract.py`

**Step 1: Add minimal host bootstrap helpers**

Implement:
- `e2e_get_ansible_root`
- `e2e_get_ansible_venv_dir`
- `e2e_get_ansible_bin`
- `e2e_ensure_ansible`
- `e2e_write_ansible_inventory`
- `e2e_run_ansible_playbook`

Rules:
- Use `ansible-playbook` from `PATH` if available.
- Otherwise create a host venv and `pip install -r scripts/ansible/requirements.txt`.
- Inventory must derive host, user, ssh key, and Python interpreter from the existing SSH helper logic.

**Step 2: Verify tests**

Run:

```bash
pytest -q scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_e2e_runtime_contract.py
```

Expected: PASS for helper/bootstrap contracts.

### Task 3: Replace base VM dependency provisioning with Ansible

**Files:**
- Create: `scripts/ansible/playbooks/provision-base.yml`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Test: `scripts/tests/test_e2e_ansible_provisioning.py`

**Step 1: Write the Ansible playbook**

The playbook must:
- bootstrap `python3` on the target if missing
- install `curl`, `ca-certificates`, `tar`, `unzip`, `openjdk-21-jdk-headless`, `docker.io`
- enable/start Docker
- add the remote user to `docker` group when non-root
- optionally install Helm idempotently when requested

**Step 2: Wire `e2e_install_vm_dependencies()` to Ansible**

Pass variables:
- `install_helm`
- `helm_version`
- `vm_user`

**Step 3: Verify**

Run:

```bash
pytest -q scripts/tests/test_e2e_ansible_provisioning.py
ansible-playbook --syntax-check scripts/ansible/playbooks/provision-base.yml
```

### Task 4: Replace k3s provisioning with Ansible and resolve latest release dynamically

**Files:**
- Create: `scripts/ansible/playbooks/provision-k3s.yml`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py`

**Step 1: Write the failing tests for latest-release resolution**

Add assertions for:
- GitHub API release resolution logic
- absence of hardcoded `K3S_VERSION` default pin in the install path
- kubeconfig path handling staying helper-based

**Step 2: Implement the playbook**

The playbook must:
- query `https://api.github.com/repos/k3s-io/k3s/releases/latest`
- extract `tag_name`
- compare with installed `k3s --version`
- run the official installer only when k3s is missing or not at the latest release
- wait for node readiness
- copy kubeconfig to the helper-resolved target path with correct ownership/mode

Allow override:
- `K3S_VERSION` should still force a specific version when explicitly set
- otherwise latest official release wins

**Step 3: Wire `e2e_install_k3s()` to Ansible**

**Step 4: Verify**

Run:

```bash
pytest -q scripts/tests/test_e2e_k3s_common_external_ssh_mode.py scripts/tests/test_e2e_runtime_contract.py
ansible-playbook --syntax-check scripts/ansible/playbooks/provision-k3s.yml
```

### Task 5: Move local-registry/k3s registry wiring to Ansible

**Files:**
- Create: `scripts/ansible/playbooks/configure-registry.yml`
- Modify: `scripts/lib/e2e-k3s-common.sh`
- Modify: `scripts/tests/test_e2e_ansible_provisioning.py`

**Step 1: Replace shell registry configuration with Ansible**

The playbook must:
- ensure the `registry:2` container is running on the target
- write `/etc/rancher/k3s/registries.yaml`
- restart k3s only when registry config changes
- wait for node readiness after restart

**Step 2: Wire `e2e_setup_local_registry()` to Ansible**

**Step 3: Verify**

Run:

```bash
pytest -q scripts/tests/test_e2e_ansible_provisioning.py scripts/tests/test_e2e_runtime_contract.py
ansible-playbook --syntax-check scripts/ansible/playbooks/configure-registry.yml
```

### Task 6: Update documentation and runner expectations

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`

**Step 1: Document Ansible-based provisioning**

State clearly:
- VM lifecycle still uses `multipass` only when `E2E_VM_LIFECYCLE=multipass`
- all VM provisioning is done through Ansible over SSH
- host needs `python3`; Ansible is auto-bootstrapped if missing
- k3s defaults to the latest official release unless `K3S_VERSION` is explicitly set

**Step 2: Verify**

Run:

```bash
pytest -q scripts/tests/test_e2e_runtime_runners.py
```

### Task 7: Final verification

**Files:**
- Verify all touched files

**Step 1: Run full focused test suite**

```bash
pytest -q scripts/tests/test_e2e_ansible_provisioning.py \
  scripts/tests/test_e2e_k3s_common_external_ssh_mode.py \
  scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py
```

**Step 2: Run Ansible syntax checks**

```bash
ansible-playbook --syntax-check scripts/ansible/playbooks/provision-base.yml
ansible-playbook --syntax-check scripts/ansible/playbooks/provision-k3s.yml
ansible-playbook --syntax-check scripts/ansible/playbooks/configure-registry.yml
```

**Step 3: Run shell syntax checks**

```bash
bash -n scripts/lib/e2e-k3s-common.sh \
  scripts/e2e-all.sh \
  scripts/e2e-k8s-vm.sh \
  scripts/e2e-cli.sh \
  scripts/e2e-k3s-curl.sh \
  scripts/e2e-cli-host-platform.sh \
  scripts/e2e-k3s-helm.sh \
  experiments/e2e-loadtest-registry.sh \
  experiments/e2e-cold-start-metrics.sh \
  experiments/e2e-runtime-ab.sh \
  experiments/e2e-memory-ab.sh
```
