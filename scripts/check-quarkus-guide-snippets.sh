#!/usr/bin/env bash
# Fails when a code snippet of docs/guide/quarkus.md is not in the Quarkus sample (#1468).
#
# The guide's Java, XML and properties snippets are excerpts of modules/samples/quarkus-java, which CI compiles and
# tests; this keeps them excerpts. A snippet passes when its lines, each with leading and trailing whitespace
# removed, appear as consecutive lines (compared the same way) of one file of the sample, so a snippet may be
# re-indented but not edited. Shell snippets (`bash`) are commands, not sample code, and are not checked.
# Exit code 0 = every snippet is in the sample. Non-zero = each snippet that is not, with its line in the guide.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import pathlib, re, sys

guide = pathlib.Path("docs/guide/quarkus.md")
sample = pathlib.Path("modules/samples/quarkus-java")
checked = ("java", "xml", "properties")

files = {}
for p in sorted(sample.rglob("*")):
    if p.is_file() and "target" not in p.parts and p.suffix in (".java", ".xml", ".properties"):
        files[p.as_posix()] = [line.strip() for line in p.read_text(encoding="utf-8").splitlines()]

def contains(haystack, needle):
    n = len(needle)
    return any(haystack[i:i + n] == needle for i in range(len(haystack) - n + 1))

bad, count = [], 0
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
    print(f"no snippets found in {guide}", file=sys.stderr)
    sys.exit(1)
for b in bad:
    print(b, file=sys.stderr)
if bad:
    print("Copy the snippet from the sample, or change the sample and the snippet together.", file=sys.stderr)
    sys.exit(1)
print(f"All {count} snippets of {guide} are in {sample}.")
PYEOF
