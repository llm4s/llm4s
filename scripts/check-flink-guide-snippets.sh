#!/usr/bin/env bash
# Fails when docs/guide/flink.md and the Flink sample (modules/samples/flink, #2298) disagree.
#
# The guide's Scala snippets are excerpts of the sample, which .github/workflows/flink-sample.yml tests on Flink's
# MiniCluster. This keeps them excerpts, and keeps the sample on the release the docs site shows:
#   1. every `org.llm4s::llm4s-*` dependency of the sample names one version, docs/_data/project.yml's latest_release;
#   2. every ```scala snippet of the guide, with `{{ site.data.project.latest_release }}` read as that version, is in
#      one file of the sample: its lines, each with leading and trailing whitespace removed, appear as consecutive
#      lines of the file (compared the same way), so a snippet may be re-indented but not edited.
# A snippet whose first line is `// build.sbt` (the sbt form of the sample's dependencies) is not checked, nor are
# shell and output blocks. No network and no JDK needed.
# Exit code 0 = in step. Non-zero = each disagreement, with its line in the guide.
set -euo pipefail

REPO_ROOT="${FLINK_GUIDE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import pathlib, re, sys

guide = pathlib.Path("docs/guide/flink.md")
sample = pathlib.Path("modules/samples/flink")
project_yml = pathlib.Path("docs/_data/project.yml")
liquid = "{{ site.data.project.latest_release }}"

m = re.search(r'^latest_release:\s*"?([^"\s]+)"?', project_yml.read_text(encoding="utf-8"), re.M)
if not m:
    sys.exit(f"FAIL: {project_yml} has no latest_release")
latest = m.group(1)

files = {}
for p in sorted(sample.rglob("*.scala")):
    if ".scala-build" not in p.parts:
        files[p.as_posix()] = [line.strip() for line in p.read_text(encoding="utf-8").splitlines()]

bad = []
versions = set()
for name, lines in files.items():
    for line in lines:
        dep = re.match(r"^//>\s*using\s+(?:test\.)?dep\s+org\.llm4s::llm4s-[a-z0-9-]+:(\S+)", line)
        if dep:
            versions.add(dep.group(1))
if not versions:
    bad.append(f"{sample} has no `//> using dep org.llm4s::llm4s-...:<version>` line")
elif versions != {latest}:
    bad.append(f"{sample} uses llm4s {', '.join(sorted(versions))} but {project_yml}'s latest_release is {latest}"
               " - update its //> using dep lines")

def contains(haystack, needle):
    n = len(needle)
    return any(haystack[i:i + n] == needle for i in range(len(haystack) - n + 1))

count = 0
lines = guide.read_text(encoding="utf-8").splitlines()
i = 0
while i < len(lines):
    fence = re.match(r"^```(\w*)\s*$", lines[i])
    if not fence:
        i += 1
        continue
    start, lang = i + 1, fence.group(1)
    j = start
    while j < len(lines) and not lines[j].startswith("```"):
        j += 1
    block = [line.replace(liquid, latest).strip() for line in lines[start:j]]
    while block and not block[-1]:
        block.pop()
    if lang == "scala" and block and not block[0].startswith("// build.sbt"):
        count += 1
        if not any(contains(text, block) for text in files.values()):
            bad.append(f"{guide}:{start}: this scala snippet is not in {sample}")
    i = j + 1

if count == 0:
    bad.append(f"no scala snippets found in {guide}")
for b in bad:
    print(f"FAIL: {b}", file=sys.stderr)
if bad:
    print("Copy the snippet from the sample, or change the sample and the snippet together.", file=sys.stderr)
    sys.exit(1)
print(f"ok: the {count} scala snippets of {guide} are in {sample}, which uses llm4s {latest}")
PYEOF
