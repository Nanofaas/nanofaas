from __future__ import annotations

import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from controlplane_tool.images.plan import ImageCell, ImageFlavor, ImagePlan


def render_bake(
    plan: ImagePlan,
    *,
    selectors: Sequence[str] = (),
    flavors: Sequence[ImageFlavor] = ("jvm", "native", "default"),
) -> dict[str, Any]:
    """Render selected Dockerfile cells using Buildx Bake's JSON input schema."""
    requested = frozenset(selectors)
    known = plan.target_names
    unknown = sorted(requested - known)
    if unknown:
        raise ValueError(f"unknown image target: {', '.join(unknown)}")
    selected_flavors = frozenset(flavors)
    cells = tuple(
        cell
        for cell in plan.bake_cells
        if (not requested or cell.target.name in requested)
        and cell.flavor in selected_flavors
    )
    if requested and not cells:
        raise ValueError(f"selector has no Bake cells: {', '.join(sorted(requested))}")

    names_by_architecture = {
        architecture: [_bake_name(cell) for cell in cells if cell.architecture == architecture]
        for architecture in ("amd64", "arm64")
    }
    all_names = names_by_architecture["amd64"] + names_by_architecture["arm64"]
    return {
        "group": {
            "default": {"targets": all_names},
            "docker-amd64": {"targets": names_by_architecture["amd64"]},
            "docker-arm64": {"targets": names_by_architecture["arm64"]},
            "docker-all": {"targets": all_names},
        },
        "target": {_bake_name(cell): _bake_target(cell) for cell in cells},
    }


def render_bake_json(
    plan: ImagePlan,
    *,
    selectors: Sequence[str] = (),
    flavors: Sequence[ImageFlavor] = ("jvm", "native", "default"),
) -> str:
    return json.dumps(
        render_bake(plan, selectors=selectors, flavors=flavors),
        indent=2,
        ensure_ascii=False,
    ) + "\n"


def _bake_name(cell: ImageCell) -> str:
    return f"{cell.target.name}-{cell.architecture}-{cell.flavor}"


def _bake_target(cell: ImageCell) -> dict[str, list[str] | str]:
    dockerfile = cell.target.dockerfile
    if cell.target.context != Path("."):
        dockerfile = dockerfile.relative_to(cell.target.context)
    return {
        "context": cell.target.context.as_posix(),
        "dockerfile": dockerfile.as_posix(),
        "platforms": [cell.platform],
        "tags": [cell.image],
    }
