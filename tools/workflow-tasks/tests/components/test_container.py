import pytest

from workflow_tasks.components.container import managed_process_resource


class FakeProcess:
    def __init__(self) -> None:
        self.terminated = False
        self.killed = False

    def poll(self) -> None:
        return None

    def terminate(self) -> None:
        self.terminated = True

    def wait(self, timeout: float | None = None) -> int:
        return 0

    def kill(self) -> None:
        self.killed = True


def test_managed_process_is_released() -> None:
    process = FakeProcess()
    resource = managed_process_resource(
        task_id="container.start.control-plane",
        title="Start control plane",
        argv=("java", "-jar", "app.jar"),
        spawn=lambda *_args, **_kwargs: process,
        ready=lambda: True,
    )

    resource.run()
    resource.cleanup()

    assert process.terminated


def test_failed_readiness_does_not_leak_the_process() -> None:
    process = FakeProcess()
    resource = managed_process_resource(
        task_id="container.start.control-plane",
        title="Start control plane",
        argv=("java", "-jar", "app.jar"),
        spawn=lambda *_args, **_kwargs: process,
        ready=lambda: False,
        readiness_attempts=1,
        readiness_interval=0,
    )

    with pytest.raises(RuntimeError, match="did not become ready"):
        resource.run()

    assert process.terminated
