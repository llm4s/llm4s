#!/usr/bin/env bash
# Runs every probe in probes/ with Scala CLI, once per Scala 2.13 version, against llm4s's Scala 3 artifacts, and
# prints a Markdown table: whether each compiles and runs, with the compiler's first error verbatim when it does not.
# A probe is one `.scala` file, or a directory of sources (Scala and Java) with one `Probe` object. Needs scala-cli
# and network access to Maven Central; no API key (the probes that call a model use a local fake Ollama server,
# common/FakeOllama.scala, or llm4s-java-api's FakeLlmClient).
#
# Usage: ./run-probes.sh [--release | --local VERSION] [--check EXPECTED] [scala-2.13-version | latest ...]
#   --release          the llm4s artifacts of the latest release, docs/_data/project.yml's `latest_release` (default)
#   --local VERSION    the artifacts a `publishLocal` of this checkout wrote to ~/.ivy2/local at VERSION (CI, #2301):
#                      sbt 'set ThisBuild / version := "VERSION"' media/publishLocal core/publishLocal ...
#   --check EXPECTED   after the table, compare every probe's outcome on every version with the table in EXPECTED
#                      (expected.md) and exit 1 on any difference, in either direction, or on a probe without a row
#   latest             the latest Scala 2.13 release on Maven Central
#   Scala versions default to 2.13.16 and latest.
set -uo pipefail
cd "$(dirname "$0")"

MODULES=(core ollama agent java-api)
version=""
repos=()
expected=""
versions=()
while [ $# -gt 0 ]; do
  case "$1" in
    --release) version=""; repos=(); shift ;;
    --local) version="${2:?--local needs the published version}"; repos=(--repository ivy2Local); shift 2 ;;
    --check) expected="${2:?--check needs the expected table}"; shift 2 ;;
    -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d'; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) versions+=("$1"); shift ;;
  esac
done
[ ${#versions[@]} -eq 0 ] && versions=(2.13.16 latest)
if [ -z "$version" ]; then
  version="$(sed -n 's/^latest_release: *"\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p' ../../_data/project.yml)"
  [ -n "$version" ] || { echo "no latest_release in docs/_data/project.yml" >&2; exit 2; }
fi
for i in "${!versions[@]}"; do
  if [ "${versions[$i]}" = latest ]; then
    latest="$(curl -fsSL https://repo1.maven.org/maven2/org/scala-lang/scala-library/maven-metadata.xml \
      | grep -o '<version>2\.13\.[0-9]*</version>' | sed 's/<[^>]*>//g' | sort -t. -k3,3n | tail -n 1)"
    [ -n "$latest" ] || { echo "cannot read the latest Scala 2.13 from Maven Central" >&2; exit 2; }
    versions[$i]="$latest"
  fi
done
deps=()
for m in "${MODULES[@]}"; do deps+=(--dep "org.llm4s:llm4s-${m}_3:$version"); done

results="$(mktemp)"
trap 'rm -f "$results"' EXIT
echo "llm4s $version, Scala ${versions[*]}"
echo
echo "| Probe | Scala | Result | Output or first compiler error |"
echo "|---|---|---|---|"
for probe in probes/*; do
  name="$(basename "$probe" .scala)"
  for v in "${versions[@]}"; do
    out="$(scala-cli run common "$probe" --scala "$v" "${deps[@]}" ${repos[@]+"${repos[@]}"} --main-class Probe 2>&1)"
    if printf '%s' "$out" | grep -q '^PASS: '; then
      result="compiles and runs"
      detail="$(printf '%s' "$out" | grep '^PASS: ' | head -n 1 | sed 's/^PASS: //')"
    elif printf '%s' "$out" | grep -q '^FAIL: '; then
      result="runs, wrong result"
      detail="$(printf '%s' "$out" | grep '^FAIL: ' | head -n 1)"
    elif printf '%s' "$out" | grep -q 'Compiled project'; then
      result="compiles, crashes"
      detail="$(printf '%s' "$out" | grep -E 'Exception|Error' | head -n 1)"
    else
      result="does not compile"
      detail="$(printf '%s' "$out" | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'TypeError:|error\] [^.]' | grep -vE 'error\] (\./|/|  +|\s*\^)' | head -n 1 | sed -E 's/^.*(TypeError: |error\] )//')"
    fi
    printf '%s\t%s\t%s\t%s\n' "$name" "$v" "$result" "$(printf '%s' "$detail" | tr '\t\n' '  ')" >> "$results"
    printf '| %s | %s | %s | `%s` |\n' "$name" "$v" "$result" "$(printf '%s' "$detail" | sed 's/|/\\|/g; s/`/'"'"'/g')"
  done
done

[ -z "$expected" ] && exit 0
echo
# expected.md: a Markdown table of Probe | Expected outcome | Error contains | ...; a non-empty `Error contains` must
# be part of the probe's first compiler error, so a probe that stops compiling for another reason (a renamed API it
# uses) is not mistaken for the blocker it records.
python3 - "$expected" "$results" <<'PYEOF'
import re, sys
expected = {}
for line in open(sys.argv[1], encoding="utf-8"):
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if line.startswith("|") and len(cells) >= 3 and re.match(r"^[A-Z]\w*_\w+$", cells[0]):
        expected[cells[0]] = (cells[1], cells[2].strip("`"))
problems, seen = [], set()
for line in open(sys.argv[2], encoding="utf-8"):
    probe, version, actual, detail = (line.rstrip("\n").split("\t") + [""])[:4]
    seen.add(probe)
    if probe not in expected:
        problems.append(f"{probe}: no row in {sys.argv[1]} (it {actual} on Scala {version})")
        continue
    want, error = expected[probe]
    if want != actual:
        problems.append(f"{probe} on Scala {version}: expected `{want}`, but it {actual}: {detail}")
    elif error and error not in detail:
        problems.append(f"{probe} on Scala {version}: {actual} as expected, but its error is not `{error}`: {detail}")
problems += [f"{p}: a row in {sys.argv[1]}, but no probe" for p in sorted(set(expected) - seen)]
if problems:
    print("Scala 2.13 probe outcomes differ from " + sys.argv[1] + ":", file=sys.stderr)
    for p in problems:
        print("  " + p, file=sys.stderr)
    print("A change in either direction fails: update the table and docs/getting-started/scala-2-13.md with it.",
          file=sys.stderr)
    sys.exit(1)
print(f"All {len(seen)} probes match {sys.argv[1]}.")
PYEOF
