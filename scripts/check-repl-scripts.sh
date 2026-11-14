#!/usr/bin/env bash
# Runs the llm4s-repl examples (#2342) with Scala CLI against a library already published to the local Maven repository
# (`sbt publishM2`), the way docs/getting-started/repl.md tells a user to: a Scala CLI script (examples/repl/hello.sc)
# and a Metals worksheet (examples/repl/hello.worksheet.sc, run through `scala-cli run`). Both use Session.offline, so
# no API key and no network beyond dependency resolution is needed. Each run must exit 0 and print the expected lines.
#
# Usage: scripts/check-repl-scripts.sh <version>
#   <version>  the version the library was published at (CI: 0.1.0-SNAPSHOT)
# `scala-cli` must be on the PATH.
set -euo pipefail

VERSION="${1:?usage: check-repl-scripts.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail=0

# run_example <file> <pattern>...: the file runs with llm4s-repl at $VERSION, exits 0, and prints every pattern
# (fixed strings, one per output line).
run_example() {
  local file="$1" log="$WORK/$(basename "$1").log" ok=1
  shift
  # A copy outside the checkout, so Scala CLI sees no project of this repository.
  cp "$ROOT/examples/repl/$file" "$WORK/$file"
  if ! scala-cli run "$WORK/$file" --server=false --repository m2Local \
    --dep "org.llm4s::llm4s-repl:$VERSION" >"$log" 2>&1; then
    echo "FAIL  $file: scala-cli run failed"
    ok=0
  fi
  for pattern in "$@"; do
    if ! grep -Fxq -- "$pattern" "$log"; then
      echo "FAIL  $file: no output line: $pattern"
      ok=0
    fi
  done
  if grep -Eq '^\s+at [a-z]' "$log"; then
    echo "FAIL  $file: the output has a stack trace"
    ok=0
  fi
  if [ "$ok" = 1 ]; then
    echo "ok    $file"
  else
    tail -40 "$log"
    fail=1
  fi
}

run_example hello.sc \
  "Covariance lets a List[Cat] be used where a List[Animal] is expected." \
  "invoice: Invoice(ACME,12.5,EUR)" \
  "usage: scripted-model" \
  "Contravariance is the opposite." \
  "strict failure: true" \
  "user: And contravariance?" \
  "assistant: Contravariance is the opposite."

run_example hello.worksheet.sc \
  "A monad is a type with flatMap and pure that obey three laws." \
  "answer: A monad is a type with flatMap and pure that obey three laws." \
  "failed: true" \
  "messages: 2"

# The failure line names the error kind; its hint follows on the same line or the next ones.
if ! grep -q '^error: AuthenticationError' "$WORK/hello.worksheet.sc.log"; then
  echo "FAIL  hello.worksheet.sc: the failed call printed no 'error: AuthenticationError' line"
  fail=1
fi

exit "$fail"
