#!/usr/bin/env bash
# Verifies that a release is on Maven Central: every artifact the build publishes resolves at <version>.
#
#   scripts/verify-release.sh 1.0.0
#   scripts/verify-release.sh v1.0.0                       # a leading v is accepted
#   scripts/verify-release.sh 1.0.0 --list expected.txt    # use a saved list instead of asking sbt
#   scripts/verify-release.sh 1.0.0 --attestations         # also check each artifact's build provenance
#
# The expected coordinates come from the build (`sbt -error listPublishedArtifacts`), not from a list kept
# here, so a module added to the build is checked the day it is added. For each real artifact the POM and
# the jar must exist, and its -javadoc.jar must be the small pointer to https://llm4s.org/scaladoc/ (project/
# JavadocPointer.scala); for an artifact published from the Gradle build (`llm4s-kotlin-api`, a `gradle` line) the POM
# and the jar and the javadoc jar must exist and the POM must depend on `llm4s-java-api_3` at the same version, the one the release
# published from sbt; for the bill of materials (`llm4s-bom`, POM-only) the POM must exist and must carry a
# <dependencyManagement>; for each relocation stub (the pre-0.4.0 coordinates, `core`, `workspaceclient`, ...)
# the POM must exist and must carry a <relocation>. v0.4.0 shipped without its stubs and nobody noticed
# until a user could not resolve `org.llm4s:core:0.4.0` (#1150); this is the check that would have said so.
#
# Every file of an sbt-published coordinate (the POM, and for an `artifact` the jar, sources jar and javadoc jar) must
# have its .asc, .md5 and .sha1, and nothing more: no checksum of a signature (`.pom.asc.md5`) and no
# `-cyclonedx.json` SBOM, which Sonatype's 1,100-files-a-month limit has no room for (project/CentralBundle.scala; the
# SBOMs are on the GitHub Release). Only the POM's and the jar's extras are probed, not every file's.
#
# Those upload rules (the pointer javadoc jar, no checksum of a signature, no SBOM on Central; #2667) apply to every
# release from 0.7.0 (the script tests for 0.6.1 or later).
# 0.6.0 and earlier were uploaded before them, with each module's full Scaladoc in its -javadoc.jar and the extras: for
# those the javadoc jar must only exist, and the extras are not probed.
#
# Maven Central's index can lag a successful publish by a while. A 404 straight after the release job
# finishes is not proof of a miss: re-run before concluding one.
#
# With --attestations each resolved artifact is also downloaded (the jar, or the POM of the BOM and of a stub) and
# checked with `gh attestation verify <file> -R llm4s/llm4s --signer-workflow <release.yml>`, and so is the container
# image, oci://ghcr.io/llm4s/workspace-runner:<version> (#2305, docs/reference/verifying-releases.md). It needs the
# GitHub CLI, logged in. Releases up to 0.6.0 predate the attestations and fail this mode.
#   GH        the gh executable (default: gh)
#   IMAGE     the image to check (default: ghcr.io/llm4s/workspace-runner)
#
# Exit code 0 = everything resolves. 1 = something is missing. 2 = usage or a failure to get the list.
set -uo pipefail

BASE="${MAVEN_CENTRAL_URL:-https://repo1.maven.org/maven2}/org/llm4s"

REPO=llm4s/llm4s
SIGNER_WORKFLOW="$REPO/.github/workflows/release.yml"
# `llm4s-kotlin-api` is attested in the job that builds it, which runs in the reusable workflow release.yml calls: the
# certificate names that workflow, not release.yml.
KOTLIN_SIGNER_WORKFLOW="$REPO/.github/workflows/publish-kotlin.yml"
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

JAVADOC_MAX_BYTES=51200   # JavadocPointer.MaxBytes

# Was VERSION uploaded under the current rules (0.6.1 and later, a pre-release of 0.6.1 included)?
IFS=. read -r vmajor vminor vpatch _ <<<"${VERSION%%[-+]*}"
if [ "$vmajor" -gt 0 ] || [ "$vminor" -gt 6 ] || { [ "$vminor" -eq 6 ] && [ "$vpatch" -ge 1 ]; }; then
  SLIM_UPLOAD=1
else
  SLIM_UPLOAD=0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ "$ATTEST" = 1 ]; then
  command -v "$GH" >/dev/null 2>&1 || { echo "--attestations needs the GitHub CLI ('$GH' not found)." >&2; exit 2; }
fi

# attested <file or oci:// reference> [signer workflow]: does `gh attestation verify` accept it as built by this
# repository's release workflow (or the workflow given)? The reason is left in ATTEST_ERR.
attested() {
  ATTEST_ERR="$("$GH" attestation verify "$1" -R "$REPO" --signer-workflow "${2:-$SIGNER_WORKFLOW}" 2>&1 >/dev/null)" && return 0
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
  # Maven Central requires a javadoc jar. An sbt module's is the pointer to the site's Scaladoc
  # (project/JavadocPointer.scala), so it must be small and say where the API docs are; the Kotlin artifact's is Dokka's.
  # Up to 0.6.0 an sbt module's held its full Scaladoc, so for those only its presence is checked.
  if { [ "$kind" = artifact ] || [ "$kind" = gradle ]; } && [ -z "$problems" ]; then
    javadoc="$BASE/$id/$VERSION/$id-$VERSION-javadoc.jar"
    dcode="$(status "$javadoc")"
    if [ "$dcode" != 200 ]; then
      problems="javadoc jar HTTP $dcode"
    elif [ "$kind" = artifact ] && [ "$SLIM_UPLOAD" = 1 ]; then
      curl -s --max-time 60 "$javadoc" > "$TMP/javadoc.jar"
      size="$(wc -c < "$TMP/javadoc.jar" | tr -d ' ')"
      if [ "$size" -gt "$JAVADOC_MAX_BYTES" ]; then
        problems="javadoc jar is $size bytes, over $JAVADOC_MAX_BYTES: not the pointer jar"
      elif ! unzip -p "$TMP/javadoc.jar" README.md 2>/dev/null | grep -qF 'https://llm4s.org/scaladoc/'; then
        problems="javadoc jar has no README.md pointing to https://llm4s.org/scaladoc/"
      fi
    fi
  fi
  # What Central requires beside each file, and the extras a release no longer uploads (sbt-published coordinates).
  if [ "$kind" != gradle ] && [ -z "$problems" ]; then
    stem="$BASE/$id/$VERSION/$id-$VERSION"
    files=(".pom")
    [ "$kind" = artifact ] && files+=(".jar" "-sources.jar" "-javadoc.jar")
    for f in "${files[@]}"; do
      for c in .asc .md5 .sha1; do
        ccode="$(status "$stem$f$c")"
        [ "$ccode" = 200 ] || problems="${problems:+$problems; }$id-$VERSION$f$c HTTP $ccode"
      done
    done
    if [ "$SLIM_UPLOAD" = 1 ]; then
      for extra in ".pom.asc.md5" "-cyclonedx.json"; do
        [ "$kind" = artifact ] && [ "$extra" = ".pom.asc.md5" ] && extra=".jar.asc.md5"
        [ "$(status "$stem$extra")" = 404 ] || problems="${problems:+$problems; }$id-$VERSION$extra was uploaded"
      done
    fi
  fi
  if [ "$ATTEST" = 1 ] && [ -z "$problems" ]; then
    case "$kind" in artifact | gradle) file="$id-$VERSION.jar" ;; *) file="$id-$VERSION.pom" ;; esac
    if ! curl -s --max-time 120 "$BASE/$id/$VERSION/$file" > "$TMP/$file"; then
      problems="could not download $file"
    elif ! { if [ "$kind" = gradle ]; then attested "$TMP/$file" "$KOTLIN_SIGNER_WORKFLOW"; else attested "$TMP/$file"; fi; }; then
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
  echo "All $checked coordinates resolve at $VERSION, each with build provenance from $SIGNER_WORKFLOW (llm4s-kotlin-api: $KOTLIN_SIGNER_WORKFLOW)."
else
  echo "All $checked coordinates resolve at $VERSION."
fi
