import typer

app = typer.Typer(add_completion=False, help="Scaffold a new nanofaas function project.", invoke_without_command=True)


@app.command()
def scaffold() -> None:
    """Initialize a new nanofaas function."""
    pass


def main() -> None:
    app()
