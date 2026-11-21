#!/usr/bin/env bash
# Tests for scripts/check-docs-tests.sh against a fixture build model, docs/ tree and module tree: tagged readers in
# the modules the alias runs pass; an untagged reader, a suite that reads docs/ through a helper, a tagged suite in a
# module the alias skips, and an alias module with no tagged suite each fail with a message naming it; a fixture path
# that merely starts with "docs/" and a comment naming a guide do not count as reading docs/.
#
# No network, no sbt. Usage: scripts/test-check-docs-tests.sh     (needs ruby, on GitHub's runners)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${DOCS_CHECK_SCRIPT:-$REPO_ROOT/scripts/check-docs-tests.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0
TAG='org.llm4s.tags.DocsSpec'

# fixture ALIAS_BODY : modules core (a tagged guide suite), samples (a tagged suite reading through a tagged helper's
# trait) and rag (fixture paths and a comment only), and docs/guide/a.md
fixture() {
  rm -rf "$WORK/repo"
  local r="$WORK/repo"
  mkdir -p "$r/docs/guide" "$r/modules/core/src/test/scala/a" "$r/modules/samples/src/test/scala/b" \
    "$r/modules/rag/src/test/scala/c"
  echo '# A' > "$r/docs/guide/a.md"
  cat > "$r/modules/core/src/test/scala/a/GuideSpec.scala" <<'SCALA'
@org.llm4s.tags.DocsSpec
class GuideSpec { val text = read("docs/guide/a.md") }
SCALA
  cat > "$r/modules/samples/src/test/scala/b/PageSupport.scala" <<'SCALA'
trait PageSupport { def page(p: String) = read(s"docs/guide/$p") }
SCALA
  cat > "$r/modules/samples/src/test/scala/b/PartSpec.scala" <<'SCALA'
import org.llm4s.tags.DocsSpec
@DocsSpec
final class PartSpec extends AnyFlatSpec with PageSupport
SCALA
  cat > "$r/modules/rag/src/test/scala/c/S3Spec.scala" <<'SCALA'
// docs/guide/a.md describes this; the test reads fixtures only
class S3Spec { val keys = Seq("docs/a.txt", "docs/b.txt") }
SCALA
  ruby -rjson -e '
    projects = [["root", "."], ["core", "modules/core"], ["samples", "modules/samples"], ["rag", "modules/rag"]]
    puts JSON.generate("projects" => projects.map { |id, base| { "id" => id, "base" => base } },
                       "aliases" => [{ "name" => "testDocs", "body" => ARGV[0] }])
  ' "$1" > "$WORK/model.json"
}
GOOD=";core/testOnly -- -n $TAG;samples/testOnly -- -n $TAG"

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
expect_pass "tagged readers in the modules the alias runs pass (fixture paths and comments do not count)"

fixture "$GOOD"
sed -i.bak '/@org.llm4s.tags.DocsSpec/d' "$WORK/repo/modules/core/src/test/scala/a/GuideSpec.scala"
expect_fail "an untagged suite that reads docs/ fails" "GuideSpec.scala reads docs/ but is not tagged"

fixture "$GOOD"
sed -i.bak '/^@DocsSpec/d' "$WORK/repo/modules/samples/src/test/scala/b/PartSpec.scala"
expect_fail "an untagged suite that reads docs/ through a helper fails" "PartSpec.scala reads docs/ but is not tagged"

fixture "$GOOD"
printf '@org.llm4s.tags.DocsSpec\nclass RagGuideSpec { val t = read("docs/guide/a.md") }\n' \
  > "$WORK/repo/modules/rag/src/test/scala/c/RagGuideSpec.scala"
expect_fail "a tagged suite in a module the alias skips fails" "RagGuideSpec.scala is tagged DocsSpec, but .* does not run rag"

fixture "$GOOD;rag/testOnly -- -n $TAG"
expect_fail "an alias module with no tagged suite fails" "runs rag, which has no suite tagged DocsSpec"

fixture "$GOOD;gone/testOnly -- -n $TAG"
expect_fail "an alias project the build does not have fails" "runs gone, which is not a project of this build"

fixture "testOnly -- -n $TAG"
expect_fail "an alias over the whole build fails" "runs no .<project>/testOnly"

fixture "$GOOD"
if "$CHECK" --model "$WORK/model.json" --root "$WORK/repo" --list > "$WORK/out" 2>&1 &&
  [ "$(awk '{print $2}' "$WORK/out" | xargs -n1 basename | sort | tr '\n' ' ')" = "GuideSpec.scala PartSpec.scala " ]; then
  PASSED=$((PASSED + 1)); echo "ok   --list names the two reading suites"
else
  FAILED=$((FAILED + 1)); echo "FAIL --list names the two reading suites"; sed 's/^/       /' "$WORK/out"
fi

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
