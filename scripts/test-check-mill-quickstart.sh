#!/usr/bin/env bash
# Tests for scripts/check-mill-quickstart.sh, with no network and no Mill: the real repository passes, and each way
# the quick start can fall out of step fails with a message naming it. Every case copies the two files the check
# reads (build.mill and docs/_data/project.yml) into a scratch directory, changes one thing, and runs the check there.
# Maven Central is a file:// metadata document; mill is a fake that records its call.
#
# Usage: scripts/test-check-mill-quickstart.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${MILL_QUICKSTART_CHECK:-$REPO_ROOT/scripts/check-mill-quickstart.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

VERSION="$(sed -nE 's/^latest_release:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p' "$REPO_ROOT/docs/_data/project.yml" | head -n 1)"
[ -n "$VERSION" ] || { echo "FAIL: no latest_release in docs/_data/project.yml"; exit 2; }

SAMPLE="$WORK/repo/modules/samples/mill"

fresh() {
  rm -rf "$WORK/repo"
  mkdir -p "$SAMPLE" "$WORK/repo/docs/_data"
  cp "$REPO_ROOT/modules/samples/mill/build.mill" "$SAMPLE/build.mill"
  cp "$REPO_ROOT/docs/_data/project.yml" "$WORK/repo/docs/_data/project.yml"
}

central() {
  printf '<metadata><versioning><latest>%s</latest><release>%s</release></versioning></metadata>\n' "$1" "$1" > "$WORK/metadata.xml"
}

edit() { # edit <python regex> <replacement>: one substitution in the copied build.mill
  python3 - "$SAMPLE/build.mill" "$1" "$2" <<'PY'
import re, sys
path, pattern, replacement = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
new = re.sub(pattern, replacement, text, count=1, flags=re.M)
assert new != text, f"no match for {pattern}"
open(path, "w", encoding="utf-8").write(new)
PY
}

# A fake mill: records its arguments and working directory, and fails when FAKE_MILL_FAIL is set.
cat > "$WORK/mill" <<'FAKE'
#!/usr/bin/env bash
echo "$PWD $*" > "$(dirname "$0")/mill.calls"
[ -z "${FAKE_MILL_FAIL:-}" ]
FAKE
chmod +x "$WORK/mill"

run() {
  QUICKSTART_ROOT="$WORK/repo" LLM4S_CORE_METADATA_URL="file://$WORK/metadata.xml" MILL="$WORK/mill" \
    "$CHECK" "$@" > "$WORK/out" 2>&1
}

expect_pass() {
  local name="$1"; shift
  if run "$@"; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: $name: expected success"; cat "$WORK/out"; fi
}

expect_fail() {
  local name="$1" message="$2"; shift 2
  if run "$@"; then
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected a failure"; cat "$WORK/out"
  elif ! grep -qF -- "$message" "$WORK/out"; then
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected '$message' in:"; cat "$WORK/out"
  else
    PASSED=$((PASSED + 1))
  fi
}

# The repository as it is, offline and against a Central that agrees with it.
fresh; central "$VERSION"
expect_pass "the repository passes"
expect_pass "the repository passes --central" --central

# A new release on Central that the sample was not bumped to.
fresh; central "99.0.0"
expect_fail "a newer release on Central" "Maven Central's latest llm4s-core is 99.0.0 but build.mill uses $VERSION" --central

# Between a release's prepare commit and its publish, the sample is ahead of Central: it passes and is not built.
fresh; central "0.0.1"; rm -f "$WORK/mill.calls"
expect_pass "a release not yet published" --central --build
if [ -e "$WORK/mill.calls" ]; then FAILED=$((FAILED + 1)); echo "FAIL: mill ran for a version not yet on Central"; else PASSED=$((PASSED + 1)); fi
if grep -qF "not on Maven Central yet" "$WORK/out"; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: no not-yet-published notice:"; cat "$WORK/out"; fi

# Central unreachable, or answering without a release.
fresh; rm -f "$WORK/metadata.xml"
expect_fail "Central unreachable" "FAIL: could not read" --central
fresh; echo '<metadata/>' > "$WORK/metadata.xml"
expect_fail "metadata without a release" "no <release>" --central

# The sample and the docs site disagree.
fresh; central "$VERSION"; edit '^val llm4sVersion = "[^"]+"' 'val llm4sVersion = "0.0.1"'
expect_fail "sample behind latest_release" "build.mill uses llm4s 0.0.1 but docs/_data/project.yml's latest_release is $VERSION"

# One module pinned to a literal version other than llm4sVersion.
fresh; edit 'org\.llm4s::llm4s-openai:\$llm4sVersion' 'org.llm4s::llm4s-openai:0.0.2'
expect_fail "mixed llm4s versions" "pins more than one llm4s version"

# The same literal as llm4sVersion is in step.
fresh; edit 'org\.llm4s::llm4s-openai:\$llm4sVersion' "org.llm4s::llm4s-openai:$VERSION"
expect_pass "a literal equal to llm4sVersion"

# No llm4sVersion, no llm4s-core, and no latest_release.
fresh; edit '^val llm4sVersion = .*$' ''
expect_fail "no llm4sVersion" "has no \`val llm4sVersion = \"<version>\"\` line"
fresh; edit '^.*org\.llm4s::llm4s-core:.*$' ''
expect_fail "no llm4s-core" "does not depend on org.llm4s::llm4s-core"
fresh; sed -i.bak '/^latest_release:/d' "$WORK/repo/docs/_data/project.yml"
expect_fail "no latest_release" "has no latest_release"

# --build runs mill in the sample's directory, and reports its failure.
fresh; central "$VERSION"; rm -f "$WORK/mill.calls"
expect_pass "build runs mill" --build
if grep -qF "$SAMPLE --no-daemon app.compile + app.test" "$WORK/mill.calls" 2>/dev/null; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: mill was not called as 'mill --no-daemon app.compile + app.test' in the sample's directory"; cat "$WORK/mill.calls" 2>/dev/null || true; fi
fresh
FAKE_MILL_FAIL=1 expect_fail "a build failure" "mill could not build and test the sample" --build

# An unknown flag is a usage error, not a silent pass.
fresh
expect_fail "unknown flag" "unknown argument: --nope" --nope

echo "check-mill-quickstart tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
