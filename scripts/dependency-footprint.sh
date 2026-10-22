#!/usr/bin/env bash
# Measures what a published llm4s artifact puts on a user's runtime classpath: the number of jars and their total
# size, resolved by coursier from Maven Central, for llm4s-java-api and for llm4s-core with one provider at a time
# (#1496). Prints a Markdown table, the one in docs/reference/dependency-conflicts.md#dependency-footprint.
#
# Usage: scripts/dependency-footprint.sh [VERSION]
#   VERSION  the published llm4s version to measure; defaults to latest_release in docs/_data/project.yml.
#
# Needs coursier's `cs` on the PATH (or CS=/path/to/cs). `cs fetch` resolves the runtime dependencies of the given
# coordinates together, as Maven and Gradle would for a project that declares them, and downloads the jars into
# coursier's cache; nothing is published or written anywhere else. Works with bash 3.2 (macOS).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CS="${CS:-cs}"

if [ $# -gt 1 ]; then
  echo "usage: $0 [VERSION]" >&2
  exit 2
fi

if [ $# -eq 1 ]; then
  VERSION="$1"
else
  VERSION="$(sed -n 's/^latest_release: *"\{0,1\}\([^"]*\)"\{0,1\} *$/\1/p' "$REPO_ROOT/docs/_data/project.yml")"
fi
if [ -z "$VERSION" ]; then
  echo "error: no version given and none found in docs/_data/project.yml" >&2
  exit 2
fi

# label|space-separated llm4s artifact names (without the _3 suffix)
SETS='llm4s-java-api|llm4s-java-api
llm4s-core|llm4s-core
llm4s-core + llm4s-ollama|llm4s-core llm4s-ollama
llm4s-core + llm4s-openai-compatible|llm4s-core llm4s-openai-compatible
llm4s-core + llm4s-gemini|llm4s-core llm4s-gemini
llm4s-core + llm4s-anthropic|llm4s-core llm4s-anthropic
llm4s-core + llm4s-openai|llm4s-core llm4s-openai
llm4s-core + llm4s-agent + llm4s-ollama|llm4s-core llm4s-agent llm4s-ollama'

# what a jar's file name says it is, for the "Brings" column
flags() {
  local jars="$1" out=""
  if printf '%s\n' "$jars" | grep -q '/okhttp-[0-9]'; then out="$out OkHttp,"; fi
  if printf '%s\n' "$jars" | grep -q '/kotlin-stdlib-[0-9]'; then out="$out kotlin-stdlib,"; fi
  if printf '%s\n' "$jars" | grep -q '/jackson-databind-[0-9]'; then out="$out Jackson,"; fi
  if printf '%s\n' "$jars" | grep -q '/anthropic-java-core-[0-9]'; then out="$out Anthropic SDK,"; fi
  if printf '%s\n' "$jars" | grep -q '/openai-java-core-[0-9]'; then out="$out OpenAI SDK,"; fi
  out="${out%,}"
  printf '%s' "${out# }"
}

echo "| Dependencies (llm4s $VERSION) | Jars | Size (MB) | Brings |"
echo "|---|---:|---:|---|"
printf '%s\n' "$SETS" | while IFS='|' read -r label artifacts; do
  coords=""
  for a in $artifacts; do coords="$coords org.llm4s:${a}_3:$VERSION"; done
  # shellcheck disable=SC2086 # one argument per coordinate
  if ! jars="$("$CS" fetch $coords 2>/dev/null)"; then
    echo "error: cs fetch failed for:$coords" >&2
    exit 1
  fi
  count=0
  bytes=0
  while IFS= read -r jar; do
    [ -n "$jar" ] || continue
    count=$((count + 1))
    bytes=$((bytes + $(wc -c <"$jar")))
  done <<EOF
$jars
EOF
  mb="$(awk -v b="$bytes" 'BEGIN { printf "%.1f", b / 1048576 }')"
  brings="$(flags "$jars")"
  echo "| $label | $count | $mb | ${brings:--} |"
done
