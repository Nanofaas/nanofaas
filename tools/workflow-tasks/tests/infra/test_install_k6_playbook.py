from __future__ import annotations

from workflow_tasks.infra.ansible import bundled_ansible_root


def _playbook_text() -> str:
    return (bundled_ansible_root() / "playbooks" / "install-k6.yml").read_text(encoding="utf-8")


def test_install_k6_uses_github_binary_not_arm64_broken_apt() -> None:
    """k6 must install from the GitHub release tarball, not the Grafana apt repo.

    The Grafana apt repo (dl.k6.io/deb) ships no arm64 package, so `apt install k6`
    fails on arm64 hosts (e.g. Apple-Silicon multipass) with "No package matching 'k6'".
    The release tarball handles amd64 AND arm64 — the arch-correct, proven path.
    """
    text = _playbook_text()
    # No apt-based install (assert on actual task/module usage, not prose mentions).
    assert "ansible.builtin.apt:" not in text
    assert "sources.list.d/k6.list" not in text
    assert "Install k6 via apt" not in text
    # Installs from the arch-correct GitHub release tarball (amd64 + arm64).
    assert "github.com/grafana/k6/releases/download" in text
    assert "linux-{{ k6_arch }}.tar.gz" in text
