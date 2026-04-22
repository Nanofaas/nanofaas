from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def _read(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def test_word_stats_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("examples/javascript/word-stats/Dockerfile")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert "RUN npm install" in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "COPY examples/javascript/word-stats /src/examples/javascript/word-stats" in dockerfile
    assert "WORKDIR /src/examples/javascript/word-stats" in dockerfile


def test_json_transform_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("examples/javascript/json-transform/Dockerfile")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert "RUN npm install" in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "COPY examples/javascript/json-transform /src/examples/javascript/json-transform" in dockerfile
    assert "WORKDIR /src/examples/javascript/json-transform" in dockerfile
