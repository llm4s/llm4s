#!/usr/bin/env bash
# Verifies that a release is on Maven Central: every artifact the build publishes resolves at <version>.
#
#   scripts/verify-release.sh 0.5.0
#   scripts/verify-release.sh v0.5.0                       # a leading v is accepted
#   scripts/verify-release.sh 0.5.0 --list expected.txt    # use a saved list instead of asking sbt
#
# The expected coordinates come from the build (`sbt -error listPublishedArtifacts`), not from a list kept
# here, so a module added to the build is checked the day it is added. For each real artifact the POM and
# the jar must exist; for the bill of materials (`llm4s-bom`, POM-only) the POM must exist and must carry a
# <dependencyManagement>; for each relocation stub (the pre-0.4.0 coordinates, `core`, `workspaceclient`, ...)
# the POM must exist and must carry a <relocation>. v0.4.0 shipped without its stubs and nobody noticed
# until a user could not resolve `org.llm4s:core:0.4.0` (#1150); this is the check that would have said so.
#
# Maven Central's index can lag a successful publish by a while. A 404 straight after the release job
# finishes is not proof of a miss: re-run before concluding one.
#
# Exit code 0 = everything resolves. 1 = something is missing. 2 = usage or a failure to get the list.
set -uo pipefail

BASE="${MAVEN_CENTRAL_URL:-https://repo1.maven.org/maven2}/org/llm4s"

usage() { echo "usage: $0 <version> [--list <file>]" >&2; exit 2; }

[ $# -ge 1 ] || usage
VERSION="${1#v}"; shift
LIST_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --list) [ $# -ge 2 ] || usage; LIST_FILE="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.+-]*)?$ ]] || { echo "not a version: '$VERSION'" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "$LIST_FILE" ]; then
  LIST="$(cat "$LIST_FILE")" || exit 2
else
  echo "Asking sbt which artifacts the build publishes..." >&2
  LIST="$(cd "$REPO_ROOT" && sbt -error listPublishedArtifacts 2>/dev/null | grep -E '^(artifact|bom|stub) ')" || true
fi
[ -n "$LIST" ] || { echo "No artifacts listed: sbt printed nothing. Is 'listPublishedArtifacts' defined?" >&2; exit 2; }

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$1" || echo 000; }

missing=0; checked=0
while read -r kind id; do
  [ -n "${id:-}" ] || continue
  checked=$((checked + 1))
  pom="$BASE/$id/$VERSION/$id-$VERSION.pom"
  problems=""
  code="$(status "$pom")"
  if [ "$code" != 200 ]; then
    problems="pom HTTP $code"
  elif [ "$kind" = stub ]; then
    curl -s --max-time 30 "$pom" | grep -q '<relocation>' || problems="pom has no <relocation>"
  elif [ "$kind" = bom ]; then
    # POM-only: there is no jar, but the POM must carry the managed versions that make it a BOM.
    curl -s --max-time 30 "$pom" | grep -q '<dependencyManagement>' || problems="pom has no <dependencyManagement>"
  fi
  if [ "$kind" = artifact ] && [ -z "$problems" ]; then
    jcode="$(status "$BASE/$id/$VERSION/$id-$VERSION.jar")"
    [ "$jcode" = 200 ] || problems="jar HTTP $jcode"
  fi
  if [ -n "$problems" ]; then
    printf '  MISSING  %-8s %-40s %s\n' "$kind" "$id" "$problems"
    missing=$((missing + 1))
  else
    printf '  ok       %-8s %s\n' "$kind" "$id"
  fi
done <<< "$LIST"

echo
if [ "$missing" -gt 0 ]; then
  echo "$missing of $checked coordinates do not resolve at $VERSION on Maven Central."
  echo "If the release job finished minutes ago, the index may still be catching up: re-run before concluding."
  exit 1
fi
echo "All $checked coordinates resolve at $VERSION."
