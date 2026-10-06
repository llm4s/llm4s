#!/usr/bin/env bash
# Tests for scripts/check-doc-support.sh: the real repository passes, and each kind of stale claim fails
# with a message naming it. Each case copies the files the check reads into a scratch directory, breaks
# one thing, and runs the check against that copy.
#
# Nothing here hard-codes a version: the Scala and JDK versions come from project/Dependencies.scala and
# .github/workflows/ci.yml, and each mutation is computed to differ from them, so a version bump that
# keeps docs and build in step does not break this test. A mutation that finds nothing to change is a
# SETUP error (exit 2) that says which file moved, never a silent no-op.
#
# Exit code 0 = every case behaved. 1 = a case did not. 2 = the test needs updating for a moved file.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-doc-support.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- the build's own facts
SCALA="$(sed -n 's/.*val scala3 *= *"\([^"]*\)".*/\1/p' "$REPO_ROOT/project/Dependencies.scala" | head -1)"
[ -n "$SCALA" ] || { echo "SETUP: no 'val scala3' in project/Dependencies.scala"; exit 2; }
IFS=. read -r S_MAJOR S_MINOR S_PATCH <<<"$SCALA"
SCALA_STALE="$S_MAJOR.$S_MINOR.$((S_PATCH + 1))"      # a claim the build does not make
SCALA_NEXT="$S_MAJOR.$((S_MINOR + 1)).0"               # the build moving on
SCALA_SHORT_STALE="$S_MAJOR.$((S_MINOR + 1))"
JDK_LIST="$(grep -oE 'java: \[[^]]*\]' "$REPO_ROOT/.github/workflows/ci.yml" | head -1 | grep -oE '[0-9]+' | sort -n)"
[ -n "$JDK_LIST" ] || { echo "SETUP: no 'java: [N]' matrix in .github/workflows/ci.yml"; exit 2; }
JDK="$(tail -1 <<<"$JDK_LIST")"                         # the newest JDK CI runs
JDK_UNRUN=$((JDK + 4))                                  # a JDK CI does not run

fresh_copy() {
  local dir="$WORK/$1"
  mkdir -p "$dir/.github/workflows" "$dir/project"
  cp -R "$REPO_ROOT/docs" "$dir/docs"
  cp "$REPO_ROOT/CLAUDE.md" "$REPO_ROOT/README.md" "$REPO_ROOT/build.sbt" "$dir/"
  cp "$REPO_ROOT"/project/*.scala "$REPO_ROOT/project/plugins.sbt" "$dir/project/"
  cp "$REPO_ROOT/.github/workflows/ci.yml" "$dir/.github/workflows/"
  (cd "$REPO_ROOT" && find modules -maxdepth 3 -type d -not -path '*/src*' -not -path '*/target*' -not -path '*/node_modules*') |
    while read -r d; do mkdir -p "$dir/$d"; done
  echo "$dir"
}

# mutate FILE OLD NEW: replace every literal OLD in FILE; exit 2 if OLD is not there.
mutate() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
if old not in text:
    print(f"SETUP: '{old}' is not in {path}; update scripts/test-check-doc-support.sh for the moved text")
    sys.exit(2)
open(path, "w", encoding="utf-8").write(text.replace(old, new))
PY
}

# append_line FILE TEXT: add a paragraph to a doc.
append_line() { printf '\n%s\n' "$2" >> "$1"; }

# expect_fail NAME DIR EXPECTED_SUBSTRING: the check must exit non-zero and mention the substring.
expect_fail() {
  local name="$1" dir="$2" needle="$3" out status=0
  out="$("$CHECK" "$dir" 2>&1)" || status=$?
  if [ "$status" -eq 0 ]; then
    echo "FAIL [$name]: the check passed on a repository with a stale claim"; exit 1
  fi
  if ! grep -qF -- "$needle" <<<"$out"; then
    echo "FAIL [$name]: the check failed but did not mention '$needle'. Output:"; echo "$out"; exit 1
  fi
  echo "ok   [$name]"
}

# expect_pass NAME DIR: the check must accept the repository.
expect_pass() {
  local name="$1" dir="$2" out status=0
  out="$("$CHECK" "$dir" 2>&1)" || status=$?
  if [ "$status" -ne 0 ]; then
    echo "FAIL [$name]: the check rejected a true claim. Output:"; echo "$out"; exit 1
  fi
  echo "ok   [$name]"
}

echo "== the real repository (Scala $SCALA, JDK $JDK_LIST)"
"$CHECK" "$REPO_ROOT" >/dev/null || { echo "FAIL: the check fails on the repository itself:"; "$CHECK" "$REPO_ROOT"; exit 1; }
echo "ok   [repository passes]"

echo "== Scala version"
d="$(fresh_copy scala)"
mutate "$d/docs/getting-started/installation.md" "Scala $SCALA" "Scala $SCALA_STALE"
expect_fail "stale Scala version in a getting-started page" "$d" "Scala $SCALA_STALE"

d="$(fresh_copy scala-build)"
mutate "$d/project/Dependencies.scala" "val scala3 = \"$SCALA\"" "val scala3 = \"$SCALA_NEXT\""
expect_fail "build moved on, docs did not" "$d" "$SCALA_NEXT"

d="$(fresh_copy scala-bump)"
# A bump that keeps docs and build together must pass: this is what makes the test above survive a bump.
python3 - "$d" "$SCALA" "$SCALA_NEXT" <<'PY'
import pathlib, sys
root, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
for rel in ["project/Dependencies.scala", "CLAUDE.md", "README.md"] + [p.relative_to(root).as_posix() for p in (root / "docs").rglob("*.md")]:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    if old in text:
        p.write_text(text.replace(old, new), encoding="utf-8")
PY
expect_pass "docs and build bumped together" "$d"

d="$(fresh_copy scala-short)"
append_line "$d/docs/getting-started/installation.md" "LLM4S targets Scala $SCALA_SHORT_STALE."
expect_fail "a major.minor claim that is not the build's" "$d" "Scala $SCALA_SHORT_STALE"

d="$(fresh_copy scala-short-ok)"
append_line "$d/docs/getting-started/installation.md" "LLM4S targets Scala $S_MAJOR.$S_MINOR."
expect_pass "a major.minor claim that is the build's" "$d"

d="$(fresh_copy scala-history)"
append_line "$d/docs/getting-started/installation.md" "Releases before 0.5 were built with Scala 3.3.5, which is no longer supported."
expect_pass "an old Scala version named as history" "$d"

d="$(fresh_copy scala-ignore)"
append_line "$d/docs/getting-started/installation.md" "<!-- doc-support: ignore --> Scala 3.3.5 is the LTS line."
expect_pass "an opted-out line" "$d"

echo "== JDK version"
d="$(fresh_copy jdk)"
mutate "$d/docs/reference/v1-scope.md" "JDK $JDK is used in CI" "JDK $JDK_UNRUN is used in CI"
expect_fail "docs claim a JDK CI does not run" "$d" "JDK $JDK_UNRUN"

d="$(fresh_copy jdk-ci)"
python3 - "$d/.github/workflows/ci.yml" "$JDK_UNRUN" <<'PY'
import re, sys
path, jdk = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
text = re.sub(r"java: \[[^\]]*\]", f"java: [{jdk}]", text)
text = re.sub(r"java-version: *\d+", f"java-version: {jdk}", text)
open(path, "w", encoding="utf-8").write(text)
PY
expect_fail "CI moved to another JDK, docs did not" "$d" "JDK $JDK"

d="$(fresh_copy jdk-java-word)"
append_line "$d/docs/getting-started/installation.md" "You need Java $JDK_UNRUN to build."
expect_fail "'Java N' is read like 'JDK N'" "$d" "JDK $JDK_UNRUN"

d="$(fresh_copy jdk-openjdk-word)"
append_line "$d/docs/getting-started/installation.md" "We test on OpenJDK $JDK_UNRUN."
expect_fail "'OpenJDK N' is read like 'JDK N'" "$d" "JDK $JDK_UNRUN"

d="$(fresh_copy jdk-floor-ok)"
append_line "$d/docs/getting-started/installation.md" "Any JDK $((JDK - 4))+ can run the compiled jars."
expect_pass "a floor at or below the JDK CI runs" "$d"

d="$(fresh_copy jdk-floor-too-high)"
append_line "$d/docs/getting-started/installation.md" "Requires JDK $JDK_UNRUN or newer."
expect_fail "a floor above every JDK CI runs" "$d" "JDK $JDK_UNRUN"

d="$(fresh_copy jdk-history)"
append_line "$d/docs/getting-started/installation.md" "The build previously ran on JDK $((JDK - 4))."
expect_pass "an old JDK named as history" "$d"

echo "== modules"
d="$(fresh_copy module-missing)"
rmdir "$d/modules/core"
expect_fail "documented module has no directory" "$d" "modules/core"

d="$(fresh_copy module-invented)"
python3 - "$d/CLAUDE.md" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
m = re.search(r"^│   ├── core/.*$", text, re.M)
if not m:
    print("SETUP: no '│   ├── core/' line in CLAUDE.md's repository-structure block; update the test")
    sys.exit(2)
open(path, "w", encoding="utf-8").write(text.replace(m.group(0), m.group(0) + "\n│   ├── crossTest/             # Cross-version tests", 1))
PY
expect_fail "documented module that never existed" "$d" "modules/crossTest"

d="$(fresh_copy module-undocumented)"
printf '\nlazy val secretModule = (project in file("modules/secret-module"))\n' >> "$d/build.sbt"
mkdir -p "$d/modules/secret-module/src"
expect_fail "build module the docs never name" "$d" "modules/secret-module"

# A project with no sources (the aggregate `docs` project) gets a `target/` from sbt and is still no module.
d="$(fresh_copy module-build-output-only)"
printf '\nlazy val aggregateOnly = (project in file("modules/aggregate-only"))\n' >> "$d/build.sbt"
mkdir -p "$d/modules/aggregate-only/target"
expect_pass "project with only build output is not a module" "$d"

echo "== sbt commands quoted in the docs"
d="$(fresh_copy cmd-bare)"
printf '\n```bash\nsbt crossTestAll\n```\n' >> "$d/CLAUDE.md"
expect_fail "unknown sbt task" "$d" "crossTestAll"

d="$(fresh_copy cmd-hint)"
printf '\n```bash\nsbt crossTestAll\n```\n' >> "$d/CLAUDE.md"
expect_fail "an unknown sbt task says how to allow it" "$d" "SBT_BUILTINS"

d="$(fresh_copy cmd-project)"
printf '\nRun `sbt "nonexistentProject/test"` to check it.\n' >> "$d/README.md"
expect_fail "unknown sbt project" "$d" "nonexistentProject"

d="$(fresh_copy cmd-scoped-task)"
printf '\nRun `sbt "core/definitelyNotATask"` to check it.\n' >> "$d/README.md"
expect_fail "unknown task in a project the build defines" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-scoped-config-task)"
printf '\n```bash\nsbt core/Test/definitelyNotATask\n```\n' >> "$d/CLAUDE.md"
expect_fail "unknown task after a project and configuration" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-scoped-ok)"
printf '\n```bash\nsbt core/Test/compile docs/doc\n```\n' >> "$d/CLAUDE.md"
expect_pass "a known task in a project the build defines" "$d"

d="$(fresh_copy cmd-continued)"
printf '\n```bash\nsbt -Dllm4s.x=y \\\n    -Dllm4s.z=w \\\n    "definitelyNotATask"\n```\n' >> "$d/CLAUDE.md"
expect_fail "an unknown task on a continuation line" "$d" "definitelyNotATask"

d="$(fresh_copy cmd-continued-ok)"
printf '\n```bash\nsbt -Dllm4s.x=y \\\n    "run"\n```\n' >> "$d/CLAUDE.md"
expect_pass "a known task on a continuation line" "$d"

d="$(fresh_copy cmd-malformed-quotes)"
printf '\n```bash\nsbt "core/test\n```\n' >> "$d/CLAUDE.md"
expect_fail "a command the shell cannot parse" "$d" "cannot be parsed"

d="$(fresh_copy cmd-malformed-quotes-inline)"
printf '\nRun `sbt "testOnly org.llm4s.Foo` to check it.\n' >> "$d/README.md"
expect_fail "an inline command the shell cannot parse" "$d" "cannot be parsed"

d="$(fresh_copy cmd-ignore)"
printf '\n```bash\nsbt crossTestAll   # doc-support: ignore\n```\n' >> "$d/CLAUDE.md"
expect_pass "an opted-out sbt line" "$d"

echo "== sbt tasks in the project that sets them"
d="$(fresh_copy cmd-root-task-in-module)"
printf '\n```bash\nsbt core/publishedArtifactsCheck\n```\n' >> "$d/CLAUDE.md"
expect_fail "a root-only task scoped to another project" "$d" "publishedArtifactsCheck\` is set only in project llm4s"

d="$(fresh_copy cmd-module-task-elsewhere)"
printf '\nRun `sbt "core/itTierCheck"` to check it.\n' >> "$d/README.md"
expect_fail "a task one module sets, scoped to another" "$d" "itTierCheck\` is set only in project it"

d="$(fresh_copy cmd-task-where-set)"
printf '\n```bash\nsbt publishedArtifactsCheck it/itTierCheck llm4s/stabilityTierCheck core/test\n```\n' >> "$d/CLAUDE.md"
expect_pass "tasks run where the build sets them, built-ins anywhere" "$d"

echo "== sbt commands that come from plugins"
d="$(fresh_copy plugin-removed)"
mutate "$d/project/plugins.sbt" 'addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates"' '// addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates"'
printf '\n```bash\nsbt dependencyUpdates\n```\n' >> "$d/CLAUDE.md"
expect_fail "a plugin command whose plugin was removed" "$d" "comes from sbt-dependency-updates, which project/plugins.sbt does not load"

d="$(fresh_copy plugin-config-removed)"
mutate "$d/project/plugins.sbt" 'addSbtPlugin("com.github.sbt" % "sbt-native-packager"' '// addSbtPlugin("com.github.sbt" % "sbt-native-packager"'
printf '\n```bash\nsbt workspaceRunner/docker:publishLocal\n```\n' >> "$d/CLAUDE.md"
expect_fail "a plugin configuration whose plugin was removed" "$d" "comes from sbt-native-packager"

d="$(fresh_copy plugin-never-loaded)"
printf '\n```bash\nsbt assembly\n```\n' >> "$d/CLAUDE.md"
expect_fail "a plugin command the build never loaded" "$d" "comes from sbt-assembly"

d="$(fresh_copy plugin-loaded)"
printf '\n```bash\nsbt dependencyUpdates scalafmtCheckAll workspaceRunner/docker:publishLocal "benchmarks/Jmh/run"\n```\n' >> "$d/CLAUDE.md"
expect_pass "commands of loaded plugins" "$d"

d="$(fresh_copy cmd-alias-removed)"
# An alias the docs quote: the first build.sbt alias that CLAUDE.md or README.md tells the reader to run.
alias_name="$(python3 - "$d" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
build = (root / "build.sbt").read_text(encoding="utf-8")
docs = (root / "CLAUDE.md").read_text(encoding="utf-8") + (root / "README.md").read_text(encoding="utf-8")
for name in re.findall(r'addCommandAlias\(\s*"([^"]+)"', build):
    if re.search(r"\bsbt\s+" + re.escape(name) + r"\b", docs):
        print(name)
        break
PY
)"
[ -n "$alias_name" ] || { echo "SETUP: no build.sbt alias is quoted as 'sbt <alias>' in CLAUDE.md or README.md; update the test"; exit 2; }
sed -i.bak "/addCommandAlias(\"$alias_name\"/d" "$d/build.sbt"
expect_fail "documented alias removed from the build" "$d" "$alias_name"

echo "all cases behaved"
