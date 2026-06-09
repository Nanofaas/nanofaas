"""Tests for ProxmoxVmProvider."""
from __future__ import annotations

from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.proxmox import ProxmoxVmProvider


def _make_provider() -> ProxmoxVmProvider:
    return ProxmoxVmProvider(repo_root=Path("/repo"))


def _make_request(**kwargs: Any) -> VmRequest:
    defaults: dict[str, Any] = dict(
        lifecycle="proxmox",
        name="test-vm",
        user="ubuntu",
        proxmox_host="pve.example.com",
        proxmox_node="pve",
        proxmox_user="root@pam",
        proxmox_password="secret",
        proxmox_template_id=100,
        proxmox_ssh_key_path="/home/user/.ssh/id_ed25519",
    )
    defaults.update(kwargs)
    return VmRequest(**defaults)


def _make_proxmox_client_mock() -> tuple:
    client = MagicMock()
    vm = MagicMock()
    vm.vm_id = 123
    vm.wait_for_ip.return_value = "192.168.1.100"
    client.get_vm.return_value = vm
    return client, vm


def _mock_ssh_nat_rule(
    mock_routing_cls,
    *,
    host_port: int = 20000,
    vm_ip: str = "192.168.1.100",
) -> MagicMock:
    from proxmox_sdk.routing import PortMapping

    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip=vm_ip,
            vm_port=22,
            service="SSH",
            host_port=host_port,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    mock_routing_cls.from_password.return_value = mgr_mock
    return mgr_mock


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_root(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="root")
    assert provider.remote_home(req) == "/root"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_home_custom(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(home="/custom/home")
    assert provider.remote_home(req) == "/custom/home"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_remote_project_dir(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(user="ubuntu")
    assert provider.remote_project_dir(req) == "/home/ubuntu/nanofaas"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_uses_name_field(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(name="custom-vm")
    assert provider._vm_name(req) == "custom-vm"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_vm_name_default(mock_client_cls) -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="proxmox", proxmox_password="secret")
    assert provider._vm_name(req) == "nanofaas-proxmox"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_key_from_request(mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path="/home/user/.ssh/id_ed25519")
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_ed25519")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
@patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=Path("/home/user/.ssh/id_rsa"))
def test_ssh_key_fallback(mock_find, mock_client_cls) -> None:
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    key = provider._ssh_key(req)
    assert key == Path("/home/user/.ssh/id_rsa")


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_endpoint_returns_published_endpoint(mock_client_cls, monkeypatch) -> None:
    provider = _make_provider()
    req = _make_request()
    monkeypatch.setattr(provider, "_ssh_endpoint", lambda request: ("149.132.176.73", 20001))

    assert provider.ssh_endpoint(req) == ("149.132.176.73", 20001)


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_endpoint_republishes_when_cached_nat_rule_is_missing(
    mock_client_cls,
    mock_routing_cls,
) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = []
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20001,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    provider._ssh_endpoints["test-vm"] = ("pve.example.com", 20000)
    req = _make_request(proxmox_host="pve.example.com")

    endpoint = provider.ssh_endpoint(req)

    assert endpoint == ("pve.example.com", 20001)
    mgr_mock.add_rules.assert_called_once()
    mapping = mgr_mock.add_rules.call_args[0][0][0]
    assert mapping == PortMapping(
        vm_id=123,
        vm_name="test-vm",
        vm_ip="10.0.2.27",
        vm_port=22,
        service="SSH",
    )


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ssh_endpoint_republishes_when_nat_rule_targets_stale_guest_ip(
    mock_client_cls,
    mock_routing_cls,
) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.99",
            vm_port=22,
            service="SSH",
            host_port=20000,
        )
    ]
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20001,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    endpoint = provider.ssh_endpoint(req)

    assert endpoint == ("pve.example.com", 20001)
    mgr_mock.add_rules.assert_called_once()


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_connection_host_returns_guest_ip(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    host = provider.connection_host(req)
    assert host == "192.168.1.100"
    client_mock.get_vm.assert_called_once_with("test-vm")
    vm_mock.wait_for_ip.assert_called_once()


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_guest_host_returns_guest_ip(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()

    assert provider.guest_host(req) == "192.168.1.100"


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_success(mock_client_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "stopped"
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    vm_mock.delete.assert_called_once()
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_removes_nat_rules_for_vm(mock_client_cls, mock_routing_cls) -> None:
    from proxmox_sdk.routing import PortMapping
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "stopped"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    matching_rule = PortMapping(vm_id=100, vm_name="test-vm", vm_ip="10.0.0.10", vm_port=22, service="SSH", host_port=20000)
    other_rule = PortMapping(vm_id=200, vm_name="other-vm", vm_ip="10.0.0.20", vm_port=22, service="SSH", host_port=20001)
    mgr_mock.list_rules.return_value = [matching_rule, other_rule]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0
    mgr_mock.remove_rules.assert_called_once_with([matching_rule])


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_stops_running_vm_before_delete(mock_client_cls, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "running"
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls)
    provider = _make_provider()
    req = _make_request()

    result = provider.teardown(req)

    assert result.return_code == 0
    vm_mock.stop.assert_called_once()
    vm_mock.delete.assert_called_once()


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_nat_failure_does_not_prevent_success(mock_client_cls, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    mock_routing_cls.from_key.side_effect = RuntimeError("SSH failed")
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_removes_nat_rules_when_delete_fails(mock_client_cls, mock_routing_cls) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "stopped"
    vm_mock.delete.side_effect = RuntimeError("VM is running - destroy failed")
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    matching_rule = PortMapping(
        vm_id=100,
        vm_name="test-vm",
        vm_ip="10.0.0.10",
        vm_port=22,
        service="SSH",
        host_port=20000,
    )
    mgr_mock.list_rules.return_value = [matching_rule]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request()

    with pytest.raises(RuntimeError, match="destroy failed"):
        provider.teardown(req)

    mgr_mock.remove_rules.assert_called_once_with([matching_rule])


@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_teardown_vm_not_found_is_ignored(mock_client_cls) -> None:
    from proxmox_sdk.exceptions import VmNotFoundError
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.info.return_value.state.value = "stopped"
    vm_mock.delete.side_effect = VmNotFoundError("gone")
    mock_client_cls.return_value = client_mock
    provider = _make_provider()
    req = _make_request()
    result = provider.teardown(req)
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.socket.create_connection")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running(mock_client_cls, mock_routing_cls, mock_subproc, mock_socket, tmp_path) -> None:
    from proxmox_sdk.routing import PortMapping
    mock_socket.return_value.__enter__ = MagicMock(return_value=MagicMock())
    mock_socket.return_value.__exit__ = MagicMock(return_value=False)
    mock_subproc.return_value.stdout = "status: done\n"
    mock_subproc.return_value.stderr = ""
    mock_subproc.return_value.returncode = 0
    client_mock = MagicMock()
    vm_mock = MagicMock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    client_mock.ensure_running.return_value = vm_mock
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(vm_id=123, vm_name="test-vm", vm_ip="10.0.2.27", vm_port=22, service="SSH", host_port=20000)
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    private_key = tmp_path / "id_ed25519"
    public_key = tmp_path / "id_ed25519.pub"
    private_key.write_text("private-key-placeholder", encoding="utf-8")
    public_key.write_text("ssh-ed25519 AAAA test@example\n", encoding="utf-8")
    req = _make_request(cpus=2, memory="4G", disk="20G", proxmox_ssh_key_path=str(private_key))
    result = provider.ensure_running(req)
    client_mock.ensure_running.assert_called_once_with(
        "test-vm",
        template_id=100,
        node="pve",
        cores=2,
        memory_mb=4096,
        disk_gb=20,
        cloud_init_config=client_mock.ensure_running.call_args.kwargs["cloud_init_config"],
    )
    cloud_init = client_mock.ensure_running.call_args.kwargs["cloud_init_config"]
    assert cloud_init.username == "ubuntu"
    assert cloud_init.ssh_keys == ["ssh-ed25519 AAAA test@example"]
    assert result.return_code == 0


@patch("workflow_tasks.vm.proxmox.socket.create_connection")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_waits_ready_and_publishes_ssh_nat(mock_client_cls, mock_routing_cls, mock_subproc, mock_socket, tmp_path) -> None:
    from proxmox_sdk.routing import PortMapping

    mock_socket.return_value.__enter__ = MagicMock(return_value=MagicMock())
    mock_socket.return_value.__exit__ = MagicMock(return_value=False)
    mock_subproc.return_value.stdout = "status: done\n"
    mock_subproc.return_value.stderr = ""
    mock_subproc.return_value.returncode = 0
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.node = "pve"
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    client_mock.ensure_running.return_value = vm_mock
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20000,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock

    provider = _make_provider()
    private_key = tmp_path / "id_ed25519"
    public_key = tmp_path / "id_ed25519.pub"
    private_key.write_text("private-key-placeholder", encoding="utf-8")
    public_key.write_text("ssh-ed25519 AAAA test@example\n", encoding="utf-8")
    req = _make_request(cpus=2, memory="4G", disk="20G", proxmox_ssh_key_path=str(private_key))

    result = provider.ensure_running(req)

    assert result.return_code == 0
    vm_mock.wait_ready.assert_called_once()
    vm_mock.wait_for_ip.assert_called_once()
    mgr_mock.add_rules.assert_called_once()
    mapping = mgr_mock.add_rules.call_args[0][0][0]
    assert mapping == PortMapping(
        vm_id=123,
        vm_name="test-vm",
        vm_ip="10.0.2.27",
        vm_port=22,
        service="SSH",
    )


@patch("workflow_tasks.vm.proxmox.socket.create_connection")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_allows_slow_proxmox_guest_agent(
    mock_client_cls,
    mock_routing_cls,
    mock_subproc,
    mock_socket,
    tmp_path,
) -> None:
    from proxmox_sdk.routing import PortMapping

    mock_socket.return_value.__enter__ = MagicMock(return_value=MagicMock())
    mock_socket.return_value.__exit__ = MagicMock(return_value=False)
    mock_subproc.return_value.stdout = "status: done\n"
    mock_subproc.return_value.stderr = ""
    mock_subproc.return_value.returncode = 0
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    client_mock.ensure_running.return_value = vm_mock
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20000,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    # Slow guest agent: wait_ready raises once before succeeding; the
    # ensure_running retry wrapper must tolerate it (no per-call timeout).
    vm_mock.wait_ready.side_effect = [RuntimeError("qemu guest agent not ready"), None]
    provider = _make_provider()
    private_key = tmp_path / "id_ed25519"
    public_key = tmp_path / "id_ed25519.pub"
    private_key.write_text("private-key-placeholder", encoding="utf-8")
    public_key.write_text("ssh-ed25519 AAAA test@example\n", encoding="utf-8")
    req = _make_request(proxmox_ssh_key_path=str(private_key))

    provider.ensure_running(req)

    # Retried until the slow agent became ready (called twice, no per-call timeout).
    assert vm_mock.wait_ready.call_count == 2
    vm_mock.wait_ready.assert_called_with()
    vm_mock.wait_for_ip.assert_called_once_with()


@patch("workflow_tasks.vm.proxmox.socket.create_connection")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_ensure_running_passes_configured_ssh_public_key_to_cloud_init(
    mock_client_cls,
    mock_routing_cls,
    mock_subproc,
    mock_socket,
    tmp_path,
) -> None:
    from proxmox_sdk.routing import PortMapping

    mock_socket.return_value.__enter__ = MagicMock(return_value=MagicMock())
    mock_socket.return_value.__exit__ = MagicMock(return_value=False)
    mock_subproc.return_value.stdout = "status: done\n"
    mock_subproc.return_value.stderr = ""
    mock_subproc.return_value.returncode = 0
    private_key = tmp_path / "id_ed25519"
    public_key = tmp_path / "id_ed25519.pub"
    private_key.write_text("private-key-placeholder", encoding="utf-8")
    public_key.write_text("ssh-ed25519 AAAA test@example\n", encoding="utf-8")
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    client_mock.ensure_running.return_value = vm_mock
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.27",
            vm_port=22,
            service="SSH",
            host_port=20000,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=str(private_key))

    result = provider.ensure_running(req)

    assert result.return_code == 0
    cloud_init = client_mock.ensure_running.call_args.kwargs["cloud_init_config"]
    assert cloud_init.username == "ubuntu"
    assert cloud_init.ssh_keys == ["ssh-ed25519 AAAA test@example"]
    vm_mock.exec.assert_not_called()


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = "output"
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.exec_argv(req, ["echo", "hello"])

    assert result.return_code == 0
    assert result.stdout == "output"
    called_cmd = mock_subproc.call_args[0][0]
    assert called_cmd[0] == "ssh"
    assert "StrictHostKeyChecking=no" in called_cmd
    # Ephemeral VMs reuse the NAT host:port with fresh host keys; pin a throwaway
    # known_hosts so recreated VMs never collide with stale cached keys.
    assert "UserKnownHostsFile=/dev/null" in called_cmd
    assert "BatchMode=yes" in called_cmd
    assert called_cmd[-3:-1] == ["-p", "20022"] or ("-p" in called_cmd and "20022" in called_cmd)
    assert "ubuntu@pve.example.com" in called_cmd
    assert "echo hello" in called_cmd[-1]


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_with_cwd_and_env(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request()

    result = provider.exec_argv(req, ["ls"], cwd="/home/ubuntu", env={"FOO": "bar"})

    assert result.return_code == 0
    remote_cmd = mock_subproc.call_args[0][0][-1]
    assert "cd /home/ubuntu" in remote_cmd
    assert "FOO=bar" in remote_cmd
    assert "ls" in remote_cmd


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_exec_argv_failure_includes_proxmox_nat_diagnostic(
    mock_client_cls,
    mock_subproc,
    mock_routing_cls,
) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.27"
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022, vm_ip="10.0.2.27")
    proc = MagicMock()
    proc.returncode = 255
    proc.stdout = ""
    proc.stderr = "ssh: connect to host pve.example.com port 20022: Connection refused\n"
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.exec_argv(req, ["true"])

    assert "Connection refused" in result.stderr
    assert "Proxmox NAT diagnostic:" in result.stderr
    assert "vm=test-vm" in result.stderr
    assert "guest_ip=10.0.2.27" in result.stderr
    assert "SSH=20022->10.0.2.27:22" in result.stderr


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_to(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.transfer_to(req, source=Path("/local/file"), destination="/remote/file")

    assert result.return_code == 0
    cmd = result.command
    assert cmd[0] == "scp"
    assert "StrictHostKeyChecking=no" in cmd
    assert "UserKnownHostsFile=/dev/null" in cmd
    assert "-P" in cmd and "20022" in cmd
    assert "/local/file" in cmd
    assert "ubuntu@pve.example.com:/remote/file" in cmd


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls, host_port=20022)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    result = provider.transfer_from(req, source="/remote/file", destination=Path("/local/file"))

    assert result.return_code == 0
    cmd = result.command
    assert cmd[0] == "scp"
    assert "StrictHostKeyChecking=no" in cmd
    assert "UserKnownHostsFile=/dev/null" in cmd
    assert "-P" in cmd and "20022" in cmd
    assert "ubuntu@pve.example.com:/remote/file" in cmd
    assert "/local/file" in cmd


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.subprocess.run")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_transfer_from_no_ssh_key(mock_client_cls, mock_subproc, mock_routing_cls) -> None:
    client_mock, vm_mock = _make_proxmox_client_mock()
    mock_client_cls.return_value = client_mock
    _mock_ssh_nat_rule(mock_routing_cls)
    proc = MagicMock()
    proc.returncode = 0
    proc.stdout = ""
    proc.stderr = ""
    mock_subproc.return_value = proc
    provider = _make_provider()
    req = _make_request(proxmox_ssh_key_path=None)
    with patch("workflow_tasks.vm.proxmox._find_ssh_private_key_path", return_value=None):
        result = provider.transfer_from(req, source="/remote/file", destination=Path("/local"))
    assert result.return_code == 0
    assert "-i" not in result.command


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_publish_port_returns_runner_facing_endpoint(mock_client_cls, mock_routing_cls) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.50"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = []
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.50",
            vm_port=30090,
            service="PROMETHEUS",
            host_port=20090,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    endpoint = provider.publish_port(req, service="PROMETHEUS", guest_port=30090)

    assert endpoint == ("pve.example.com", 20090)
    mgr_mock.add_rules.assert_called_once()


@patch("workflow_tasks.vm.proxmox.ProxmoxRoutingManager")
@patch("workflow_tasks.vm.proxmox.ProxmoxClient")
def test_publish_port_republishes_when_nat_rule_targets_stale_guest_ip(
    mock_client_cls,
    mock_routing_cls,
) -> None:
    from proxmox_sdk.routing import PortMapping

    client_mock, vm_mock = _make_proxmox_client_mock()
    vm_mock.vm_id = 123
    vm_mock.wait_for_ip.return_value = "10.0.2.50"
    mock_client_cls.return_value = client_mock
    mgr_mock = MagicMock()
    mgr_mock.list_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.99",
            vm_port=30090,
            service="PROMETHEUS",
            host_port=20090,
        )
    ]
    mgr_mock.add_rules.return_value = [
        PortMapping(
            vm_id=123,
            vm_name="test-vm",
            vm_ip="10.0.2.50",
            vm_port=30090,
            service="PROMETHEUS",
            host_port=20091,
        )
    ]
    mock_routing_cls.from_key.return_value = mgr_mock
    provider = _make_provider()
    req = _make_request(proxmox_host="pve.example.com")

    endpoint = provider.publish_port(req, service="PROMETHEUS", guest_port=30090)

    assert endpoint == ("pve.example.com", 20091)
    mgr_mock.add_rules.assert_called_once()
