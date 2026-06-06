from __future__ import annotations

import workflow_tasks.infra.host_sleep as host_sleep
from workflow_tasks.infra.host_sleep import prevent_host_sleep


class _FakeProc:
    def __init__(self) -> None:
        self.terminated = False
        self._running = True

    def poll(self):
        return None if self._running else 0

    def terminate(self) -> None:
        self.terminated = True
        self._running = False

    def wait(self, timeout=None) -> int:
        return 0


def test_prevent_host_sleep_caffeinates_on_macos(monkeypatch) -> None:
    monkeypatch.setattr(host_sleep.sys, "platform", "darwin")
    calls: list[list[str]] = []
    proc = _FakeProc()

    def fake_popen(cmd):
        calls.append(cmd)
        return proc

    monkeypatch.setattr(host_sleep.subprocess, "Popen", fake_popen)

    with prevent_host_sleep():
        assert calls == [host_sleep._CAFFEINATE_CMD]
        assert proc.terminated is False

    assert proc.terminated is True  # released on exit


def test_prevent_host_sleep_is_noop_off_macos(monkeypatch) -> None:
    monkeypatch.setattr(host_sleep.sys, "platform", "linux")

    def boom(cmd):
        raise AssertionError("caffeinate must not run off macOS")

    monkeypatch.setattr(host_sleep.subprocess, "Popen", boom)

    with prevent_host_sleep():
        pass  # no exception => no Popen attempted
