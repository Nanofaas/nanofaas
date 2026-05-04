from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_javascript_example_images_copy_local_sdk_dependency_target() -> None:
    dockerfiles = (
        ROOT / "examples" / "javascript" / "word-stats" / "Dockerfile",
        ROOT / "examples" / "javascript" / "json-transform" / "Dockerfile",
    )

    for dockerfile in dockerfiles:
        text = dockerfile.read_text(encoding="utf-8")
        assert "COPY --from=building /src/function-sdk-javascript /function-sdk-javascript" in text, (
            f"{dockerfile} must copy the local function SDK into the final image because "
            "npm installs nanofaas-function-sdk as a symlinked file dependency."
        )
