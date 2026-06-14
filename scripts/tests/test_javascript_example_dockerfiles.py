from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def _read(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def test_word_stats_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("functions/javascript/word-stats/Dockerfile")
    package_json = _read("functions/javascript/word-stats/package.json")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert dockerfile.count("RUN npm ci") == 2
    assert "RUN npm install" not in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "COPY functions/javascript/word-stats /src/functions/javascript/word-stats" in dockerfile
    assert "WORKDIR /src/functions/javascript/word-stats" in dockerfile
    assert "npm --prefix ../../../sdks/javascript install" in package_json


def test_json_transform_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("functions/javascript/json-transform/Dockerfile")
    package_json = _read("functions/javascript/json-transform/package.json")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert dockerfile.count("RUN npm ci") == 2
    assert "RUN npm install" not in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "COPY functions/javascript/json-transform /src/functions/javascript/json-transform" in dockerfile
    assert "WORKDIR /src/functions/javascript/json-transform" in dockerfile
    assert "npm --prefix ../../../sdks/javascript install" in package_json
