"""Check that reduced Java-lite Docker contexts contain their Gradle project closure."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DockerProjectClosureTest(unittest.TestCase):
    def test_java_lite_project_dependencies_are_in_the_context(self):
        for name in ("word-stats-lite", "roman-numeral-lite", "json-transform-lite"):
            with self.subTest(function=name):
                directory = ROOT / "functions/java" / name
                settings = (directory / "settings.gradle.docker").read_text()
                included = {
                    ":" + value.lstrip(":")
                    for value in re.findall(r"include\(['\"]([^'\"]+)", settings)
                }
                copied = {}
                for source, destination in re.findall(
                    r"^COPY (\S+/) (\S+/)\s*$",
                    (directory / "Dockerfile").read_text(),
                    re.M,
                ):
                    if (ROOT / source / "build.gradle").exists():
                        copied[":" + destination.strip("/").replace("/", ":")] = (
                            ROOT / source
                        )
                for project in included:
                    self.assertIn(
                        project, copied, f"{name}: project {project} not copied"
                    )
                    dependencies = re.findall(
                        r"project\(['\"](:[^'\"]+)['\"]\)",
                        (copied[project] / "build.gradle").read_text(),
                    )
                    self.assertFalse(
                        set(dependencies) - included,
                        f"{name}/{project}: missing projects {set(dependencies) - included}",
                    )


if __name__ == "__main__":
    unittest.main()
