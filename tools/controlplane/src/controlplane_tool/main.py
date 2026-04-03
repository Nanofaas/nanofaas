from __future__ import annotations

import typer

app = typer.Typer(
    help="Control-plane orchestration product for build, test, and reporting."
)


@app.callback()
def entrypoint() -> None:
    """Control-plane orchestration CLI entrypoint."""


def main() -> None:
    app()


if __name__ == "__main__":
    main()
