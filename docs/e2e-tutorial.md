# End-to-end validation

NanoFaaS uses the same scenario workflow locally and on remote VMs.

## Container validation

Start Docker, then inspect and run the bundled container scenario:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

## Kubernetes in Multipass

On the first run, explicitly create or reuse the named VM and apply the separately maintained Ansible tasks:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision
```

The result contains k3s, Helm, a local registry, JDK 21 and the repository at `/home/ubuntu/nanofaas`. Subsequent runs omit `--provision`. Failures leave the VM running for diagnosis.

## Remote VM through SSH and Ansible

Copy `tools/controlplane/environments/external.yaml.example` to `external.yaml` and set host/user/home. The first run connects over SSH, applies the same idempotent Ansible tasks, and synchronizes the repository; it never creates or destroys the remote VM:

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --provision
```

Omit `--provision` on subsequent runs.

## Load test

The stack must already expose the control-plane and Prometheus, and k6 must be installed on the stack or dedicated load-generator role.

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --provision \
  --control-plane-url http://stack.example:30080 \
  --prometheus-url http://stack.example:30090 \
  --run-dir tools/controlplane/runs/e2e
```

`--provision` installs k6 on the dedicated `loadgen` role when present, otherwise on `stack`; omit it after the first run.

Use `--keep` while investigating a failed environment and task slicing for targeted reruns.
