#!/usr/bin/env bash
# Tests scripts/check-scaladoc-warnings.sh against sbt `doc` logs written here.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-scaladoc-warnings.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
expect() { # <name> <expected exit> <log file> [text the output must contain]
  local name="$1" want="$2" log="$3" text="${4:-}" got=0 out
  out="$("$CHECK" "$log" 2>&1)" || got=$?
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
expect "a log with only the -classpath warning passes" 0 "$TMP/clean.log" "No Scaladoc warnings in llm4s sources."

cat > "$TMP/third-party.log" <<'EOF'
[warn] -- Warning: dotty/core/src/main/scala/org/scalatest/Assertions.scala:472:13
[warn] 472 |  def assert(condition: Boolean)(implicit prettifier: Prettifier, pos: source.Position): Assertion = ???
[warn]     |             ^
[warn]     |Couldn't resolve a member for the given link query: TestFailedException
[warn] -- Warning: core/jvm/src/main/scala/cats/effect/IOApp.scala:596:14
[warn]     |Couldn't resolve a member for the given link query: IOApp.Simple!.run:cats\.effect\.IO[Unit]*
[warn] two warnings found
EOF
expect "warnings in third-party sources pass" 0 "$TMP/third-party.log" "2 Scaladoc warning(s) in third-party sources"

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

if [[ "$failures" -ne 0 ]]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all check-scaladoc-warnings tests passed"
