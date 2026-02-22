from __future__ import annotations

from dataclasses import dataclass
from typing import Any


ALLOWED_VERSION_STATUSES = (
    "staging",
    "candidate",
    "baseline",
    "rejected",
    "archived-baseline",
)
REQUIRED_VERSION_FIELDS = ("slug", "kind", "status", "parent", "created_at")


@dataclass(frozen=True)
class VersionMetadata:
    slug: str
    kind: str
    status: str
    parent: str
    created_at: str
    source_commit: str | None = None
    notes: str | None = None

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "VersionMetadata":
        for field_name in REQUIRED_VERSION_FIELDS:
            if field_name not in payload or payload[field_name] in (None, ""):
                raise ValueError(f"Missing required field: {field_name}")

        status = str(payload["status"])
        if status not in ALLOWED_VERSION_STATUSES:
            raise ValueError(f"Unsupported status: {status}")

        return cls(
            slug=str(payload["slug"]),
            kind=str(payload["kind"]),
            status=status,
            parent=str(payload["parent"]),
            created_at=str(payload["created_at"]),
            source_commit=_optional_string(payload.get("source_commit")),
            notes=_optional_string(payload.get("notes")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "slug": self.slug,
            "kind": self.kind,
            "status": self.status,
            "parent": self.parent,
            "created_at": self.created_at,
        }
        if self.source_commit:
            payload["source_commit"] = self.source_commit
        if self.notes:
            payload["notes"] = self.notes
        return payload


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text if text else None

