#!/usr/bin/env bash
# Tests scripts/check-scaladoc-warnings.sh against sbt `doc` logs written here, its allow-list of third-party
# warnings, and that CI's ScalaDoc job and the release's publish job both run it (#1967).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-scaladoc-warnings.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
ALLOW="$TMP/allow.txt"
cat > "$ALLOW" <<'EOF'
# a comment, and a blank line

org/scalatest/Assertions.scala | TestFailedException | inherited by the testkits' suites
cats/effect/IOApp.scala | IOApp.Simple!.run:cats\.effect\.IO[Unit]* | inherited by the samples' IOApp mains
EOF

expect() { # <name> <expected exit> <log file> [text the output must contain] [allow-list]
  local name="$1" want="$2" log="$3" text="${4:-}" allow="${5:-$ALLOW}" got=0 out
  out="$("$CHECK" "$log" "$allow" 2>&1)" || got=$?
  if [[ "$got" -ne "$want" ]]; then
    echo "FAIL: $name: exit $got, expected $want"; echo "$out"; failures=$((failures + 1)); return
  fi
  if [[ -n "$text" && "$out" != *"$text"* ]]; then
    echo "FAIL: $name: output lacks '$text'"; echo "$out"; failures=$((failures + 1)); return
  fi
  echo "ok: $name"
}

cat > "$TMP/clean.log" <<'EOF'
[info] Main Scala API documentation to /x/modules/core/target/scala-3.7.1/api...
[warn] Flag -classpath set repeatedly
[warn] one warning found
[info] Main Scala API documentation successful.
EOF
expect "a log with only the -classpath warning passes" 0 "$TMP/clean.log" "No unexpected Scaladoc warnings."

cat > "$TMP/third-party.log" <<'EOF'
[warn] -- Warning: dotty/core/src/main/scala/org/scalatest/Assertions.scala:472:13
[warn] 472 |  def assert(condition: Boolean)(implicit prettifier: Prettifier, pos: source.Position): Assertion = ???
[warn]     |             ^
[warn]     |Couldn't resolve a member for the given link query: TestFailedException
[warn] -- Warning: core/jvm/src/main/scala/cats/effect/IOApp.scala:596:14
[warn]     |Couldn't resolve a member for the given link query: IOApp.Simple!.run:cats\.effect\.IO[Unit]*
[warn] two warnings found
EOF
expect "allow-listed third-party warnings pass" 0 "$TMP/third-party.log" \
  "2 Scaladoc warning(s) in third-party sources, allow-listed"

# The key has no line number and starts at the package root, so a library rebuilt elsewhere still matches.
cat > "$TMP/moved.log" <<'EOF'
[warn] -- Warning: jvm/target/scala-3.3.6/src_managed/main/org/scalatest/Assertions.scala:999:1
[warn]     |Couldn't resolve a member for the given link query: TestFailedException
EOF
expect "an allow-listed warning on another line and source root passes" 0 "$TMP/moved.log" \
  "1 allow-list entr(y/ies) not seen in this log"

cat > "$TMP/new-third-party.log" <<'EOF'
[warn] -- Warning: dotty/core/src/main/scala/org/scalatest/Suite.scala:758:7
[warn]     |Couldn't resolve a member for the given link query: NullArgumentException
EOF
expect "a third-party warning not on the allow-list fails" 1 "$TMP/new-third-party.log" \
  "allow-list key: org/scalatest/Suite.scala | NullArgumentException"

printf 'org/scalatest/Suite.scala | NullArgumentException\n' > "$TMP/bad-allow.txt"
expect "an allow-list line without a reason is refused" 2 "$TMP/new-third-party.log" "expected" "$TMP/bad-allow.txt"

printf '%s\n' \
  $'\x1b[33m[warn]\x1b[0m -- Warning: modules/core/src/main/scala/org/llm4s/config/Llm4sConfig.scala:210:6 ' \
  '[warn] 210 |  def defaultProvider()(using ProviderRegistry): Result[ProviderConfig] =' \
  '[warn]     |      ^' \
  "[warn]     |    Couldn't resolve a member for the given link query: ProviderConfig" \
  '[warn] -- Warning: dotty/core/src/main/scala/org/scalatest/Assertions.scala:472:13' \
  "[warn]     |Couldn't resolve a member for the given link query: TestFailedException" \
  > "$TMP/ours.log"
expect "a broken link in an llm4s source fails" 1 "$TMP/ours.log" \
  "modules/core/src/main/scala/org/llm4s/config/Llm4sConfig.scala:210: unresolved link [[ProviderConfig]]"

# The same warning reported by a module's `doc` and again by `docs/doc` is listed once.
cat "$TMP/ours.log" "$TMP/ours.log" > "$TMP/twice.log"
expect "a warning seen by two doc runs is listed once" 1 "$TMP/twice.log" "1 Scaladoc warning(s) in llm4s sources"

printf '[warn] -- Warning: modules/rag/src/main/scala/org/llm4s/rag/RAG.scala:224:6\n[warn]     | some other Scaladoc warning\n' > "$TMP/other.log"
expect "any Scaladoc warning in an llm4s source fails" 1 "$TMP/other.log" "RAG.scala:224: see the log"

expect "a missing log is a usage error" 2 "$TMP/missing.log" "usage:"
expect "a missing allow-list is a usage error" 2 "$TMP/clean.log" "usage:" "$TMP/missing.txt"

# The repository's own allow-list parses: every line has a path, a link and a reason.
if out="$("$CHECK" "$TMP/clean.log" 2>&1)"; then
  echo "ok: the default allow-list parses"
else
  echo "FAIL: the default allow-list does not parse"; echo "$out"; failures=$((failures + 1))
fi

# ---- CI's ScalaDoc job and the release's publish job run the check ---------------------------------------------------
# In the publish job it must come before `sbt ci-release`, which signs and uploads: a check after it stops nothing.
CI_WORKFLOW="${CI_WORKFLOW:-$REPO_ROOT/.github/workflows/ci.yml}"
RELEASE_WORKFLOW="${RELEASE_WORKFLOW:-$REPO_ROOT/.github/workflows/release.yml}"
if command -v ruby >/dev/null 2>&1; then
  wiring="$(ruby -ryaml -e '
    def check_step(steps)
      steps.index do |s|
        r = s["run"].to_s
        r.include?("set -o pipefail") && r =~ /sbt doc\b.*\| tee scaladoc\.log/ &&
          r.include?("scripts/check-scaladoc-warnings.sh scaladoc.log")
      end
    end
    errs = []
    ci = (YAML.load_file(ARGV[0])["jobs"] || {})["scaladoc"]
    errs << "ci.yml: the scaladoc job must run check-scaladoc-warnings.sh on its doc log" unless ci && check_step(ci["steps"] || [])
    pub = (YAML.load_file(ARGV[1])["jobs"] || {})["publish"]
    if pub.nil?
      errs << "release.yml: no publish job"
    else
      steps = pub["steps"] || []
      check = check_step(steps)
      release = steps.index { |s| s["run"].to_s.include?("ci-release") }
      if check.nil?
        errs << "release.yml: the publish job must run check-scaladoc-warnings.sh on its doc log"
      elsif release.nil?
        errs << "release.yml: the publish job has no ci-release step"
      elsif check > release
        errs << "release.yml: the Scaladoc check must run before ci-release"
      end
    end
    puts errs' "$CI_WORKFLOW" "$RELEASE_WORKFLOW")"
  if [[ -z "$wiring" ]]; then
    echo "ok: ci.yml's scaladoc job and release.yml's publish job (before ci-release) run the check"
  else
    echo "FAIL: workflow wiring"; echo "$wiring"; failures=$((failures + 1))
  fi
else
  echo "FAIL: ruby is needed to read the workflows"; failures=$((failures + 1))
fi

if [[ "$failures" -ne 0 ]]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all check-scaladoc-warnings tests passed"
