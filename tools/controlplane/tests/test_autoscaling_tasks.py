from __future__ import annotations

from dataclasses import dataclass

from controlplane_tool.autoscaling.tasks import VerifyAutoscalingReplicas


@dataclass
class _Result:
    return_code: int
    stdout: str
    stderr: str = ""


class _Runner:
    def __init__(self, values: list[str]) -> None:
        self.values = values
        self.commands: list[tuple[str, ...]] = []

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ):
        self.commands.append(argv)
        if not self.values:
            return _Result(return_code=0, stdout="0")
        return _Result(return_code=0, stdout=self.values.pop(0))


def test_verify_autoscaling_replicas_observes_scale_up_and_down(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["1", "1", "2", "2", "0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    summary = task.run()

    assert summary.max_replicas_observed == 2
    assert summary.final_desired_replicas == 0
    assert len(runner.commands) == 5
    assert all("kubectl" in " ".join(command) for command in runner.commands)


def test_verify_autoscaling_replicas_quotes_shell_arguments(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["2", "2", "0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas; touch /tmp/ns-pwned",
        deployment_name="fn-word-stats-java; touch /tmp/deploy-pwned",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=1,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    task.run()

    command = runner.commands[0][2]
    assert "'fn-word-stats-java; touch /tmp/deploy-pwned'" in command
    assert "'nanofaas; touch /tmp/ns-pwned'" in command


def test_verify_autoscaling_replicas_accepts_scale_down_on_final_poll(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["2", "2", "1", "0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=1,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    summary = task.run()

    assert summary.final_desired_replicas == 0


def test_verify_autoscaling_replicas_fails_when_scale_up_never_exceeds_one(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["1", "1", "1", "1"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    try:
        task.run()
    except RuntimeError as exc:
        assert "Scale-up not observed" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_verify_autoscaling_replicas_fails_when_scale_down_never_reaches_zero(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)
    runner = _Runner(["2", "2", "2", "2", "1"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
    )

    try:
        task.run()
    except RuntimeError as exc:
        assert "Scale-down to 0 not observed" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


class _FailingRunner:
    def __init__(self, stderr: str, return_code: int = 1) -> None:
        self.stderr = stderr
        self.return_code = return_code

    def run_vm_command(self, argv, *, env, remote_dir, dry_run):
        return _Result(return_code=self.return_code, stdout="", stderr=self.stderr)


def test_replica_probe_reports_missing_deployment_clearly() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_FailingRunner('Error from server (NotFound): deployments.apps "fn-x" not found'),
        namespace="nanofaas",
        deployment_name="fn-x",
        remote_dir="/home/ubuntu/mcFaas",
    )
    try:
        probe.desired_replicas()
    except RuntimeError as exc:
        assert "not found" in str(exc)
        assert "fn-x" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_replica_probe_propagates_kubectl_errors() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_FailingRunner("Unable to connect to the server: dial tcp: lookup ..."),
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    try:
        probe.ready_replicas()
    except RuntimeError as exc:
        assert "Unable to connect" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_replica_probe_treats_empty_jsonpath_output_as_zero() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_Runner([""]),  # readyReplicas is absent from status when 0
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    assert probe.ready_replicas() == 0


def test_replica_watcher_records_max_while_running() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe, ReplicaWatcher

    runner = _Runner(["1", "1", "2", "3", "2", "1"])
    probe = ReplicaProbe(
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    watcher = ReplicaWatcher(probe, poll_interval_seconds=0.01)

    watcher.start()
    import time as _time
    deadline = _time.time() + 2.0
    while watcher.max_observed < 3 and _time.time() < deadline:
        _time.sleep(0.01)
    watcher.stop()

    assert watcher.max_observed >= 3


def test_replica_watcher_survives_probe_errors() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe, ReplicaWatcher

    probe = ReplicaProbe(
        runner=_FailingRunner("Unable to connect to the server"),
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    watcher = ReplicaWatcher(probe, poll_interval_seconds=0.01)
    watcher.start()
    import time as _time
    _time.sleep(0.05)
    watcher.stop()  # must not raise; errors recorded, watcher keeps sampling

    assert watcher.max_observed == 0


def test_run_k6_with_replica_watch_starts_and_stops_watcher_around_run() -> None:
    from controlplane_tool.autoscaling.tasks import RunK6WithReplicaWatch

    events: list[str] = []

    class _FakeWatcher:
        max_observed = 2

        def start(self) -> None:
            events.append("watch.start")

        def stop(self) -> None:
            events.append("watch.stop")

    class _FakeRunK6:
        def run(self):
            events.append("k6.run")
            return "k6-result"

    task = RunK6WithReplicaWatch(
        task_id="autoscaling.run_k6",
        title="Run autoscaling k6",
        run_k6=_FakeRunK6(),
        watcher=_FakeWatcher(),
    )

    assert task.run() == "k6-result"
    assert events == ["watch.start", "k6.run", "watch.stop"]


def test_run_k6_with_replica_watch_stops_watcher_on_k6_failure() -> None:
    from controlplane_tool.autoscaling.tasks import RunK6WithReplicaWatch

    events: list[str] = []

    class _FakeWatcher:
        def start(self) -> None:
            events.append("watch.start")

        def stop(self) -> None:
            events.append("watch.stop")

    class _BoomRunK6:
        def run(self):
            raise RuntimeError("k6 exploded")

    task = RunK6WithReplicaWatch(
        task_id="autoscaling.run_k6",
        title="Run autoscaling k6",
        run_k6=_BoomRunK6(),
        watcher=_FakeWatcher(),
    )
    try:
        task.run()
    except RuntimeError:
        pass
    assert events == ["watch.start", "watch.stop"]


def test_verify_uses_watcher_max_and_skips_scale_up_polling(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)

    class _WatcherStub:
        max_observed = 3

    # Only the scale-down phase should hit kubectl: desired goes straight to 0.
    runner = _Runner(["0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
        watcher=_WatcherStub(),
    )

    summary = task.run()

    assert summary.max_replicas_observed == 3
    assert summary.final_desired_replicas == 0
    # One kubectl call total (the final desired check), no scale-up polling.
    assert len(runner.commands) == 1


def test_scale_up_failure_message_includes_watcher_probe_errors(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)

    class _WatcherStub:
        max_observed = 0
        errors = ["Unable to connect to the server: dial tcp"]

    runner = _Runner(["0", "0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=1,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
        watcher=_WatcherStub(),
    )

    try:
        task.run()
    except RuntimeError as exc:
        assert "Scale-up not observed" in str(exc)
        assert "Unable to connect" in str(exc)
        return
    raise AssertionError("expected RuntimeError")
