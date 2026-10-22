#!/usr/bin/env bash
# Runs every probe in probes/ with Scala CLI, once per Scala 2.13 version, against the published llm4s artifacts
# in common/project.scala, and prints a Markdown table: whether each compiles and runs, with the compiler's first
# error verbatim when it does not. Needs scala-cli and network access to Maven Central; no API key (the probes that
# call a model use a local fake Ollama server, common/FakeOllama.scala).
#
# Usage: ./run-probes.sh [scala-2.13-version ...]    (default: 2.13.16 2.13.18)
set -uo pipefail
cd "$(dirname "$0")"
versions=("$@")
[ ${#versions[@]} -eq 0 ] && versions=(2.13.16 2.13.18)

echo "| Probe | Scala | Result | Output or first compiler error |"
echo "|---|---|---|---|"
for probe in probes/*.scala; do
  name="$(basename "$probe" .scala)"
  for v in "${versions[@]}"; do
    out="$(scala-cli run common "$probe" --scala "$v" 2>&1)"
    if printf '%s' "$out" | grep -q '^PASS: '; then
      result="compiles and runs"
      detail="$(printf '%s' "$out" | grep '^PASS: ' | head -n 1 | sed 's/^PASS: //')"
    elif printf '%s' "$out" | grep -q '^FAIL: '; then
      result="runs, wrong result"
      detail="$(printf '%s' "$out" | grep '^FAIL: ' | head -n 1)"
    else
      result="does not compile"
      detail="$(printf '%s' "$out" | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'TypeError:|error\] [^.]' | grep -vE 'error\] (\./|/|  +|\s*\^)' | head -n 1 | sed -E 's/^.*(TypeError: |error\] )//')"
    fi
    printf '| %s | %s | %s | `%s` |\n' "$name" "$v" "$result" "$(printf '%s' "$detail" | sed 's/|/\\|/g; s/`/'"'"'/g')"
  done
done
