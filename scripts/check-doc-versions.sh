#!/usr/bin/env bash
# Fails when a docs page pins a literal llm4s version in an install snippet.
#
# The docs site sets `site.data.project.latest_release` from the latest published GitHub Release at
# deploy time (.github/workflows/pages.yml), so a snippet that uses
#   {{ site.data.project.latest_release }}
# is right the day a release is published. A literal `0.4.0` is right until the next release and then
# quietly sends new users to the old one - the installation guide already pinned 0.4.0 in its sbt and
# Maven snippets and 0.4.1 in its Gradle ones.
#
# A SNAPSHOT version is a locally built one, not a release, and is not checked.
# Release notes, the migration guides and design documents name versions on purpose and are not checked.
# Exit code 0 = no literal pin. Non-zero = file:line of each one.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import re, sys, pathlib

SKIP_DIRS = ("docs/migrations/", "docs/design/", "docs/_data/", "docs/_site/")
SKIP_FILES = {"docs/reference/migration.md", "docs/reference/release.md", "CHANGELOG.md"}

SBT    = re.compile(r'"org\.llm4s"\s*%+\s*"llm4s-[a-z0-9-]+"\s*%\s*"(\d+\.\d+\.\d+[^"]*)"')
GRADLE = re.compile(r'org\.llm4s:llm4s-[a-z0-9_-]+:(\d+\.\d+\.\d+[^\s"\')`]*)')
MAVEN  = re.compile(r'<artifactId>llm4s-[a-z0-9_-]+</artifactId>\s*<version>(\d+\.\d+\.\d+[^<]*)</version>')

bad = []
files = [p for p in pathlib.Path("docs").rglob("*.md")] + [pathlib.Path("README.md")]
for p in files:
    rel = p.as_posix()
    if rel in SKIP_FILES or rel.startswith(SKIP_DIRS) or not p.exists():
        continue
    text = p.read_text(encoding="utf-8")
    for rx in (SBT, GRADLE, MAVEN):
        for m in rx.finditer(text):
            if "SNAPSHOT" in m.group(1):
                continue          # a locally built version is not "the latest release"
            line = text.count("\n", 0, m.start(1)) + 1
            bad.append((rel, line, m.group(1)))

if bad:
    print("Literal llm4s version pinned in an install snippet:")
    for rel, line, v in sorted(bad):
        print(f"  {rel}:{line}: {v}")
    print("Use {{ site.data.project.latest_release }} instead: pages.yml sets it from the latest GitHub Release,")
    print("so the snippet cannot lag the release. See scripts/check-doc-versions.sh.")
    sys.exit(1)
print("No literal llm4s version pinned in an install snippet.")
PYEOF
