#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-doc-support.py")


class DocumentationSupportCheckTest(unittest.TestCase):
    def test_current_repository_passes(self):
        result = subprocess.run([sys.executable, str(SCRIPT), str(SCRIPT.parents[1])], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_stale_scala_claim_fails(self):
        root = Path(tempfile.mkdtemp())
        for directory in [root / "project", root / "docs/reference", root / ".github/workflows", root / "modules/core"]:
            directory.mkdir(parents=True)
        (root / "CLAUDE.md").write_text("**Tech Stack:** Scala 3.7.1, JDK 21\n├── modules/\n│   ├── core/\nsbt compile\n", encoding="utf-8")
        (root / "build.sbt").write_text('lazy val core = (project in file("modules/core"))\n', encoding="utf-8")
        (root / "project/Dependencies.scala").write_text('val scala3 = "3.6.0"\n', encoding="utf-8")
        (root / "docs/reference/v1-scope.md").write_text("Scala 3 only (3.7.1)", encoding="utf-8")
        (root / ".github/workflows/ci.yml").write_text("java-version: 21", encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), str(root)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Scala version differs", result.stdout)


if __name__ == "__main__":
    unittest.main()
