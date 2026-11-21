#!/usr/bin/env bash
# Tests for scripts/check-performance-tests.sh against a fixture build model and module tree: the modules the alias
# runs pass, and a tagged test elsewhere, an alias naming a missing project or a project with no tagged test, and an
# alias that runs nothing each fail with a message naming it.
#
# No network, no sbt. Usage: scripts/test-check-performance-tests.sh     (needs ruby, on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${PERFORMANCE_CHECK_SCRIPT:-$REPO_ROOT/scripts/check-performance-tests.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

# fixture ALIAS_BODY : a two-module build (core, agent, plus rag with no tagged test) whose testPerformance is ALIAS_BODY
fixture() {
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo/modules/core/src/test/scala/a" "$WORK/repo/modules/agent/src/test/scala/b" \
    "$WORK/repo/modules/rag/src/test/scala/c"
  cat > "$WORK/repo/modules/core/src/test/scala/a/ScaleSpec.scala" <<'SCALA'
class ScaleSpec { it("scales") taggedAs (SlowTest, PerformanceTest) in {} ; it("grows") taggedAs(PerformanceTest) in {} }
SCALA
  cat > "$WORK/repo/modules/agent/src/test/scala/b/GrowthSpec.scala" <<'SCALA'
class GrowthSpec { it("grows") taggedAs(PerformanceTest) in {} }
SCALA
  cat > "$WORK/repo/modules/rag/src/test/scala/c/PlainSpec.scala" <<'SCALA'
// a comment naming PerformanceTest is not a use of the tag, nor is an import
import org.llm4s.testutil.{ LinearTime, PerformanceTest }
class PlainSpec { it("works") in {} }
SCALA
  ruby -rjson -e '
    body = ARGV[0]
    projects = [["root", "."], ["core", "modules/core"], ["agent", "modules/agent"], ["rag", "modules/rag"]]
    puts JSON.generate("projects" => projects.map { |id, base| { "id" => id, "base" => base } },
                       "aliases" => [{ "name" => "testPerformance", "body" => body }])
  ' "$1" > "$WORK/model.json"
}

TAG='org.llm4s.tags.PerformanceTest'
GOOD=";core/testOnly -- -n $TAG;agent/testOnly -- -n $TAG"

run_check() { "$CHECK" --model "$WORK/model.json" --root "$WORK/repo" > "$WORK/out" 2>&1; }
expect_pass() {
  if run_check; then PASSED=$((PASSED + 1)); echo "ok   $1"
  else FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to pass)"; sed 's/^/       /' "$WORK/out"; fi
}
expect_fail() {
  if run_check; then FAILED=$((FAILED + 1)); echo "FAIL $1 (expected to fail)"; sed 's/^/       /' "$WORK/out"
  elif grep -qE "$2" "$WORK/out"; then PASSED=$((PASSED + 1)); echo "ok   $1"
  else FAILED=$((FAILED + 1)); echo "FAIL $1 (failed, but without /$2/)"; sed 's/^/       /' "$WORK/out"; fi
}

fixture "$GOOD"
expect_pass "tagged tests only in the modules the alias runs pass"

fixture "$GOOD"
cat > "$WORK/repo/modules/rag/src/test/scala/c/RagScaleSpec.scala" <<'SCALA'
class RagScaleSpec { test("scales", PerformanceTest) {} }
SCALA
expect_fail "a tagged test in a module the alias does not run fails" "RagScaleSpec.scala is tagged PerformanceTest, but .* does not run rag"

fixture "$GOOD"
cat > "$WORK/repo/modules/rag/src/test/scala/c/RagTagSpec.scala" <<SCALA
object T extends Tag("$TAG")
SCALA
expect_fail "the tag named by its string elsewhere fails" "RagTagSpec.scala is tagged PerformanceTest"

fixture "$GOOD;rag/testOnly -- -n $TAG"
expect_fail "a module the alias runs with no tagged test fails" "runs rag, which has no PerformanceTest"

fixture "$GOOD;gone/testOnly -- -n $TAG"
expect_fail "a project the build does not have fails" "runs gone, which is not a project of this build"

fixture "testOnly -- -n $TAG"
expect_fail "an alias over the whole build fails" "runs no .<project>/testOnly"

fixture ";core/testOnly -- -n $TAG"
expect_fail "dropping a module that has tagged tests fails" "GrowthSpec.scala is tagged PerformanceTest, but .* does not run agent"

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
