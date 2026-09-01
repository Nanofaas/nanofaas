# Testing

Run Java tests with `./gradlew test`. Run the nanolab workspace tests from the
separate [nanolab](https://github.com/miciav/nanolab) checkout:

```bash
cd ../nanolab && uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests
```

Plan tests do not need Docker or a VM:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-container.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/loadtest.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

Actual container validation needs Docker. With `--provision`, Kubernetes validation
can prepare Multipass, Azure, or Proxmox VMs; an external SSH host remains
user-managed. Load tests additionally need provider credentials and network access.
Managed VMs are deleted after the run unless `--keep` is set.

The lifecycle scenarios deliberately start clean and remove state at teardown.
The persistent-recovery scenarios keep it only across their internal
control-plane restart, where they verify function adoption and replica recovery;
they then use the same normal teardown.

Use `--only`, `--from`, and `--until` to isolate tasks. Use `--keep` only when infrastructure must remain available for investigation.

## Watchdog

The watchdog has Rust unit tests and local integration tests for HTTP, STDIO, FILE, callback, metrics, and warm lifecycle behavior. They require Rust, Python 3, `jq`, `curl`, and `nc`; Docker and a VM are not required.

```bash
cargo test --manifest-path runtimes/watchdog/Cargo.toml
bash runtimes/watchdog/test-local.sh

# Focused suites while debugging
bash runtimes/watchdog/test-local.sh --http
bash runtimes/watchdog/test-local.sh --stdio
bash runtimes/watchdog/test-local.sh --file
bash runtimes/watchdog/test-local.sh --callback
```
