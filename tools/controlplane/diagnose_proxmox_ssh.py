#!/usr/bin/env python3
"""
Diagnostic script for Proxmox SSH connectivity.

Runs the first 4 prelude steps of proxmox-vm-loadtest using exactly the same
code path as the TUI (same request, same plan_recipe_steps, same runner._execute_step).
After each step it probes: NAT rule, VM state, TCP SSH reachability,
cloud-init status, sshd status.

Usage:
    cd tools/controlplane
    .venv/bin/python diagnose_proxmox_ssh.py [--no-cleanup] [--no-pause]
"""
from __future__ import annotations

import argparse
import socket
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).parent.parent.parent  # mcFaas/

from controlplane_tool.workspace.proxmox_config import load_proxmox_config
from controlplane_tool.cli.e2e_commands import _resolve_run_request
from controlplane_tool.e2e.e2e_runner import E2eRunner, E2ePlan, plan_recipe_steps
from controlplane_tool.scenario.catalog import resolve_scenario
from workflow_tasks.vm.models import VmRequest

_DIAG_COMPONENTS = (
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
)


# ---------------------------------------------------------------------------
# probe helpers
# ---------------------------------------------------------------------------

def _tcp_probe(host: str, port: int, timeout: float = 5.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _nat_ssh_rule(vm_request: VmRequest):
    try:
        from workflow_tasks.vm.proxmox import ProxmoxVmProvider
        provider = ProxmoxVmProvider(repo_root=REPO_ROOT)
        mgr = provider._routing_manager(vm_request)
        name = vm_request.name or "nanofaas-proxmox"
        rules = [r for r in mgr.list_rules() if r.vm_name == name and r.service == "SSH"]
        return rules[0] if rules else None
    except Exception as exc:
        return f"<error: {exc}>"


def _vm_state(vm_request: VmRequest) -> str:
    try:
        from proxmox_sdk import ProxmoxClient
        client = ProxmoxClient(
            host=vm_request.proxmox_host or "",
            user=vm_request.proxmox_user or "root@pam",
            password=vm_request.proxmox_password,
            node=vm_request.proxmox_node,
        )
        vm = client.get_vm(vm_request.name or "nanofaas-proxmox")
        return vm.info().state.value
    except Exception as exc:
        return f"<error: {exc}>"


def _ssh_run(host: str, port: int, key: str | None, user: str, cmd: str) -> str:
    ssh = [
        "ssh", "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=5", "-p", str(port),
    ]
    if key:
        ssh += ["-i", key]
    ssh += [f"{user}@{host}", cmd]
    try:
        result = subprocess.run(ssh, capture_output=True, text=True, timeout=15)
        out = (result.stdout + result.stderr).strip()
        return out if out else f"<exit {result.returncode}>"
    except Exception as exc:
        return f"<error: {exc}>"


# ---------------------------------------------------------------------------
# main probe
# ---------------------------------------------------------------------------

def probe(label: str, vm_request: VmRequest) -> None:
    host = vm_request.proxmox_host or ""
    user = vm_request.user or "ubuntu"
    key = vm_request.proxmox_ssh_key_path

    print(f"\n{'='*64}")
    print(f"  PROBE after: {label}")
    print(f"  Time: {time.strftime('%H:%M:%S')}")
    print(f"{'='*64}")

    # 1. NAT rule
    rule = _nat_ssh_rule(vm_request)
    if isinstance(rule, str):
        print(f"  NAT SSH rule : {rule}")
        ssh_port = None
    elif rule is None:
        print("  NAT SSH rule : MISSING")
        ssh_port = None
    else:
        ssh_port = rule.host_port
        vm_ip = getattr(rule, "vm_ip", "?")
        print(f"  NAT SSH rule : {host}:{ssh_port} -> {vm_ip}:22  (vm_id={rule.vm_id})")

    # 2. VM state
    state = _vm_state(vm_request)
    print(f"  VM state     : {state}")

    # 3. TCP probe
    if ssh_port is not None:
        reachable = _tcp_probe(host, int(ssh_port))
        label_tcp = "OPEN" if reachable else "REFUSED/TIMEOUT"
        print(f"  SSH TCP probe: {label_tcp}  ({host}:{ssh_port})")

        if reachable:
            # 4a. cloud-init status
            ci = _ssh_run(host, int(ssh_port), key, user,
                          "cloud-init status 2>/dev/null || echo 'cloud-init not available'")
            print(f"  cloud-init   : {ci!r}")

            # 4b. sshd status
            sshd = _ssh_run(host, int(ssh_port), key, user,
                            "systemctl is-active ssh 2>/dev/null || systemctl is-active sshd 2>&1")
            print(f"  sshd status  : {sshd!r}")

            # 4c. last cloud-init log lines (if any)
            ci_log = _ssh_run(host, int(ssh_port), key, user,
                              "sudo tail -5 /var/log/cloud-init.log 2>/dev/null || echo 'no log'")
            print(f"  cloud-init log (last 5):\n    {ci_log}")
        else:
            print("  (skipping SSH commands — port unreachable)")
    else:
        print("  SSH TCP probe: SKIPPED (no NAT rule)")


# ---------------------------------------------------------------------------
# entrypoint
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-cleanup", action="store_true",
                        help="Keep VM alive after run (default: destroy)")
    parser.add_argument("--no-pause", action="store_true",
                        help="Run all steps without pausing between them")
    args = parser.parse_args()

    cfg = load_proxmox_config()

    request = _resolve_run_request(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        lifecycle="proxmox",
        name=cfg.vm_name,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="20G",
        cleanup_vm=not args.no_cleanup,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv=None,
        scenario_file=None,
        saved_profile=None,
        loadgen_name=cfg.loadgen_name,
        loadgen_cpus=2,
        loadgen_memory="2G",
        loadgen_disk="10G",
        proxmox_host=cfg.host,
        proxmox_node=cfg.node,
        proxmox_user=cfg.user,
        proxmox_password=cfg.password,
        proxmox_template_id=cfg.template_id,
        proxmox_ssh_key_path=cfg.ssh_key_path,
    )

    vm_request: VmRequest = request.vm  # type: ignore[assignment]

    runner = E2eRunner(repo_root=REPO_ROOT)
    steps = plan_recipe_steps(
        REPO_ROOT,
        request,
        "proxmox-vm-loadtest",
        component_ids=_DIAG_COMPONENTS,
    )

    scenario = resolve_scenario("proxmox-vm-loadtest")
    plan = E2ePlan(scenario=scenario, request=request, steps=steps)

    print("\nProxmox diagnostic run")
    print(f"  Host     : {cfg.host}")
    print(f"  Node     : {cfg.node}")
    print(f"  VM       : {cfg.vm_name}")
    print(f"  Steps    : {len(steps)} ({', '.join(s.summary for s in steps)})")
    print(f"  Cleanup  : {not args.no_cleanup}")

    ip_cache: dict = {}
    failed = False

    for i, step in enumerate(steps, start=1):
        print(f"\n>>> [{i}/{len(steps)}] {step.summary}")
        t0 = time.monotonic()
        try:
            runner._execute_step(plan, i, len(steps), step, ip_cache)
            elapsed = time.monotonic() - t0
            print(f"    OK  ({elapsed:.1f}s)")
        except Exception as exc:
            elapsed = time.monotonic() - t0
            print(f"    FAIL ({elapsed:.1f}s): {exc}")
            failed = True

        probe(step.summary, vm_request)

        if failed:
            print("\n  Step failed — stopping here.")
            break

        if not args.no_pause and i < len(steps):
            try:
                input("\n  Press Enter to continue to next step (Ctrl-C to abort)... ")
            except KeyboardInterrupt:
                print("\n  Aborted by user.")
                break

    if not args.no_cleanup:
        print("\n  Destroying VM and NAT rules...")
        from workflow_tasks.vm.proxmox import ProxmoxVmProvider
        provider = ProxmoxVmProvider(repo_root=REPO_ROOT)
        for name, req in [(cfg.vm_name, vm_request), (cfg.loadgen_name, VmRequest(
            lifecycle="proxmox",
            name=cfg.loadgen_name,
            proxmox_host=cfg.host,
            proxmox_node=cfg.node,
            proxmox_user=cfg.user,
            proxmox_password=cfg.password,
            proxmox_template_id=cfg.template_id,
            proxmox_ssh_key_path=cfg.ssh_key_path,
        ))]:
            try:
                provider.teardown(req)
                print(f"  {name}: destroyed.")
            except Exception as exc:
                print(f"  {name}: cleanup failed: {exc}")

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
