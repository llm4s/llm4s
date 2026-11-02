#!/usr/bin/env bash
# Keeps the Mill quick start (modules/samples/mill, docs/getting-started/mill.md, #2293) buildable against the latest
# release.
#
# sbt never builds the sample: it is a standalone Mill project whose build.mill pins a published llm4s version. This
# checks it against what users actually download:
#   1. build.mill names one llm4s version: `val llm4sVersion = "..."`, and any `org.llm4s::llm4s-*:<version>` literal
#      agrees with it;
#   2. that version is `latest_release` in docs/_data/project.yml, the version the docs site shows;
#   3. with --central, it is also the latest llm4s-core release on Maven Central, so a release that forgot to bump
#      the sample is caught (a version newer than Central's is a release not yet published: it passes, unbuilt);
#   4. with --build, `./mill app.compile + app.test` builds the sample and runs its test (a scripted client: no key).
# Steps 3 and 4 need the network, so the PR build does not run them; .github/workflows/mill-quickstart.yml does.
#
# Usage: scripts/check-mill-quickstart.sh [--central] [--build]
# Environment (for scripts/test-check-mill-quickstart.sh): QUICKSTART_ROOT (default: the repository),
# LLM4S_CORE_METADATA_URL (default: Maven Central's maven-metadata.xml for llm4s-core_3), MILL (default: the sample's
# ./mill launcher).
# Exit code 0 = in step. Non-zero = a message naming what is out of step and what to change.
set -euo pipefail

ROOT="${QUICKSTART_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
METADATA_URL="${LLM4S_CORE_METADATA_URL:-https://repo1.maven.org/maven2/org/llm4s/llm4s-core_3/maven-metadata.xml}"
SAMPLE_DIR="$ROOT/modules/samples/mill"
BUILD="$SAMPLE_DIR/build.mill"
MILL="${MILL:-./mill}"
PROJECT_YML="$ROOT/docs/_data/project.yml"

CENTRAL=false
BUILD_IT=false
for arg in "$@"; do
  case "$arg" in
    --central) CENTRAL=true ;;
    --build) BUILD_IT=true ;;
    *) echo "unknown argument: $arg (expected --central or --build)" >&2; exit 2 ;;
  esac
done

[ -f "$BUILD" ] || { echo "FAIL: $BUILD not found"; exit 1; }
[ -f "$PROJECT_YML" ] || { echo "FAIL: $PROJECT_YML not found"; exit 1; }

declared="$(sed -nE 's/^[[:space:]]*val[[:space:]]+llm4sVersion[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$BUILD")"
literals="$(grep -oE 'org\.llm4s::llm4s-[a-z0-9-]+:[^"$[:space:]][^"[:space:]]*' "$BUILD" | sed -E 's/.*://' || true)"
if [ -z "$declared" ]; then
  echo "FAIL: build.mill has no \`val llm4sVersion = \"<version>\"\` line"
  exit 1
fi
versions="$(printf '%s\n%s\n' "$declared" "$literals" | sed '/^$/d' | sort -u)"
if [ "$(printf '%s\n' "$versions" | wc -l | tr -d ' ')" != "1" ]; then
  echo "FAIL: build.mill pins more than one llm4s version: $(printf '%s ' $versions)- use llm4sVersion for every llm4s module"
  exit 1
fi
sample_version="$versions"
if ! grep -qE 'org\.llm4s::llm4s-core:' "$BUILD"; then
  echo "FAIL: build.mill does not depend on org.llm4s::llm4s-core"
  exit 1
fi

latest_release="$(sed -nE 's/^latest_release:[[:space:]]*"?([^"[:space:]]+)"?.*/\1/p' "$PROJECT_YML" | head -n 1)"
if [ -z "$latest_release" ]; then
  echo "FAIL: docs/_data/project.yml has no latest_release"
  exit 1
fi
if [ "$sample_version" != "$latest_release" ]; then
  echo "FAIL: build.mill uses llm4s $sample_version but docs/_data/project.yml's latest_release is $latest_release - update llm4sVersion"
  exit 1
fi
echo "ok: build.mill and docs/_data/project.yml both use llm4s $sample_version"

if $CENTRAL; then
  metadata="$(curl -fsSL --retry 3 "$METADATA_URL")" || { echo "FAIL: could not read $METADATA_URL"; exit 1; }
  central="$(printf '%s' "$metadata" | sed -nE 's#.*<release>([^<]+)</release>.*#\1#p' | head -n 1)"
  if [ -z "$central" ]; then
    echo "FAIL: no <release> in $METADATA_URL"
    exit 1
  fi
  newest="$(printf '%s\n%s\n' "$central" "$sample_version" | sort -V | tail -n 1)"
  if [ "$central" = "$sample_version" ]; then
    echo "ok: $sample_version is the latest llm4s-core on Maven Central"
  elif [ "$newest" = "$sample_version" ]; then
    # A release's prepare commit bumps the sample before the tag is published: not an error, nothing to build yet.
    echo "ok: llm4s $sample_version is not on Maven Central yet (its latest is $central); the build waits until it is published"
    BUILD_IT=false
  else
    echo "FAIL: Maven Central's latest llm4s-core is $central but build.mill uses $sample_version - bump llm4sVersion (and latest_release) to $central"
    exit 1
  fi
fi

if $BUILD_IT; then
  ( cd "$SAMPLE_DIR" && "$MILL" --no-daemon app.compile + app.test ) || { echo "FAIL: mill could not build and test the sample against llm4s $sample_version"; exit 1; }
  echo "ok: the Mill sample builds and its test passes against the published llm4s $sample_version"
fi
