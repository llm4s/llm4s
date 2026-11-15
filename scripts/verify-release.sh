#!/usr/bin/env bash
# Verifies that a release is on Maven Central: every artifact the build publishes resolves at <version>.
#
#   scripts/verify-release.sh 0.5.0
#   scripts/verify-release.sh v0.5.0                       # a leading v is accepted
#   scripts/verify-release.sh 0.5.0 --list expected.txt    # use a saved list instead of asking sbt
#   scripts/verify-release.sh 0.5.0 --attestations         # also check each artifact's build provenance
#
# The expected coordinates come from the build (`sbt -error listPublishedArtifacts`), not from a list kept
# here, so a module added to the build is checked the day it is added. For each real artifact the POM and
# the jar must exist; for an artifact published from the Gradle build (`llm4s-kotlin-api`, a `gradle` line) the POM
# and the jar must exist and the POM must depend on `llm4s-java-api_3` at the same version, the one the release
# published from sbt; for the bill of materials (`llm4s-bom`, POM-only) the POM must exist and must carry a
# <dependencyManagement>; for each relocation stub (the pre-0.4.0 coordinates, `core`, `workspaceclient`, ...)
# the POM must exist and must carry a <relocation>. v0.4.0 shipped without its stubs and nobody noticed
# until a user could not resolve `org.llm4s:core:0.4.0` (#1150); this is the check that would have said so.
#
# Maven Central's index can lag a successful publish by a while. A 404 straight after the release job
# finishes is not proof of a miss: re-run before concluding one.
#
# With --attestations each resolved artifact is also downloaded (the jar, or the POM of the BOM and of a stub) and
# checked with `gh attestation verify <file> -R llm4s/llm4s --signer-workflow <release.yml>`, and so is the container
# image, oci://ghcr.io/llm4s/workspace-runner:<version> (#2305, docs/reference/verifying-releases.md). It needs the
# GitHub CLI, logged in. Releases up to 0.5.1 predate the attestations and fail this mode.
#   GH        the gh executable (default: gh)
#   IMAGE     the image to check (default: ghcr.io/llm4s/workspace-runner)
#
# Exit code 0 = everything resolves. 1 = something is missing. 2 = usage or a failure to get the list.
set -uo pipefail

BASE="${MAVEN_CENTRAL_URL:-https://repo1.maven.org/maven2}/org/llm4s"

REPO=llm4s/llm4s
SIGNER_WORKFLOW="$REPO/.github/workflows/release.yml"
GH="${GH:-gh}"
IMAGE="${IMAGE:-ghcr.io/llm4s/workspace-runner}"

usage() { echo "usage: $0 <version> [--list <file>] [--attestations]" >&2; exit 2; }

[ $# -ge 1 ] || usage
VERSION="${1#v}"; shift
LIST_FILE=""
ATTEST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --list) [ $# -ge 2 ] || usage; LIST_FILE="$2"; shift 2 ;;
    --attestations) ATTEST=1; shift ;;
    *) usage ;;
  esac
done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.+-]*)?$ ]] || { echo "not a version: '$VERSION'" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "$LIST_FILE" ]; then
  LIST="$(cat "$LIST_FILE")" || exit 2
else
  echo "Asking sbt which artifacts the build publishes..." >&2
  LIST="$(cd "$REPO_ROOT" && sbt -error listPublishedArtifacts 2>/dev/null | grep -E '^(artifact|gradle|bom|stub) ')" || true
fi
[ -n "$LIST" ] || { echo "No artifacts listed: sbt printed nothing. Is 'listPublishedArtifacts' defined?" >&2; exit 2; }

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$1" || echo 000; }

if [ "$ATTEST" = 1 ]; then
  command -v "$GH" >/dev/null 2>&1 || { echo "--attestations needs the GitHub CLI ('$GH' not found)." >&2; exit 2; }
  DL="$(mktemp -d)"
  trap 'rm -rf "$DL"' EXIT
fi

# attested <file or oci:// reference>: does `gh attestation verify` accept it as built by this repository's release
# workflow? The reason is left in ATTEST_ERR.
attested() {
  ATTEST_ERR="$("$GH" attestation verify "$1" -R "$REPO" --signer-workflow "$SIGNER_WORKFLOW" 2>&1 >/dev/null)" && return 0
  ATTEST_ERR="$(printf '%s' "$ATTEST_ERR" | tail -1)"
  return 1
}

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
  elif [ "$kind" = gradle ]; then
    # Built by Gradle from the same tag: its POM must name the facade it wraps at this very version, or a user gets
    # a Kotlin API compiled against one release and a facade from another.
    pom_text="$(curl -s --max-time 30 "$pom" | tr -d ' \t\r\n')"
    grep -qF "<artifactId>llm4s-java-api_3</artifactId><version>$VERSION</version>" <<<"$pom_text" ||
      problems="pom does not depend on llm4s-java-api_3 $VERSION"
  elif [ "$kind" = bom ]; then
    # POM-only: there is no jar, but the POM must carry the managed versions that make it a BOM.
    curl -s --max-time 30 "$pom" | grep -q '<dependencyManagement>' || problems="pom has no <dependencyManagement>"
  fi
  if { [ "$kind" = artifact ] || [ "$kind" = gradle ]; } && [ -z "$problems" ]; then
    jcode="$(status "$BASE/$id/$VERSION/$id-$VERSION.jar")"
    [ "$jcode" = 200 ] || problems="jar HTTP $jcode"
  fi
  if [ "$ATTEST" = 1 ] && [ -z "$problems" ]; then
    case "$kind" in artifact | gradle) file="$id-$VERSION.jar" ;; *) file="$id-$VERSION.pom" ;; esac
    if ! curl -s --max-time 120 "$BASE/$id/$VERSION/$file" > "$DL/$file"; then
      problems="could not download $file"
    elif ! attested "$DL/$file"; then
      problems="no build provenance for $file: $ATTEST_ERR"
    fi
  fi
  if [ -n "$problems" ]; then
    printf '  MISSING  %-8s %-40s %s\n' "$kind" "$id" "$problems"
    missing=$((missing + 1))
  else
    printf '  ok       %-8s %s\n' "$kind" "$id"
  fi
done <<< "$LIST"

if [ "$ATTEST" = 1 ]; then
  checked=$((checked + 1))
  if attested "oci://$IMAGE:$VERSION"; then
    printf '  ok       %-8s %s\n' image "$IMAGE:$VERSION"
  else
    printf '  MISSING  %-8s %-40s %s\n' image "$IMAGE:$VERSION" "no build provenance: $ATTEST_ERR"
    missing=$((missing + 1))
  fi
fi

echo
if [ "$missing" -gt 0 ]; then
  if [ "$ATTEST" = 1 ]; then
    echo "$missing of $checked coordinates do not resolve at $VERSION on Maven Central, or have no build provenance."
  else
    echo "$missing of $checked coordinates do not resolve at $VERSION on Maven Central."
  fi
  echo "If the release job finished minutes ago, the index may still be catching up: re-run before concluding."
  exit 1
fi
if [ "$ATTEST" = 1 ]; then
  echo "All $checked coordinates resolve at $VERSION, each with build provenance from $SIGNER_WORKFLOW."
else
  echo "All $checked coordinates resolve at $VERSION."
fi
