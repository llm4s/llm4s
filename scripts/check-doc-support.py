#!/usr/bin/env python3
"""Check that the contributor-facing support claims match the build and CI."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def fail(message: str, failures: list[str]) -> None:
    failures.append(message)


def main(root: Path) -> int:
    claude = (root / "CLAUDE.md").read_text(encoding="utf-8")
    build = (root / "build.sbt").read_text(encoding="utf-8")
    deps = (root / "project" / "Dependencies.scala").read_text(encoding="utf-8")
    scope = (root / "docs" / "reference" / "v1-scope.md").read_text(encoding="utf-8")
    ci = (root / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    failures: list[str] = []

    documented_scala = re.search(r"Scala 3 only \(([^)]+)\)", scope)
    built_scala = re.search(r'val scala3\s*=\s*"([^"]+)"', deps)
    if not documented_scala or not built_scala:
        fail("could not locate the documented or configured Scala version", failures)
    elif documented_scala.group(1) != built_scala.group(1):
        fail(f"Scala version differs: docs={documented_scala.group(1)} build={built_scala.group(1)}", failures)

    documented_jdk = re.search(r"JDK (\d+)", claude)
    ci_jdks = set(re.findall(r"java-version:\s*['\"]?(\d+)", ci))
    if not documented_jdk or documented_jdk.group(1) not in ci_jdks:
        fail(f"JDK claim is not covered by CI: docs={documented_jdk.group(1) if documented_jdk else 'missing'} CI={sorted(ci_jdks)}", failures)

    for module in re.findall(r"^│   ├── ([^/`\s]+/?)", claude, re.MULTILINE):
        module = module.rstrip("/")
        if not (root / "modules" / module).is_dir():
            fail(f"documented module directory does not exist: modules/{module}", failures)
        module_in_build = f'file("modules/{module}")' in build or f'file("modules//{module}")' in build
        if not module_in_build and module != "workspace":
            nested = f'file("modules/{module}/'
            if nested not in build:
                fail(f"documented module is not represented in build.sbt: modules/{module}", failures)

    aliases = set(re.findall(r'addCommandAlias\("([^"]+)",', build))
    tasks = set(re.findall(r"(?:lazy val|val)\s+([A-Za-z][A-Za-z0-9]*)\s*=\s*(?:taskKey|inputKey)", build))
    known = aliases | tasks | {"compile", "test", "doc", "coverage", "scalafixAll", "scalafmtAll", "samples"}
    for command in re.findall(r"^sbt[ \t]+[`\"]?([A-Za-z][A-Za-z0-9]*)", claude, re.MULTILINE):
        if command not in known:
            fail(f"documented sbt command is not a known alias or task: {command}", failures)

    if failures:
        print("Documentation support check failed:")
        print("\n".join(f"- {failure}" for failure in failures))
        return 1
    print("Documentation support check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()))
