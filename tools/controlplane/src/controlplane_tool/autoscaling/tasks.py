from __future__ import annotations

from dataclasses import dataclass
import shlex
import time

from workflow_tasks.tasks.executors import VmCommandRunner


@dataclass(frozen=True)
class AutoscalingSummary:
    deployment_name: str
    max_replicas_observed: int
    final_desired_replicas: int


@dataclass
class VerifyAutoscalingReplicas:
    task_id: str
    title: str
    runner: VmCommandRunner
    namespace: str
    deployment_name: str
    remote_dir: str
    scale_up_polls: int = 24
    scale_down_initial_delay_seconds: int = 90
    scale_down_polls: int = 24
    poll_interval_seconds: int = 5

    def _replica_count(self, jsonpath: str) -> int:
        deployment = shlex.quote(self.deployment_name)
        namespace = shlex.quote(self.namespace)
        output = shlex.quote(f"jsonpath={jsonpath}")
        result = self.runner.run_vm_command(
            (
                "bash",
                "-lc",
                "kubectl get deployment "
                f"{deployment} -n {namespace} "
                f"-o {output} 2>/dev/null || echo 0",
            ),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "kubectl replica query failed")
        try:
            return int((result.stdout or "0").strip() or "0")
        except ValueError as exc:
            raise RuntimeError(f"invalid replica count: {result.stdout!r}") from exc

    def _ready_replicas(self) -> int:
        return self._replica_count("{.status.readyReplicas}")

    def _desired_replicas(self) -> int:
        return self._replica_count("{.spec.replicas}")

    def run(self) -> AutoscalingSummary:
        max_replicas = 0
        for _ in range(self.scale_up_polls):
            time.sleep(self.poll_interval_seconds)
            ready = self._ready_replicas()
            desired = self._desired_replicas()
            max_replicas = max(max_replicas, ready, desired)
            if max_replicas > 1:
                break

        if max_replicas <= 1:
            raise RuntimeError(f"Scale-up not observed: max replicas stayed at {max_replicas}")

        time.sleep(self.scale_down_initial_delay_seconds)
        final_desired = self._desired_replicas()
        if final_desired == 0:
            return AutoscalingSummary(
                deployment_name=self.deployment_name,
                max_replicas_observed=max_replicas,
                final_desired_replicas=final_desired,
            )
        for _ in range(self.scale_down_polls):
            time.sleep(self.poll_interval_seconds)
            final_desired = self._desired_replicas()
            if final_desired == 0:
                return AutoscalingSummary(
                    deployment_name=self.deployment_name,
                    max_replicas_observed=max_replicas,
                    final_desired_replicas=final_desired,
                )

        raise RuntimeError(f"Scale-down to 0 not observed: desired replicas = {final_desired}")
