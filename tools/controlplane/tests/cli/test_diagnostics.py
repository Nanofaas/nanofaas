from controlplane_tool.cli.diagnostics import REQUIRED_EXECUTABLES, missing_executables


def test_missing_executables_checks_the_shared_product_prerequisites(monkeypatch) -> None:
    checked: list[str] = []

    def which(name: str) -> str | None:
        checked.append(name)
        return "/usr/bin/ssh" if name == "ssh" else None

    monkeypatch.setattr("controlplane_tool.cli.diagnostics.shutil.which", which)

    assert REQUIRED_EXECUTABLES == ("docker", "ssh")
    assert missing_executables() == ["docker"]
    assert checked == ["docker", "ssh"]
