"""Snapshot oracle for the proxmox-vm-loadtest prelude Workflow (C4.3a).

Pins that the honest-Task ``Workflow`` prelude built by
``build_proxmox_vm_loadtest_plan`` reproduces an exact, ordered task_id list and
the exact argv/env per command — frozen as literal snapshots. These were
originally derived from the legacy recipe engine's proxmox prelude components; the
recipe engine is being deleted, so the snapshots below are now the source of truth.

The snapshot encodes the three proxmox rewrites (previously covered directly
against the legacy recipe in test_recipe_execution_hooks.py — migrated here):

1. ansible steps (vm.provision_base / registry.ensure_container / k3s.install /
   k3s.configure_registry): the ``-i`` inventory is rewritten to the proxmox SSH
   endpoint ``host,`` + appended ``-e ansible_port=PORT`` + ``--private-key KEY``.
2. repo.sync_to_vm: replaced by an rsync command through the proxmox SSH endpoint
   (``-e ssh ... -p PORT -i KEY`` and ``user@host:remote_dir/`` destination).
3. cli.fn_apply_selected: replaced by a single ``functions.register`` task
   (RegisterFunctions via REST, with publish_port NAT). It is a CallableTask, so
   only its task_id position is asserted (no CommandTask spec).
"""
from __future__ import annotations

from pathlib import Path

import pytest

from workflow_tasks.infra.ansible import bundled_ansible_root

_ANSIBLE_ROOT = bundled_ansible_root()
_ANSIBLE_CFG = str(_ANSIBLE_ROOT / "ansible.cfg")


def _playbook(name: str) -> str:
    return str(_ANSIBLE_ROOT / "playbooks" / name)

# Deterministic proxmox SSH endpoint / key produced by _FakeProxmoxVmOrchestrator
# and baked into the literal snapshot below.
_HOST = "203.0.113.5"
_SSH_PORT = 2222
_KEY = Path("/keys/proxmox_id")
_CP_HOST = "203.0.113.5"
_CP_PORT = 30080


class _FakeProxmoxVmOrchestrator:
    def __init__(self, repo_root=None, **_kw):
        self.repo_root = repo_root

    def remote_project_dir(self, request):
        return f"/home/{request.user or 'ubuntu'}/nanofaas"

    def ssh_endpoint(self, request):
        return (_HOST, _SSH_PORT)

    def ssh_private_key_path(self, request):
        return _KEY

    def wait_for_ssh(self, request, *, timeout=120.0):
        return None

    def connection_host(self, request):
        return _HOST

    def publish_port(self, request, *, service, guest_port):
        return (_CP_HOST, _CP_PORT)

    def teardown(self, request):
        return None


@pytest.fixture()
def patched_proxmox(monkeypatch):
    monkeypatch.setattr(
        "controlplane_tool.infra.vm.proxmox_vm_adapter.ProxmoxVmOrchestrator",
        _FakeProxmoxVmOrchestrator,
    )
    return _FakeProxmoxVmOrchestrator


# --- Literal snapshots (captured from the legacy recipe; now the source of truth) ---
EXPECTED_PRELUDE_TASK_IDS = [
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core.boot_jars",
    "images.build_core.control_image",
    "images.build_core.runtime_image",
    "images.build_core.push_control_image",
    "images.build_core.push_runtime_image",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "cli.build_install_dist",
    "functions.register",
]

# vm.ensure_running -> EnsureVmRunning (not a CommandTask); functions.register ->
# CallableTask (no spec). All other prelude tasks are honest CommandTasks whose
# argv/env are pinned here, INCLUDING the three proxmox rewrites.
_RSYNC_EXCLUDES = [
    "--exclude=.git",
    "--exclude=.git/",
    "--exclude=.gitnexus",
    "--exclude=.gradle/",
    "--exclude=.gradle-local/",
    "--exclude=.DS_Store",
    "--exclude=.idea/",
    "--exclude=.vscode/",
    "--exclude=.env",
    "--exclude=*.log",
    "--exclude=*.class",
    "--exclude=.worktrees/",
    "--exclude=__pycache__/",
    "--exclude=*.egg-info/",
    "--exclude=*.pyc",
    "--exclude=*.pyo",
    "--exclude=*.pyd",
    "--exclude=.pytest_cache/",
    "--exclude=.venv/",
    "--exclude=.uv/",
    "--exclude=node_modules/",
    "--exclude=dist/",
    "--exclude=/building/",
    "--exclude=out/",
    "--exclude=target/",
    "--exclude=building-test/",
    "--exclude=k6/results/",
    "--exclude=experiments/k6/results/",
    "--exclude=experiments/loadtest/results/",
    "--exclude=experiments/.image-cache/",
    "--exclude=tooling/runs/",
    "--exclude=tools/controlplane/runs/",
    "--exclude=recovery/",
]

EXPECTED_PRELUDE_COMMANDS: dict[str, dict] = {
    "vm.provision_base": {
        "argv": [
            "ansible-playbook", "-i", "203.0.113.5,", "-u", "ubuntu",
            "--private-key", "/keys/proxmox_id",
            "-e", "install_helm=true",
            "-e", "helm_version=3.16.4",
            "-e", "vm_user=ubuntu",
            _playbook("provision-base.yml"),
            "-e", "ansible_port=2222",
        ],
        "env": {"ANSIBLE_CONFIG": _ANSIBLE_CFG},
    },
    "repo.sync_to_vm": {
        "argv": [
            "rsync", "-az", "--delete", "--delete-excluded",
            *_RSYNC_EXCLUDES,
            "-e",
            "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "
            "-p 2222 -i /keys/proxmox_id",
            "/repo/",
            "ubuntu@203.0.113.5:/home/ubuntu/nanofaas/",
        ],
        "env": {},
    },
    "registry.ensure_container": {
        "argv": [
            "ansible-playbook", "-i", "203.0.113.5,", "-u", "ubuntu",
            "--private-key", "/keys/proxmox_id",
            "-e", "registry=localhost:5000",
            "-e", "registry_host=localhost",
            "-e", "registry_port=5000",
            "-e", "registry_container_name=nanofaas-e2e-registry",
            _playbook("ensure-registry.yml"),
            "-e", "ansible_port=2222",
        ],
        "env": {"ANSIBLE_CONFIG": _ANSIBLE_CFG},
    },
    "images.build_core.boot_jars": {
        "argv": [
            "./gradlew", ":control-plane:bootJar", ":function-runtime:bootJar",
            "--no-daemon", "-q",
        ],
        "env": {},
    },
    "images.build_core.control_image": {
        "argv": [
            "docker", "build", "-f", "control-plane/Dockerfile", "-t",
            "localhost:5000/nanofaas/control-plane:e2e", "control-plane",
        ],
        "env": {},
    },
    "images.build_core.runtime_image": {
        "argv": [
            "docker", "build", "-f", "function-runtime/Dockerfile", "-t",
            "localhost:5000/nanofaas/function-runtime:e2e", "function-runtime",
        ],
        "env": {},
    },
    "images.build_core.push_control_image": {
        "argv": ["docker", "push", "localhost:5000/nanofaas/control-plane:e2e"],
        "env": {},
    },
    "images.build_core.push_runtime_image": {
        "argv": ["docker", "push", "localhost:5000/nanofaas/function-runtime:e2e"],
        "env": {},
    },
    "k3s.install": {
        "argv": [
            "ansible-playbook", "-i", "203.0.113.5,", "-u", "ubuntu",
            "--private-key", "/keys/proxmox_id",
            "-e", "vm_user=ubuntu",
            "-e", "kubeconfig_path=/home/ubuntu/.kube/config",
            _playbook("provision-k3s.yml"),
            "-e", "ansible_port=2222",
        ],
        "env": {"ANSIBLE_CONFIG": _ANSIBLE_CFG},
    },
    "k3s.configure_registry": {
        "argv": [
            "ansible-playbook", "-i", "203.0.113.5,", "-u", "ubuntu",
            "--private-key", "/keys/proxmox_id",
            "-e", "registry=localhost:5000",
            "-e", "registry_host=localhost",
            "-e", "registry_port=5000",
            _playbook("configure-k3s-registry.yml"),
            "-e", "ansible_port=2222",
        ],
        "env": {"ANSIBLE_CONFIG": _ANSIBLE_CFG},
    },
    "namespace.install": {
        "argv": [
            "helm", "upgrade", "--install", "nanofaas-e2e-namespace",
            "deploy/helm/nanofaas-namespace", "-n", "default", "--wait", "--timeout",
            "2m", "--set", "namespace.name=nanofaas-e2e",
        ],
        "env": {"KUBECONFIG": "/home/ubuntu/.kube/config"},
    },
    "helm.deploy_control_plane": {
        "argv": [
            "helm", "upgrade", "--install", "control-plane", "deploy/helm/nanofaas",
            "-n", "nanofaas-e2e", "--wait", "--timeout", "5m",
            "--set", "namespace.create=false",
            "--set", "namespace.name=nanofaas-e2e",
            "--set", "controlPlane.image.repository=localhost:5000/nanofaas/control-plane",
            "--set", "controlPlane.image.tag=e2e",
            "--set", "controlPlane.image.pullPolicy=Always",
            "--set", "demos.enabled=false",
            "--set", "prometheus.create=true",
            "--set", "controlPlane.extraEnv[0].name=NANOFAAS_DEPLOYMENT_DEFAULT_BACKEND",
            "--set", "controlPlane.extraEnv[0].value=k8s",
            "--set", "controlPlane.extraEnv[1].name=NANOFAAS_K8S_CALLBACK_URL",
            "--set", "controlPlane.extraEnv[1].value=http://control-plane.nanofaas-e2e.svc.cluster.local:8080/v1/internal/executions",
            "--set", "controlPlane.extraEnv[2].name=SYNC_QUEUE_ENABLED",
            "--set", "controlPlane.extraEnv[2].value=true",
            "--set", "controlPlane.extraEnv[3].name=NANOFAAS_SYNC_QUEUE_ENABLED",
            "--set", "controlPlane.extraEnv[3].value=true",
            "--set", "controlPlane.extraEnv[4].name=SYNC_QUEUE_ADMISSION_ENABLED",
            "--set", "controlPlane.extraEnv[4].value=false",
            "--set", "controlPlane.extraEnv[5].name=SYNC_QUEUE_MAX_DEPTH",
            "--set", "controlPlane.extraEnv[5].value=1",
            "--set", "controlPlane.extraEnv[6].name=NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY",
            "--set", "controlPlane.extraEnv[6].value=1",
            "--set", "controlPlane.extraEnv[7].name=SYNC_QUEUE_MAX_ESTIMATED_WAIT",
            "--set", "controlPlane.extraEnv[7].value=2s",
            "--set", "controlPlane.extraEnv[8].name=SYNC_QUEUE_MAX_QUEUE_WAIT",
            "--set", "controlPlane.extraEnv[8].value=5s",
            "--set", "controlPlane.extraEnv[9].name=SYNC_QUEUE_RETRY_AFTER_SECONDS",
            "--set", "controlPlane.extraEnv[9].value=2",
            "--set", "controlPlane.extraEnv[10].name=SYNC_QUEUE_THROUGHPUT_WINDOW",
            "--set", "controlPlane.extraEnv[10].value=10s",
            "--set", "controlPlane.extraEnv[11].name=SYNC_QUEUE_PER_FUNCTION_MIN_SAMPLES",
            "--set", "controlPlane.extraEnv[11].value=1",
            "--set", "controlPlane.service.type=NodePort",
            "--set", "controlPlane.service.nodePorts.http=30080",
            "--set", "controlPlane.service.nodePorts.actuator=30081",
            "--set", "prometheus.service.type=NodePort",
            "--set", "prometheus.service.nodePort=30090",
        ],
        "env": {"KUBECONFIG": "/home/ubuntu/.kube/config"},
    },
    "helm.deploy_function_runtime": {
        "argv": [
            "helm", "upgrade", "--install", "function-runtime",
            "deploy/helm/nanofaas-runtime", "-n", "nanofaas-e2e", "--wait", "--timeout",
            "3m",
            "--set", "functionRuntime.image.repository=localhost:5000/nanofaas/function-runtime",
            "--set", "functionRuntime.image.tag=e2e",
            "--set", "functionRuntime.image.pullPolicy=Always",
        ],
        "env": {"KUBECONFIG": "/home/ubuntu/.kube/config"},
    },
    "cli.build_install_dist": {
        "argv": ["./gradlew", ":nanofaas-cli:installDist", "--no-daemon", "-q"],
        "env": {},
    },
}

_ANSIBLE_IDS = {
    "vm.provision_base",
    "registry.ensure_container",
    "k3s.install",
    "k3s.configure_registry",
}


def _build(tmp_path):
    from workflow_tasks.shell import RecordingShell
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = E2eRunner(
        repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path
    )
    request = E2eRequest(
        scenario="loadtest-proxmox",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen"),
    )
    return runner, request


def _plan(tmp_path):
    from controlplane_tool.scenario.scenarios.proxmox_vm_loadtest import (
        build_proxmox_vm_loadtest_plan,
    )

    runner, request = _build(tmp_path)
    return build_proxmox_vm_loadtest_plan(runner, request)


def test_prelude_task_ids_match_snapshot(patched_proxmox, tmp_path):
    plan = _plan(tmp_path)
    assert plan.prelude_task_ids == EXPECTED_PRELUDE_TASK_IDS


def test_prelude_commands_match_snapshot(patched_proxmox, tmp_path):
    plan = _plan(tmp_path)
    tasks_by_id = {t.task_id: t for t in plan.prelude_tasks}

    # vm.ensure_running is handled separately as EnsureVmRunning and does not
    # appear in prelude_tasks as a CommandTask.
    assert "vm.ensure_running" not in tasks_by_id
    # functions.register is a CallableTask: only identity is asserted.
    assert getattr(tasks_by_id["functions.register"], "spec", None) is None

    for step_id, expected in EXPECTED_PRELUDE_COMMANDS.items():
        spec = tasks_by_id[step_id].spec
        assert list(spec.argv) == expected["argv"], f"argv mismatch for {step_id}"
        assert dict(spec.env) == expected["env"], f"env mismatch for {step_id}"


def test_prelude_reproduces_proxmox_ansible_inventory_rewrite(patched_proxmox, tmp_path):
    """Migrated from test_recipe_execution_hooks: ansible -i / port / key rewrite.

    Every ansible prelude command targets the proxmox SSH endpoint host,
    appends ``-e ansible_port=PORT`` and carries ``--private-key KEY``.
    """
    plan = _plan(tmp_path)
    tasks_by_id = {t.task_id: t for t in plan.prelude_tasks}

    for step_id in _ANSIBLE_IDS:
        argv = list(tasks_by_id[step_id].spec.argv)
        assert argv[0] == "ansible-playbook", step_id
        assert argv[argv.index("-i") + 1] == f"{_HOST},", step_id
        assert "ansible_port=2222" in argv, step_id
        assert argv[argv.index("--private-key") + 1] == str(_KEY), step_id


def test_prelude_reproduces_proxmox_repo_sync_rewrite(patched_proxmox, tmp_path):
    """Migrated from test_recipe_execution_hooks: repo.sync_to_vm rsync rewrite."""
    plan = _plan(tmp_path)
    tasks_by_id = {t.task_id: t for t in plan.prelude_tasks}

    argv = list(tasks_by_id["repo.sync_to_vm"].spec.argv)
    assert argv[0] == "rsync"
    rsh = argv[argv.index("-e") + 1]
    assert f"-p {_SSH_PORT}" in rsh
    assert f"-i {_KEY}" in rsh
    assert argv[-1] == f"ubuntu@{_HOST}:/home/ubuntu/nanofaas/"


def test_prelude_replaces_proxmox_ansible_private_key(patched_proxmox, tmp_path):
    """Migrated from test_recipe_execution_hooks: the recipe's default --private-key
    is replaced by the proxmox key (no stale host key path survives)."""
    plan = _plan(tmp_path)
    tasks_by_id = {t.task_id: t for t in plan.prelude_tasks}

    argv = list(tasks_by_id["vm.provision_base"].spec.argv)
    assert "--private-key" in argv
    # exactly one --private-key, pointing at the proxmox key.
    assert argv.count("--private-key") == 1
    assert argv[argv.index("--private-key") + 1] == str(_KEY)
