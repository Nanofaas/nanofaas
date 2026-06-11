from __future__ import annotations

from dataclasses import dataclass
import shlex
import threading
import time

from workflow_tasks.tasks.executors import VmCommandRunner


@dataclass(frozen=True)
class AutoscalingSummary:
    deployment_name: str
    max_replicas_observed: int
    final_desired_replicas: int


@dataclass(frozen=True)
class ReplicaProbe:
    """Reads deployment replica counts over the VM command runner.

    Errors are surfaced, not masked: a missing deployment and an unreachable
    cluster must be distinguishable from "0 replicas" when diagnosing a run.
    """

    runner: VmCommandRunner
    namespace: str
    deployment_name: str
    remote_dir: str

    def ready_replicas(self) -> int:
        return self._replica_count("{.status.readyReplicas}")

    def desired_replicas(self) -> int:
        return self._replica_count("{.spec.replicas}")

    def _replica_count(self, jsonpath: str) -> int:
        deployment = shlex.quote(self.deployment_name)
        namespace = shlex.quote(self.namespace)
        output = shlex.quote(f"jsonpath={jsonpath}")
        result = self.runner.run_vm_command(
            (
                "bash",
                "-lc",
                # sudo: on k3s VMs /etc/rancher/k3s/k3s.yaml is root-readable only.
                f"sudo kubectl get deployment {deployment} -n {namespace} -o {output}",
            ),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            detail = (result.stderr or result.stdout or "").strip()
            if "NotFound" in detail:
                raise RuntimeError(
                    f"deployment {self.deployment_name!r} not found in namespace {self.namespace!r}: {detail}"
                )
            raise RuntimeError(detail or f"kubectl replica query failed (exit {result.return_code})")
        raw = (result.stdout or "").strip()
        if not raw:
            # jsonpath yields empty output when the field is absent (e.g. readyReplicas at 0).
            return 0
        try:
            return int(raw)
        except ValueError as exc:
            raise RuntimeError(f"invalid replica count: {result.stdout!r}") from exc


class ReplicaWatcher:
    """Samples deployment replicas on a background thread while load runs.

    Scale-up must be observed DURING the k6 run: checking afterwards only sees
    residual state and races the autoscaler's downscale cooldown.
    """

    def __init__(self, probe: ReplicaProbe, poll_interval_seconds: float = 2.0) -> None:
        self._probe = probe
        self._poll_interval = poll_interval_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._max_observed = 0
        self.errors: list[str] = []

    @property
    def max_observed(self) -> int:
        return self._max_observed

    def start(self) -> None:
        if self._thread is not None:
            raise RuntimeError("ReplicaWatcher already started")
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="replica-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._thread is None:
            return
        self._stop.set()
        self._thread.join()
        self._thread = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                ready = self._probe.ready_replicas()
                desired = self._probe.desired_replicas()
                self._max_observed = max(self._max_observed, ready, desired)
            except RuntimeError as exc:
                # A transient probe failure must not kill the watcher mid-load;
                # errors are kept for diagnostics.
                self.errors.append(str(exc))
            self._stop.wait(self._poll_interval)


@dataclass
class RunK6WithReplicaWatch:
    """Runs k6 while a ReplicaWatcher samples the target deployment."""

    task_id: str
    title: str
    run_k6: object
    watcher: object

    def run(self):
        self.watcher.start()
        try:
            return self.run_k6.run()
        finally:
            self.watcher.stop()


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
    watcher: object | None = None

    def _probe(self) -> ReplicaProbe:
        return ReplicaProbe(
            runner=self.runner,
            namespace=self.namespace,
            deployment_name=self.deployment_name,
            remote_dir=self.remote_dir,
        )

    def _ready_replicas(self) -> int:
        return self._probe().ready_replicas()

    def _desired_replicas(self) -> int:
        return self._probe().desired_replicas()

    def run(self) -> AutoscalingSummary:
        max_replicas = self.watcher.max_observed if self.watcher is not None else 0
        if max_replicas <= 1:
            # Fallback: no watcher (or it observed nothing) — poll residual state.
            for _ in range(self.scale_up_polls):
                time.sleep(self.poll_interval_seconds)
                ready = self._ready_replicas()
                desired = self._desired_replicas()
                max_replicas = max(max_replicas, ready, desired)
                if max_replicas > 1:
                    break

        if max_replicas <= 1:
            message = f"Scale-up not observed: max replicas stayed at {max_replicas}"
            watcher_errors = list(getattr(self.watcher, "errors", []) or [])
            if watcher_errors:
                message += f" (watcher probe errors: {watcher_errors[-1]!r}, {len(watcher_errors)} total)"
            raise RuntimeError(message)

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
