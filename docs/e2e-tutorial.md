# End-to-end validation

NanoFaaS uses the same scenario workflow locally and on remote VMs.

## Container validation

Start Docker, then inspect and run the bundled container scenario:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

## Kubernetes in Multipass

Prepare the named VM with k3s, Helm, a registry, JDK 21 and the repository checkout at `/home/ubuntu/nanofaas`. The provisioning tasks are maintained separately from this tool. Then run:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
```

## Remote VM through SSH and Ansible

Copy `tools/controlplane/environments/external.yaml.example`, set host/user/home, and provision the VM idempotently with the external Ansible project. The same scenario then runs commands over SSH:

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml
```

## Load test

The stack must already expose the control-plane and Prometheus, and k6 must be installed on the stack or dedicated load-generator role.

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --control-plane-url http://stack.example:30080 \
  --prometheus-url http://stack.example:30090 \
  --run-dir tools/controlplane/runs/e2e
```

Use `--keep` while investigating a failed environment and task slicing for targeted reruns.
