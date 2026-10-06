#!/usr/bin/env bash
# Tests for scripts/check-doc-support.sh: the real repository passes, and each kind of stale claim fails
# with a message naming it. Each case copies the files the check reads into a scratch directory, breaks
# one thing, and runs the check against that copy.
#
# Exit code 0 = every case behaved. Non-zero = the first case that did not.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-doc-support.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fresh_copy() {
  local dir="$WORK/$1"
  mkdir -p "$dir/.github/workflows" "$dir/project"
  cp -R "$REPO_ROOT/docs" "$dir/docs"
  cp "$REPO_ROOT/CLAUDE.md" "$REPO_ROOT/README.md" "$REPO_ROOT/build.sbt" "$dir/"
  cp "$REPO_ROOT"/project/*.scala "$dir/project/"
  cp "$REPO_ROOT/.github/workflows/ci.yml" "$dir/.github/workflows/"
  (cd "$REPO_ROOT" && find modules -maxdepth 3 -type d -not -path '*/src*' -not -path '*/target*' -not -path '*/node_modules*') |
    while read -r d; do mkdir -p "$dir/$d"; done
  echo "$dir"
}

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

echo "== the real repository"
"$CHECK" "$REPO_ROOT" >/dev/null || { echo "FAIL: the check fails on the repository itself:"; "$CHECK" "$REPO_ROOT"; exit 1; }
echo "ok   [repository passes]"

echo "== Scala version"
d="$(fresh_copy scala)"
sed -i.bak 's/Scala 3\.7\.1/Scala 3.3.5/' "$d/docs/getting-started/installation.md"
expect_fail "stale Scala version in a getting-started page" "$d" "Scala 3.3.5"

d="$(fresh_copy scala-build)"
sed -i.bak 's/val scala3 = "3\.7\.1"/val scala3 = "3.8.0"/' "$d/project/Dependencies.scala"
expect_fail "build moved on, docs did not" "$d" "3.8.0"

echo "== JDK version"
d="$(fresh_copy jdk)"
sed -i.bak 's/JDK 21 is used in CI/JDK 17 is used in CI/' "$d/docs/reference/v1-scope.md"
expect_fail "docs claim a JDK CI does not run" "$d" "JDK 17"

d="$(fresh_copy jdk-ci)"
sed -i.bak 's/java: \[21\]/java: [25]/' "$d/.github/workflows/ci.yml"
sed -i.bak 's/java-version: 21/java-version: 25/' "$d/.github/workflows/ci.yml"
expect_fail "CI moved to another JDK, docs did not" "$d" "JDK 21"

echo "== modules"
d="$(fresh_copy module-missing)"
rmdir "$d/modules/ollama"
expect_fail "documented module has no directory" "$d" "modules/ollama"

d="$(fresh_copy module-invented)"
sed -i.bak 's#│   ├── core/                  \# Core library (published)#│   ├── core/                  \# Core library (published)\n│   ├── crossTest/             \# Cross-version tests#' "$d/CLAUDE.md"
expect_fail "documented module that never existed" "$d" "modules/crossTest"

d="$(fresh_copy module-undocumented)"
python3 - "$d/build.sbt" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s += '\nlazy val secretModule = (project in file("modules/secret-module"))\n'
open(p, "w").write(s)
PY
mkdir -p "$d/modules/secret-module/src"
expect_fail "build module the docs never name" "$d" "modules/secret-module"

# A project with no sources (the aggregate `docs` project) gets a `target/` from sbt and is still no module.
d="$(fresh_copy module-build-output-only)"
printf '\nlazy val aggregateOnly = (project in file("modules/aggregate-only"))\n' >> "$d/build.sbt"
mkdir -p "$d/modules/aggregate-only/target"
"$CHECK" "$d" >/dev/null || { echo "FAIL [build-output-only dir]: flagged a project with no sources"; exit 1; }
echo "ok   [project with only build output is not a module]"

echo "== sbt commands quoted in the docs"
d="$(fresh_copy cmd-bare)"
printf '\n```bash\nsbt crossTestAll\n```\n' >> "$d/CLAUDE.md"
expect_fail "unknown sbt task" "$d" "crossTestAll"

d="$(fresh_copy cmd-project)"
printf '\nRun `sbt "nonexistentProject/test"` to check it.\n' >> "$d/README.md"
expect_fail "unknown sbt project" "$d" "nonexistentProject"

d="$(fresh_copy cmd-alias-removed)"
sed -i.bak '/addCommandAlias("buildAll"/d' "$d/build.sbt"
expect_fail "documented alias removed from the build" "$d" "buildAll"

echo "all cases behaved"
