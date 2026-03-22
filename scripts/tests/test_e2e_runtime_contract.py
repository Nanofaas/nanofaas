from __future__ import annotations

import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"


def run_shell(script: str, *, env: dict[str, str] | None = None) -> str:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)

    proc = subprocess.run(
        ["bash", "-lc", script],
        text=True,
        capture_output=True,
        check=True,
        env=merged_env,
    )
    return proc.stdout.strip()


def test_runtime_kind_and_rust_detection_behave_as_expected():
    source = f"source '{SCRIPT}'"

    out = run_shell(f"{source}; e2e_runtime_kind")
    assert out == "java"

    out = run_shell(
        f"{source}; e2e_runtime_kind",
        env={"CONTROL_PLANE_RUNTIME": "RUST"},
    )
    assert out == "rust"

    out = run_shell(
        f"{source}; if e2e_is_rust_runtime; then echo rust; else echo java; fi",
        env={"CONTROL_PLANE_RUNTIME": "rust"},
    )
    assert out == "rust"

    out = run_shell(
        f"{source}; if e2e_is_rust_runtime; then echo rust; else echo java; fi",
        env={"CONTROL_PLANE_RUNTIME": "invalid"},
    )
    assert out == "java"


def test_build_dispatch_helpers_route_to_runtime_specific_paths():
    source = f"source '{SCRIPT}'"
    cmd = (
        f"{source}; "
        "e2e_require_vm_exec(){ return 0; }; "
        "vm_exec(){ echo VMEXEC:$*; }; "
        "e2e_build_core_jars(){ echo CORE_JARS:$1; }; "
        "e2e_build_function_runtime_jar(){ echo FR_JAR:$1; }; "
        "e2e_build_rust_control_plane_image(){ echo RUST_IMG:$1:$2; }; "
        "CONTROL_PLANE_RUNTIME=java; "
        "e2e_build_control_plane_artifacts /tmp/a; "
        "e2e_build_control_plane_image /tmp/a cp-java:test; "
        "CONTROL_PLANE_RUNTIME=rust; "
        "e2e_build_control_plane_artifacts /tmp/b; "
        "e2e_build_control_plane_image /tmp/b cp-rust:test"
    )
    out = run_shell(cmd)
    lines = out.splitlines()

    assert "CORE_JARS:/tmp/a" in lines
    assert any("control-plane/Dockerfile control-plane/" in line for line in lines)
    assert "FR_JAR:/tmp/b" in lines
    assert "RUST_IMG:/tmp/b:cp-rust:test" in lines


def test_sync_env_renderer_emits_both_aliases_and_can_be_empty():
    source = f"source '{SCRIPT}'"

    enabled = run_shell(f"{source}; e2e_render_control_plane_sync_env true")
    assert "- name: SYNC_QUEUE_ENABLED" in enabled
    assert "- name: NANOFAAS_SYNC_QUEUE_ENABLED" in enabled
    assert 'value: "true"' in enabled

    empty = run_shell(
        f"{source}; "
        "if [ -n \"$(e2e_render_control_plane_sync_env '')\" ]; then echo non-empty; else echo empty; fi"
    )
    assert empty == "empty"


def test_host_temp_files_live_under_home_for_snap_multipass_compatibility():
    source = f"source '{SCRIPT}'"

    out = run_shell(
        f"{source}; "
        "tmp=$(e2e_mktemp_file nanofaas-test .yaml); "
        "printf '%s\\n' \"$tmp\"; "
        "rm -f \"$tmp\""
    )

    assert Path(out).parent == Path.home() / "nanofaas-e2e-tmp"
    assert Path(out).name.startswith("nanofaas-test.")
    assert out.endswith(".yaml")


def test_external_vm_helper_contract_resolves_expected_paths():
    source = f"source '{SCRIPT}'"
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


def test_external_vm_lifecycle_checks_ssh_and_skips_multipass_prereq():
    source = f"source '{SCRIPT}'"

    out = run_shell(
        f"{source}; "
        "E2E_VM_LIFECYCLE=external; "
        "E2E_VM_HOST=vm.example.test; "
        "e2e_ssh_exec(){ printf '%s|%s' \"$1\" \"$2\"; }; "
        "e2e_require_vm_access && e2e_ensure_vm_running nanofaas-e2e 4 8G 30G"
    )

    assert "Using externally managed VM host vm.example.test" in out
    assert "vm.example.test|true" in out


def test_external_vm_prerequisite_validation_fails_without_resolved_host():
    source = f"source '{SCRIPT}'"

    proc = subprocess.run(
        [
            "bash",
            "-lc",
            (
                f"source '{SCRIPT}'; "
                "E2E_VM_LIFECYCLE=external; "
                "e2e_require_vm_access"
            ),
        ],
        text=True,
        capture_output=True,
        env=os.environ.copy(),
    )

    assert proc.returncode != 0
    assert "E2E_VM_HOST is required when E2E_VM_LIFECYCLE=external" in proc.stderr or "Cannot determine VM host" in proc.stderr


def test_external_vm_host_override_is_used_by_vm_exec_and_url_resolution():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "E2E_VM_HOST=vm.example.test; "
        "VM_NAME=nanofaas-e2e; "
        "multipass(){ return 1; }; "
        "e2e_get_vm_backend(){ echo ssh; }; "
        "e2e_ssh_exec(){ printf '%s' \"$1\"; }; "
        "e2e_scp_to_vm(){ printf '%s' \"$2\"; }; "
        "e2e_scp_from_vm(){ printf '%s' \"$1\"; }; "
        "warn(){ printf '%s\\n' \"$*\"; }; "
        "host=$(e2e_vm_exec 'echo ok'); "
        "url=$(e2e_resolve_nanofaas_url 30080); "
        "copy_to=$(e2e_copy_to_vm /tmp/src nanofaas-e2e /tmp/dest); "
        "copy_from=$(e2e_copy_from_vm nanofaas-e2e /tmp/src /tmp/dest); "
        "cleanup=$(KEEP_VM=true e2e_cleanup_vm | grep '^  SSH:' || true); "
        "printf '%s|%s|%s|%s|%s' \"$host\" \"$url\" \"$copy_to\" \"$copy_from\" \"$cleanup\""
    )
    assert "vm.example.test|http://vm.example.test:30080|vm.example.test|vm.example.test" in out
    assert "SSH:    ssh ubuntu@vm.example.test" in out


def test_public_host_override_controls_nodeport_urls():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "E2E_VM_LIFECYCLE=external; "
        "E2E_VM_HOST=vm.example.test; "
        "E2E_PUBLIC_HOST=api.example.test; "
        "printf '%s|%s' "
        "\"$(e2e_get_public_host)\" "
        "\"$(e2e_resolve_nanofaas_url 30080)\""
    )
    assert out == "api.example.test|http://api.example.test:30080"


def test_export_kubeconfig_to_host_rewrites_server_using_override_when_provided():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "tmp_dir=$(mktemp -d); "
        "src=${tmp_dir}/remote-config; "
        "dest=${tmp_dir}/host-config; "
        "cat > \"${src}\" <<'EOF'\n"
        "apiVersion: v1\n"
        "clusters:\n"
        "- cluster:\n"
        "    server: https://127.0.0.1:6443\n"
        "  name: default\n"
        "EOF\n"
        "E2E_VM_LIFECYCLE=external; "
        "E2E_VM_HOST=vm.example.test; "
        "E2E_KUBECONFIG_SERVER=https://api.example.test:6443; "
        "e2e_copy_from_vm(){ cp \"${src}\" \"$3\"; }; "
        "e2e_export_kubeconfig_to_host nanofaas-e2e \"${dest}\"; "
        "cat \"${dest}\"; "
        "rm -rf \"${tmp_dir}\""
    )
    assert "https://api.example.test:6443" in out
    assert "https://127.0.0.1:6443" not in out


def test_vm_exec_shell_quotes_kubeconfig_path_with_spaces():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "E2E_VM_HOST=vm.example.test; "
        "E2E_KUBECONFIG_PATH='/srv/dev/kube config/config'; "
        "e2e_get_vm_backend(){ echo ssh; }; "
        "e2e_ssh_exec(){ printf '%s' \"$2\"; }; "
        "e2e_vm_exec 'printf \"%s\" \"$KUBECONFIG\"'"
    )
    assert "/srv/dev/kube\\\\\\ config/config" in out


def test_install_k3s_routes_helper_resolved_values_through_ansible():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "E2E_VM_HOST=vm.example.test; "
        "E2E_VM_USER=dev; "
        "E2E_KUBECONFIG_PATH='/srv/dev/custom kube/config'; "
        "K3S_VERSION='v1.35.1+k3s1'; "
        "e2e_require_vm_access(){ return 0; }; "
        "e2e_run_ansible_playbook(){ printf '%s\\n' \"$@\"; }; "
        "e2e_install_k3s"
    )
    lines = out.splitlines()

    assert any("playbooks/provision-k3s.yml" in line for line in lines)
    assert "vm_user=dev" in lines
    assert "kubeconfig_path=/srv/dev/custom kube/config" in lines
    assert "k3s_version_override=v1.35.1+k3s1" in lines


def test_ansible_helper_paths_are_derived_from_repo_and_home():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "printf '%s|%s|%s' "
        "\"$(e2e_get_ansible_root)\" "
        "\"$(e2e_get_ansible_venv_dir)\" "
        "\"$(e2e_get_ansible_bin)\""
    )

    ansible_root, ansible_venv, ansible_bin = out.split("|")
    assert ansible_root.endswith("/scripts/ansible")
    assert ansible_venv.startswith(str(Path.home()))
    assert ansible_bin.endswith("/bin/ansible-playbook")


def test_ansible_inventory_writer_uses_external_vm_identity_and_ssh_key():
    source = f"source '{SCRIPT}'"
    out = run_shell(
        f"{source}; "
        "tmp=$(mktemp); "
        "E2E_VM_LIFECYCLE=external; "
        "E2E_VM_HOST=vm.example.test; "
        "E2E_VM_USER=dev; "
        "E2E_SSH_KEY=/tmp/test-id; "
        "e2e_write_ansible_inventory \"$tmp\"; "
        "cat \"$tmp\"; "
        "rm -f \"$tmp\""
    )

    assert "ansible_host: vm.example.test" in out
    assert "ansible_user: dev" in out
    assert "ansible_ssh_private_key_file: /tmp/test-id" in out
