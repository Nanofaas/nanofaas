from __future__ import annotations

from pathlib import Path

from workflow_tasks.infra.ansible import bundled_ansible_root

_ANSIBLE_ROOT = bundled_ansible_root()


def test_provision_base_uses_ansible_facts_namespace_for_architecture() -> None:
    playbook = _ANSIBLE_ROOT / "playbooks" / "provision-base.yml"

    content = playbook.read_text(encoding="utf-8")

    assert "ansible_architecture" not in content
    assert 'ansible_facts["architecture"]' in content


def test_ansible_config_disables_deprecation_warnings() -> None:
    config = _ANSIBLE_ROOT / "ansible.cfg"

    content = config.read_text(encoding="utf-8")

    assert "deprecation_warnings = False" in content


def test_ansible_config_preserves_ssh_connection_multiplexing() -> None:
    config = _ANSIBLE_ROOT / "ansible.cfg"

    content = config.read_text(encoding="utf-8")

    assert "ControlMaster=auto" in content
    assert "ControlPersist=60s" in content
