from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_envelope_examples_have_unique_catalog_function_names():
    manifests = {
        "bash/qr-code": "qr-code-exec",
        "go/qr-code": "qr-code-go",
        "java/qr-code": "qr-code-java",
        "javascript/qr-code": "qr-code-javascript",
        "python/qr-code": "qr-code-python",
        "bash/roman-numeral": "roman-numeral-exec",
        "go/roman-numeral": "roman-numeral-go",
        "java/roman-numeral": "roman-numeral-java",
        "javascript/roman-numeral": "roman-numeral-javascript",
        "python/roman-numeral": "roman-numeral-python",
    }

    for function, expected_name in manifests.items():
        manifest = (REPO_ROOT / "functions" / function / "function.yaml").read_text()
        assert f"name: {expected_name}\n" in manifest
