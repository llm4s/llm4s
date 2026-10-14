#!/usr/bin/env bash
# Tests for scripts/check-platform-tests.sh: the repository passes, and each way platform-sensitive code can drop out
# of the Windows run fails with a message naming it. The cases run the check against a small fixture repository built
# in a scratch directory (a module `whole` that runs whole on Windows, a module `pure` with one named suite), changing
# one thing each.
#
# No network, no sbt. Usage: scripts/test-check-platform-tests.sh [build-model.json]   (default target/build-model.json)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-platform-tests.sh"
MODEL="${1:-$REPO_ROOT/target/build-model.json}"
[ -f "$MODEL" ] || { echo "No build model at $MODEL; write it with sbt \"dumpBuildModel $MODEL\"" >&2; exit 2; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

# The fixture: `whole` runs whole on Windows; `pure` runs only org.pure.FilesSpec there. pure's Sink.scala writes a
# file and is listed as covered by that suite; Parser.scala is pure logic.
fresh() {
  rm -rf "$WORK/case"
  local r="$WORK/case/repo"
  mkdir -p "$r/modules/whole/src/main/scala" "$r/modules/pure/src/main/scala" "$r/modules/pure/src/test/scala"
  printf 'object Shell { def run() = new ProcessBuilder("ls").start() }\n' > "$r/modules/whole/src/main/scala/Shell.scala"
  printf 'object Sink { def put(p: java.nio.file.Path) = java.nio.file.Files.writeString(p, "x") }\n' \
    > "$r/modules/pure/src/main/scala/Sink.scala"
  printf 'object Parser { def parse(s: String) = s.trim }\n' > "$r/modules/pure/src/main/scala/Parser.scala"
  printf 'package org.pure\nclass FilesSpec\n' > "$r/modules/pure/src/test/scala/FilesSpec.scala"
  cat > "$WORK/case/model.json" <<'JSON'
{"projects":[{"id":"whole","base":"modules/whole"},{"id":"pure","base":"modules/pure"}],
 "platformTests":{"modules":["whole"],"suites":{"pure":["org.pure.FilesSpec"]}}}
JSON
  printf '# comment\nmodules/pure/src/main/scala/Sink.scala  pure:org.pure.FilesSpec\n' > "$WORK/case/sources.txt"
  cat > "$WORK/case/ci.yml" <<'YAML'
jobs:
  test:
    steps:
      - name: Run tests
        run: sbt ${{ runner.os == 'Windows' && 'testPlatform' || 'test' }}
YAML
}

run_check() {
  "$CHECK" --model "$WORK/case/model.json" --sources "$WORK/case/sources.txt" --root "$WORK/case/repo" \
    --workflow "$WORK/case/ci.yml" > "$WORK/out" 2>&1
}

expect_pass() {
  if run_check; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to pass)"; sed 's/^/       /' "$WORK/out"
  fi
}

# expect_fail NAME PATTERN : the check must fail and its output must match PATTERN (grep -E).
expect_fail() {
  if run_check; then
    FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to fail)"; sed 's/^/       /' "$WORK/out"
  elif grep -qE "$2" "$WORK/out"; then
    PASSED=$((PASSED + 1)); echo "ok   $1"
  else
    FAILED=$((FAILED + 1)); echo "FAIL $1 (failed, but without /$2/)"; sed 's/^/       /' "$WORK/out"
  fi
}

# The repository itself, against the given build model.
if "$CHECK" --model "$MODEL" > "$WORK/out" 2>&1; then
  PASSED=$((PASSED + 1)); echo "ok   the repository passes"
else
  FAILED=$((FAILED + 1)); echo "FAIL the repository passes (expected to pass)"; sed 's/^/       /' "$WORK/out"
fi

fresh
expect_pass "the fixture passes"

fresh
printf 'object Tmp { def make() = java.nio.file.Files.createTempFile("a", "b") }\n' \
  > "$WORK/case/repo/modules/pure/src/main/scala/Tmp.scala"
expect_fail "a new platform-sensitive file outside the whole modules fails" \
  "modules/pure/src/main/scala/Tmp.scala calls a platform-sensitive API \(createTemp\)"

fresh
printf 'object More { def run() = sys.process.Process("ls").! }\n' > "$WORK/case/repo/modules/whole/src/main/scala/More.scala"
expect_pass "a new platform-sensitive file in a whole module needs no line"

fresh
printf 'modules/pure/src/main/scala/Parser.scala  none: pure, but listed anyway\n' >> "$WORK/case/sources.txt"
expect_pass "a listed file need not match the pattern"

fresh
printf 'modules/pure/src/main/scala/Gone.scala  pure:org.pure.FilesSpec\n' >> "$WORK/case/sources.txt"
expect_fail "a listed file that does not exist fails" "Gone.scala does not exist"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  pure:org.pure.OtherSpec\n' > "$WORK/case/sources.txt"
expect_fail "coverage by a suite PlatformTests does not list fails" "which PlatformTests.suites does not list for pure"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  whole:org.pure.FilesSpec\n' > "$WORK/case/sources.txt"
expect_fail "coverage by a module with no named suites fails" "PlatformTests.suites has no entry for whole"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  none\n' > "$WORK/case/sources.txt"
expect_fail "none without a reason fails" "needs a reason"

fresh
printf 'modules/pure/src/main/scala/Sink.scala\n' > "$WORK/case/sources.txt"
expect_fail "a line with no coverage fails" "names no coverage"

fresh
printf 'modules/pure/src/main/scala/Sink.scala  none: reason\nmodules/pure/src/main/scala/Sink.scala  none: again\n' \
  > "$WORK/case/sources.txt"
expect_fail "a file listed twice fails" "is listed twice"

fresh
printf 'modules/whole/src/main/scala/Shell.scala  none: redundant\n' >> "$WORK/case/sources.txt"
expect_fail "a line for a file in a whole module fails as redundant" "the line is redundant"

fresh
printf 'modules/pure/  none: the whole directory\n' > "$WORK/case/sources.txt"
expect_pass "a directory line covers the files under it"

fresh
printf 'package org.pure\nclass PosixSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/PosixSpec.scala"
expect_fail "a test that probes the OS but does not run on Windows fails" \
  "PosixSpec.scala checks which OS it runs on.*under pure"

fresh
printf 'package org.pure\nclass FilesSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/FilesSpec.scala"
expect_pass "a test that probes the OS and runs on Windows passes"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m["platformTests"]["suites"]["pure"] = ["org.pure.*"]
  File.write(f, JSON.generate(m))' "$WORK/case/model.json"
printf 'modules/pure/src/main/scala/Sink.scala  pure:org.pure.*\n' > "$WORK/case/sources.txt"
printf 'package org.pure\nclass PosixSpec { val win = System.getProperty("os.name") }\n' \
  > "$WORK/case/repo/modules/pure/src/test/scala/PosixSpec.scala"
expect_pass "a glob in PlatformTests.suites covers the suites it matches"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m["platformTests"]["modules"] << "nosuch"
  File.write(f, JSON.generate(m))' "$WORK/case/model.json"
expect_fail "a module that is not a project fails" "lists nosuch, which is not a project"

fresh
ruby -rjson -e 'f = ARGV[0]; m = JSON.parse(File.read(f)); m.delete("platformTests"); File.write(f, JSON.generate(m))' \
  "$WORK/case/model.json"
expect_fail "a build model without platformTests fails" "has no platformTests field"

fresh
printf 'jobs:\n  test:\n    steps:\n      - run: sbt test\n' > "$WORK/case/ci.yml"
expect_fail "a workflow whose Windows job does not run testPlatform fails" "runs .sbt testPlatform. on Windows"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
