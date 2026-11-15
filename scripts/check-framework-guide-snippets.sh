#!/usr/bin/env bash
# Fails when a code snippet of the Micronaut or Helidon guide is not in its sample (#2281).
#
# docs/guide/micronaut.md and docs/guide/helidon.md show excerpts of modules/samples/micronaut-java and
# modules/samples/helidon-java, which CI builds and tests; this keeps them excerpts. A java, yaml or kotlin snippet
# passes when its lines, each with leading and trailing whitespace removed, appear as consecutive lines (compared the
# same way) of one file of its sample, so a snippet may be re-indented but not edited. Shell snippets (`bash`) are
# commands, not sample code, and are not checked. No network and no JDK needed.
# Exit code 0 = every snippet is in its sample. Non-zero = each snippet that is not, with its line in the guide.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import pathlib, re, sys

pairs = [
    ("docs/guide/micronaut.md", "modules/samples/micronaut-java"),
    ("docs/guide/helidon.md", "modules/samples/helidon-java"),
]
checked = ("java", "yaml", "kotlin")
suffixes = (".java", ".yml", ".yaml", ".kts")

def contains(haystack, needle):
    n = len(needle)
    return any(haystack[i:i + n] == needle for i in range(len(haystack) - n + 1))

bad = []
for guide_path, sample_path in pairs:
    guide, sample = pathlib.Path(guide_path), pathlib.Path(sample_path)
    files = {}
    for p in sorted(sample.rglob("*")):
        if p.is_file() and "build" not in p.parts and ".gradle" not in p.parts and p.suffix in suffixes:
            files[p.as_posix()] = [line.strip() for line in p.read_text(encoding="utf-8").splitlines()]
    count, before = 0, len(bad)
    lines = guide.read_text(encoding="utf-8").splitlines()
    i = 0
    while i < len(lines):
        m = re.match(r"^```(\w+)\s*$", lines[i])
        if not m:
            i += 1
            continue
        start, lang = i + 1, m.group(1)
        j = start
        while j < len(lines) and not lines[j].startswith("```"):
            j += 1
        if lang in checked:
            count += 1
            block = [line.strip() for line in lines[start:j]]
            while block and not block[-1]:
                block.pop()
            if not any(contains(text, block) for text in files.values()):
                bad.append(f"{guide}:{start}: this {lang} snippet is not in {sample}")
        i = j + 1
    if count == 0:
        bad.append(f"no snippets found in {guide}")
    elif len(bad) == before:
        print(f"All {count} snippets of {guide} are in {sample}.")

for b in bad:
    print(b, file=sys.stderr)
if bad:
    print("Copy the snippet from the sample, or change the sample and the snippet together.", file=sys.stderr)
    sys.exit(1)
PYEOF
