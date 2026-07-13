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

The load-test workflow builds and deploys NanoFaaS with Helm, registers the
function, runs k6, verifies autoscaling, captures metrics and cleans up the
deployment. Provisioning only prepares the VM and installs its prerequisites.

```bash
scripts/controlplane.sh run tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml \
  --provision \
  --run-dir tools/controlplane/runs/e2e
```

The stack host and NodePort endpoints are derived from the environment. Explicit
URL options remain available as overrides. `--provision` installs k6 on the
dedicated `loadgen` role when present, otherwise on `stack`; omit it after the
first run. For a two-VM Multipass run use
`tools/controlplane/environments/multipass-two-vm.yaml`.

Use `--keep` while investigating a failed environment and task slicing for targeted reruns.
