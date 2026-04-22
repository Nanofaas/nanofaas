from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SETTINGS_GRADLE = ROOT / "settings.gradle"
JAVA_INCLUDE_PATTERN = re.compile(r"""include(?:\()?\s*['"]examples:java:([^'")]+)['"]\)?""")


def test_java_example_includes_point_to_existing_directories() -> None:
    content = SETTINGS_GRADLE.read_text(encoding="utf-8")

    missing = [
        f"examples/java/{name}"
        for name in JAVA_INCLUDE_PATTERN.findall(content)
        if not (ROOT / "examples" / "java" / name).is_dir()
    ]

    assert missing == [], (
        "settings.gradle includes Java example projects whose directories are missing: "
        + ", ".join(missing)
    )
