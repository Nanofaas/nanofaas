from types import SimpleNamespace

import controlplane_tool.app.main as main_module
import controlplane_tool.tui.app as tui_app
from controlplane_tool.tui.setup import NANOFAAS_BRAND, NANOFAAS_THEME, setup_ui
from tui_toolkit import AppBrand, Theme
from tui_toolkit.context import get_ui

EXPECTED_ASCII_LOGO = """\
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝"""


def test_setup_ui_installs_exact_nanofaas_context_idempotently() -> None:
    first = setup_ui()
    second = setup_ui()

    assert first.brand is second.brand is NANOFAAS_BRAND
    assert first.theme is second.theme is NANOFAAS_THEME
    assert get_ui() == second
    assert NANOFAAS_BRAND == AppBrand(
        name="nanofaas",
        wordmark="NANOFAAS",
        ascii_logo=EXPECTED_ASCII_LOGO,
        default_breadcrumb="Main",
        default_footer_hint="Esc back | Ctrl+C exit",
    )
    assert NANOFAAS_THEME == Theme()


def test_tui_command_sets_up_ui_before_running(monkeypatch) -> None:
    events: list[str] = []
    monkeypatch.setattr(main_module, "setup_ui", lambda: events.append("setup"))
    monkeypatch.setattr(
        tui_app,
        "NanofaasTUI",
        lambda: SimpleNamespace(run=lambda: events.append("run")),
    )

    main_module.tui()

    assert events == ["setup", "run"]


def test_default_main_sets_up_ui_before_running_tui(monkeypatch) -> None:
    events: list[str] = []
    monkeypatch.setattr(main_module, "setup_ui", lambda: events.append("setup"))
    monkeypatch.setattr(main_module.sys, "argv", ["controlplane-tool"])
    monkeypatch.setattr(
        tui_app,
        "NanofaasTUI",
        lambda: SimpleNamespace(run=lambda: events.append("run")),
    )

    main_module.main()

    assert events == ["setup", "run"]
