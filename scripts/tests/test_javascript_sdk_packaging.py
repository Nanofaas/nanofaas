import json
import os
import re
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_JSON = REPO_ROOT / "function-sdk-javascript" / "package.json"
BUILD_GRADLE = REPO_ROOT / "build.gradle"


def test_javascript_sdk_package_metadata_is_publishable() -> None:
    package = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))
    repo_version = re.search(
        r"version\s*=\s*'([^']+)'",
        BUILD_GRADLE.read_text(encoding="utf-8"),
    ).group(1)

    assert package["name"] == "nanofaas-function-sdk"
    assert package.get("private") is False
    assert package["version"] == repo_version
    assert package["files"] == ["dist", "README.md"]
    assert package["scripts"]["prepack"] == "npm run build"
    assert package["publishConfig"]["access"] == "public"
    assert package["exports"]["."]["types"] == "./dist/index.d.ts"
    assert package["exports"]["."]["default"] == "./dist/index.js"


def test_javascript_sdk_pack_dry_run_is_dist_only(tmp_path: Path) -> None:
    env = os.environ.copy()
    env["npm_config_cache"] = str(tmp_path / "npm-cache")
    result = subprocess.run(
        ["npm", "pack", "--dry-run"],
        cwd=REPO_ROOT / "function-sdk-javascript",
        env=env,
        text=True,
        capture_output=True,
        check=True,
    )
    output = result.stdout + result.stderr

    assert "dist/index.js" in output
    assert "README.md" in output
    assert "src/runtime.ts" not in output
    assert "test/runtime.contract.test.ts" not in output
    assert "build-test/src/runtime.js" not in output
