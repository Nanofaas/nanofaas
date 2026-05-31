# Shim: re-exports from workflow_tasks.components.namespace (migrated in sub-project 2b.1).
from __future__ import annotations

from workflow_tasks.components.namespace import (
    NAMESPACE_INSTALL,
    NAMESPACE_RELEASE_NAMESPACE,
    NAMESPACE_UNINSTALL,
    namespace_release_name,
    plan_install_namespace,
    plan_uninstall_namespace,
)

__all__ = [
    "NAMESPACE_INSTALL",
    "NAMESPACE_RELEASE_NAMESPACE",
    "NAMESPACE_UNINSTALL",
    "namespace_release_name",
    "plan_install_namespace",
    "plan_uninstall_namespace",
]
