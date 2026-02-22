from pathlib import Path

from experiments.staging.image_cache import (
    CacheDecision,
    evaluate_image_cache,
    save_image_manifest,
)


def _manifest_path(tmp_path: Path) -> Path:
    return tmp_path / "versions" / "candidate" / "images" / "manifest.json"


def test_evaluate_image_cache_returns_hit_when_manifest_and_image_id_match(tmp_path: Path):
    manifest_path = _manifest_path(tmp_path)
    save_image_manifest(
        manifest_path,
        {
            "modes": {
                "jvm": {
                    "image_ref": "nanofaas/control-plane:test",
                    "image_id": "sha256:abc",
                    "build_fingerprint": "build-fp",
                    "snapshot_fingerprint": "snapshot-fp",
                }
            }
        },
    )

    decision = evaluate_image_cache(
        manifest_path=manifest_path,
        mode="jvm",
        build_fingerprint="build-fp",
        snapshot_fingerprint="snapshot-fp",
        image_id_lookup=lambda image_ref: "sha256:abc",
    )

    assert isinstance(decision, CacheDecision)
    assert decision.rebuild is False
    assert decision.reason == "cache-hit"
    assert decision.entry["image_ref"] == "nanofaas/control-plane:test"


def test_evaluate_image_cache_returns_miss_for_missing_or_mismatched_data(tmp_path: Path):
    manifest_path = _manifest_path(tmp_path)
    save_image_manifest(
        manifest_path,
        {
            "modes": {
                "native": {
                    "image_ref": "nanofaas/control-plane:native",
                    "image_id": "sha256:native",
                    "build_fingerprint": "fp-a",
                    "snapshot_fingerprint": "snap-a",
                }
            }
        },
    )

    missing_mode = evaluate_image_cache(
        manifest_path=manifest_path,
        mode="jvm",
        build_fingerprint="fp-a",
        snapshot_fingerprint="snap-a",
        image_id_lookup=lambda image_ref: "sha256:any",
    )
    mismatch = evaluate_image_cache(
        manifest_path=manifest_path,
        mode="native",
        build_fingerprint="fp-a",
        snapshot_fingerprint="snap-a",
        image_id_lookup=lambda image_ref: "sha256:different",
    )

    assert missing_mode.rebuild is True
    assert missing_mode.reason == "mode-missing"
    assert mismatch.rebuild is True
    assert mismatch.reason == "image-id-mismatch"


def test_evaluate_image_cache_force_rebuild_controls(tmp_path: Path):
    manifest_path = _manifest_path(tmp_path)
    save_image_manifest(
        manifest_path,
        {
            "modes": {
                "jvm": {
                    "image_ref": "nanofaas/control-plane:test",
                    "image_id": "sha256:abc",
                    "build_fingerprint": "build-fp",
                    "snapshot_fingerprint": "snapshot-fp",
                }
            }
        },
    )

    force_all = evaluate_image_cache(
        manifest_path=manifest_path,
        mode="jvm",
        build_fingerprint="build-fp",
        snapshot_fingerprint="snapshot-fp",
        image_id_lookup=lambda image_ref: "sha256:abc",
        force_rebuild_images=True,
    )
    force_mode = evaluate_image_cache(
        manifest_path=manifest_path,
        mode="jvm",
        build_fingerprint="build-fp",
        snapshot_fingerprint="snapshot-fp",
        image_id_lookup=lambda image_ref: "sha256:abc",
        force_rebuild_modes={"jvm"},
    )

    assert force_all.rebuild is True
    assert force_all.reason == "forced-all"
    assert force_mode.rebuild is True
    assert force_mode.reason == "forced-mode"
