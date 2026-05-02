"""Bootstrap smoke test — verifies the package can be imported."""
import tui_toolkit


def test_package_imports():
    assert tui_toolkit.__version__ == "0.1.0"
