# Shim: re-exports from workflow_tasks.infra.ansible (migrated in sub-project 1).
from __future__ import annotations

from workflow_tasks.infra.ansible import AnsibleAdapter, HostResolver

__all__ = ["AnsibleAdapter", "HostResolver"]
