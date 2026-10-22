#!/usr/bin/env bash
# Keeps the Scala CLI quick start (modules/samples/scala-cli/hello.scala, #1456) runnable against the latest release.
#
# sbt never compiles hello.scala: it is a standalone Scala CLI script whose `//> using dep` lines pin a published
# llm4s version. ScalaCliQuickstartSpec checks what the script calls against the code on the branch; this checks the
# script itself, against what users actually download:
#   1. every `org.llm4s::llm4s-*` dependency in the script names the same version;
#   2. that version is `latest_release` in docs/_data/project.yml, the version the docs site shows;
#   3. with --central, it is also the latest llm4s-core release on Maven Central, so a release that forgot to bump
#      the script is caught (a version newer than Central's is a release not yet published: it passes, uncompiled);
#   4. with --compile, `scala-cli compile` builds the script against those published artifacts.
# Steps 3 and 4 need the network, so the PR build does not run them; .github/workflows/scala-cli-quickstart.yml does.
#
# Usage: scripts/check-scala-cli-quickstart.sh [--central] [--compile]
# Environment (for scripts/test-check-scala-cli-quickstart.sh): QUICKSTART_ROOT (default: the repository),
# LLM4S_CORE_METADATA_URL (default: Maven Central's maven-metadata.xml for llm4s-core_3), SCALA_CLI (default: scala-cli).
# Exit code 0 = in step. Non-zero = a message naming what is out of step and what to change.
set -euo pipefail

ROOT="${QUICKSTART_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
METADATA_URL="${LLM4S_CORE_METADATA_URL:-https://repo1.maven.org/maven2/org/llm4s/llm4s-core_3/maven-metadata.xml}"
SCALA_CLI="${SCALA_CLI:-scala-cli}"
SCRIPT_DIR="$ROOT/modules/samples/scala-cli"
SCRIPT="$SCRIPT_DIR/hello.scala"
PROJECT_YML="$ROOT/docs/_data/project.yml"

CENTRAL=false
COMPILE=false
for arg in "$@"; do
  case "$arg" in
    --central) CENTRAL=true ;;
    --compile) COMPILE=true ;;
    *) echo "unknown argument: $arg (expected --central or --compile)" >&2; exit 2 ;;
  esac
done

[ -f "$SCRIPT" ] || { echo "FAIL: $SCRIPT not found"; exit 1; }
[ -f "$PROJECT_YML" ] || { echo "FAIL: $PROJECT_YML not found"; exit 1; }

versions="$(sed -nE 's#^//>[[:space:]]*using[[:space:]]+dep[[:space:]]+org\.llm4s::llm4s-[a-z0-9-]+:([^[:space:]]+).*#\1#p' "$SCRIPT" | sort -u)"
if [ -z "$versions" ]; then
  echo "FAIL: hello.scala has no \`//> using dep org.llm4s::llm4s-...:<version>\` line"
  exit 1
fi
if [ "$(printf '%s\n' "$versions" | wc -l | tr -d ' ')" != "1" ]; then
  echo "FAIL: hello.scala pins more than one llm4s version: $(printf '%s ' $versions)- use one version for every llm4s module"
  exit 1
fi
script_version="$versions"

latest_release="$(sed -nE 's/^latest_release:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p' "$PROJECT_YML" | head -n 1)"
if [ -z "$latest_release" ]; then
  echo "FAIL: docs/_data/project.yml has no latest_release"
  exit 1
fi
if [ "$script_version" != "$latest_release" ]; then
  echo "FAIL: hello.scala uses llm4s $script_version but docs/_data/project.yml's latest_release is $latest_release - update the //> using dep lines"
  exit 1
fi
echo "ok: hello.scala and docs/_data/project.yml both use llm4s $script_version"

if $CENTRAL; then
  metadata="$(curl -fsSL --retry 3 "$METADATA_URL")" || { echo "FAIL: could not read $METADATA_URL"; exit 1; }
  central="$(printf '%s' "$metadata" | sed -nE 's#.*<release>([^<]+)</release>.*#\1#p' | head -n 1)"
  if [ -z "$central" ]; then
    echo "FAIL: no <release> in $METADATA_URL"
    exit 1
  fi
  newest="$(printf '%s\n%s\n' "$central" "$script_version" | sort -V | tail -n 1)"
  if [ "$central" = "$script_version" ]; then
    echo "ok: $script_version is the latest llm4s-core on Maven Central"
  elif [ "$newest" = "$script_version" ]; then
    # A release's prepare commit bumps the script before the tag is published: not an error, nothing to compile yet.
    echo "ok: llm4s $script_version is not on Maven Central yet (its latest is $central); the compile waits until it is published"
    COMPILE=false
  else
    echo "FAIL: Maven Central's latest llm4s-core is $central but hello.scala uses $script_version - bump hello.scala (and latest_release) to $central"
    exit 1
  fi
fi

if $COMPILE; then
  ( cd "$SCRIPT_DIR" && "$SCALA_CLI" --power compile . ) || { echo "FAIL: scala-cli could not compile hello.scala against llm4s $script_version"; exit 1; }
  echo "ok: hello.scala compiles against the published llm4s $script_version"
fi
