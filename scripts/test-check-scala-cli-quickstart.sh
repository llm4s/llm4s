#!/usr/bin/env bash
# Tests for scripts/check-scala-cli-quickstart.sh, with no network and no Scala CLI: the real repository passes,
# and each way the quick start can fall out of step fails with a message naming it. Every case copies the two
# files the check reads (hello.scala and docs/_data/project.yml) into a scratch directory, changes one thing, and
# runs the check there. Maven Central is a file:// metadata document; scala-cli is a fake that records its call.
#
# Usage: scripts/test-check-scala-cli-quickstart.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${SCALA_CLI_QUICKSTART_CHECK:-$REPO_ROOT/scripts/check-scala-cli-quickstart.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

VERSION="$(sed -nE 's/^latest_release:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p' "$REPO_ROOT/docs/_data/project.yml" | head -n 1)"
[ -n "$VERSION" ] || { echo "FAIL: no latest_release in docs/_data/project.yml"; exit 2; }

fresh() {
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo/modules/samples/scala-cli" "$WORK/repo/docs/_data"
  cp "$REPO_ROOT/modules/samples/scala-cli/hello.scala" "$WORK/repo/modules/samples/scala-cli/hello.scala"
  cp "$REPO_ROOT/docs/_data/project.yml" "$WORK/repo/docs/_data/project.yml"
}

central() {
  printf '<metadata><versioning><latest>%s</latest><release>%s</release></versioning></metadata>\n' "$1" "$1" > "$WORK/metadata.xml"
}

# A fake scala-cli: records its arguments and working directory, and fails when FAKE_SCALA_CLI_FAIL is set.
cat > "$WORK/scala-cli" <<'FAKE'
#!/usr/bin/env bash
echo "$PWD $*" > "$(dirname "$0")/scala-cli.calls"
[ -z "${FAKE_SCALA_CLI_FAIL:-}" ]
FAKE
chmod +x "$WORK/scala-cli"

run() {
  QUICKSTART_ROOT="$WORK/repo" LLM4S_CORE_METADATA_URL="file://$WORK/metadata.xml" SCALA_CLI="$WORK/scala-cli" \
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

set_script_version() { sed -i.bak -E "s#(org\.llm4s::llm4s-[a-z0-9-]+:)[^[:space:]]+#\1$1#" "$WORK/repo/modules/samples/scala-cli/hello.scala"; }

# The repository as it is, offline and against a Central that agrees with it.
fresh; central "$VERSION"
expect_pass "the repository passes"
expect_pass "the repository passes --central" --central

# A new release on Central that the script was not bumped to.
fresh; central "99.0.0"
expect_fail "a newer release on Central" "Maven Central's latest llm4s-core is 99.0.0 but hello.scala uses $VERSION" --central

# Between a release's prepare commit and its publish, the script is ahead of Central: it passes and is not compiled.
fresh; central "0.0.1"; rm -f "$WORK/scala-cli.calls"
expect_pass "a release not yet published" --central --compile
if [ -e "$WORK/scala-cli.calls" ]; then FAILED=$((FAILED + 1)); echo "FAIL: scala-cli ran for a version not yet on Central"; else PASSED=$((PASSED + 1)); fi
if grep -qF "not on Maven Central yet" "$WORK/out"; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: no not-yet-published notice:"; cat "$WORK/out"; fi

# Central unreachable, or answering without a release.
fresh; rm -f "$WORK/metadata.xml"
expect_fail "Central unreachable" "FAIL: could not read" --central
fresh; echo '<metadata/>' > "$WORK/metadata.xml"
expect_fail "metadata without a release" "no <release>" --central

# The script and the docs site disagree.
fresh; central "$VERSION"; set_script_version "0.0.1"
expect_fail "script behind latest_release" "hello.scala uses llm4s 0.0.1 but docs/_data/project.yml's latest_release is $VERSION"

# One module bumped, the others not.
fresh; python3 - "$WORK/repo/modules/samples/scala-cli/hello.scala" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
open(path, "w", encoding="utf-8").write(re.sub(r"org\.llm4s::llm4s-core:\S+", "org.llm4s::llm4s-core:0.0.2", text, count=1))
PY
expect_fail "mixed llm4s versions" "pins more than one llm4s version"

# No llm4s dependency left at all, and no latest_release.
fresh; sed -i.bak '/org\.llm4s::/d' "$WORK/repo/modules/samples/scala-cli/hello.scala"
expect_fail "no llm4s dependency" "has no \`//> using dep org.llm4s::llm4s-...:<version>\` line"
fresh; sed -i.bak '/^latest_release:/d' "$WORK/repo/docs/_data/project.yml"
expect_fail "no latest_release" "has no latest_release"

# --compile runs scala-cli in the script's directory, and reports its failure.
fresh; central "$VERSION"; rm -f "$WORK/scala-cli.calls"
expect_pass "compile runs scala-cli" --compile
if grep -qF "$WORK/repo/modules/samples/scala-cli --power compile ." "$WORK/scala-cli.calls" 2>/dev/null; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: scala-cli was not called as 'scala-cli --power compile .' in the script's directory"; cat "$WORK/scala-cli.calls" 2>/dev/null || true; fi
fresh
FAKE_SCALA_CLI_FAIL=1 expect_fail "a compile error" "scala-cli could not compile hello.scala" --compile

# An unknown flag is a usage error, not a silent pass.
fresh
expect_fail "unknown flag" "unknown argument: --nope" --nope

echo "check-scala-cli-quickstart tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
