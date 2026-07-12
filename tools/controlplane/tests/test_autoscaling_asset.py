from pathlib import Path


def test_autoscaling_k6_asset_has_no_embedded_stages() -> None:
    asset = Path(__file__).resolve().parents[1] / "assets/k6/autoscaling.js"
    content = asset.read_text(encoding="utf-8")

    assert "stages" not in content
    assert "http_req_failed" in content
