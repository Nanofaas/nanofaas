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
