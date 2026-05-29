# Shim: re-exports from workflow_tasks.components.verification (migrated in sub-project 2b.3).
from __future__ import annotations

from workflow_tasks.components.verification import (
    plan_autoscaling_experiment,
    plan_loadtest_run,
    plan_run_k3s_curl_checks,
    plan_run_k8s_junit,
    plan_verify_cli_platform_status_fails,
)

__all__ = [
    "plan_autoscaling_experiment",
    "plan_loadtest_run",
    "plan_run_k3s_curl_checks",
    "plan_run_k8s_junit",
    "plan_verify_cli_platform_status_fails",
]
