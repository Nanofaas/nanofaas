from __future__ import annotations

from pathlib import Path

from controlplane_tool.image_ops import ImageOps


def test_image_ops_build_and_push_commands_are_stable() -> None:
    ops = ImageOps(Path("/repo"))

    build = ops.build(
        image="localhost:5000/nanofaas/control-plane:e2e",
        context=Path("control-plane"),
        dockerfile=Path("control-plane/Dockerfile"),
    )
    push = ops.push("localhost:5000/nanofaas/control-plane:e2e")

    assert build.command == [
        "docker",
        "build",
        "-f",
        "control-plane/Dockerfile",
        "-t",
        "localhost:5000/nanofaas/control-plane:e2e",
        "control-plane",
    ]
    assert push.command == ["docker", "push", "localhost:5000/nanofaas/control-plane:e2e"]
