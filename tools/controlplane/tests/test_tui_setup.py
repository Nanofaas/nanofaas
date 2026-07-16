from controlplane_tool.tui.setup import NANOFAAS_BRAND, setup_ui
from tui_toolkit.context import get_ui


def test_setup_ui_installs_nanofaas_brand_once() -> None:
    setup_ui()
    assert get_ui().brand is NANOFAAS_BRAND
    assert get_ui().brand.wordmark == "NANOFAAS"
    assert get_ui().brand.ascii_logo.count("NANOFAAS") == 0
    assert len(get_ui().brand.ascii_logo.splitlines()) == 6
