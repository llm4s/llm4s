#!/usr/bin/env bash
# Fails when docs/_data/support.yml, the table of release lines on the Support Policy page, is out of date (#2289).
#
# The table is updated by hand in the pull request that prepares a release (docs/reference/release.md, "Update the
# support table"). This script checks it:
#
#   always       every line has a version (MAJOR.MINOR.PATCH), a released date (YYYY-MM-DD) and a known status
#                (latest | superseded | security-only | eol); no version is listed twice; exactly one line is
#                `latest` and it is the first; and the newest released section of CHANGELOG.md
#                (`## [X.Y.Z] - YYYY-MM-DD`) is that `latest` line, with the same date.
#   --tag vX.Y.Z also: the tag's version is the `latest` line. The release workflow runs this before anything is
#                published, so a release tag missing from the table fails the release. A pre-release tag
#                (`v1.0.0-RC1`) is not a support line and is not checked against the table.
#
# Usage: scripts/check-support-table.sh [--tag vX.Y.Z] [--table FILE] [--changelog FILE]
# Exit status: 0 when the table is consistent, 1 when it is not, 2 on a usage error.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TABLE="$REPO_ROOT/docs/_data/support.yml"
CHANGELOG="$REPO_ROOT/CHANGELOG.md"
TAG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --table) TABLE="${2:-}"; shift 2 ;;
    --changelog) CHANGELOG="${2:-}"; shift 2 ;;
    *) echo "usage: $0 [--tag vX.Y.Z] [--table FILE] [--changelog FILE]" >&2; exit 2 ;;
  esac
done
for f in "$TABLE" "$CHANGELOG"; do
  [ -f "$f" ] || { echo "error: $f not found" >&2; exit 2; }
done

errors=0
fail() { echo "error: $*" >&2; errors=$((errors + 1)); }

# One "version<TAB>released<TAB>status" row per `- version:` entry, in file order. A missing field is left empty.
rows="$(awk '
  function val(line) { sub(/^[^:]*:[[:space:]]*/, "", line); gsub(/^"|"[[:space:]]*$/, "", line); return line }
  function flush() { if (seen) printf "%s\t%s\t%s\n", v, r, s; v = r = s = "" }
  /^[[:space:]]*-[[:space:]]+version:/ { flush(); seen = 1; v = val($0); next }
  /^[[:space:]]+released:/ { r = val($0); next }
  /^[[:space:]]+status:/   { s = val($0); next }
  END { flush() }
' "$TABLE")"

[ -n "$rows" ] || { fail "$TABLE lists no release lines"; rows=""; }

latest_count=0; first_version=""; first_status=""; latest_date=""
seen_versions=" "
while IFS=$'\t' read -r version released status; do
  [ -n "$version$released$status" ] || continue
  [ -n "$first_version" ] || { first_version="$version"; first_status="$status"; }
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version '$version' is not MAJOR.MINOR.PATCH"
  [[ "$released" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail "$version: released '$released' is not YYYY-MM-DD"
  case "$status" in
    latest) latest_count=$((latest_count + 1)); latest_date="$released" ;;
    superseded|security-only|eol) ;;
    *) fail "$version: status '$status' is not latest, superseded, security-only or eol" ;;
  esac
  case "$seen_versions" in *" $version "*) fail "$version is listed twice" ;; esac
  seen_versions="$seen_versions$version "
done <<< "$rows"

[ "$latest_count" -eq 1 ] || fail "exactly one line must be 'latest', found $latest_count"
[ -z "$first_version" ] || [ "$first_status" = "latest" ] || fail "the first line ($first_version) is '$first_status', not 'latest': list the newest line first"

# The newest released CHANGELOG section, skipping [Unreleased] and the range heading of the 0.1.x releases.
newest="$(grep -m1 -E '^## \[[0-9]+\.[0-9]+\.[0-9]+\] - [0-9]{4}-[0-9]{2}-[0-9]{2}' "$CHANGELOG" || true)"
if [ -n "$newest" ]; then
  cl_version="$(sed -E 's/^## \[([^]]+)\].*/\1/' <<< "$newest")"
  cl_date="$(sed -E 's/^## \[[^]]+\] - ([0-9-]{10}).*/\1/' <<< "$newest")"
  if [ "$first_version" != "$cl_version" ] || [ "$first_status" != "latest" ]; then
    fail "CHANGELOG.md's newest release is $cl_version, but the table's 'latest' line is ${first_version:-missing}: add $cl_version to $TABLE"
  elif [ "$latest_date" != "$cl_date" ]; then
    fail "$cl_version: the table says released $latest_date, CHANGELOG.md says $cl_date"
  fi
else
  fail "CHANGELOG.md has no released section (## [X.Y.Z] - YYYY-MM-DD)"
fi

if [ -n "$TAG" ]; then
  tag_version="${TAG#v}"
  if [[ "$tag_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-.+$ ]]; then
    echo "note: $TAG is a pre-release tag, not a support line; not checked against the table"
  elif [[ "$tag_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    [ "$tag_version" = "$first_version" ] && [ "$first_status" = "latest" ] ||
      fail "release tag $TAG is not the 'latest' line of $TABLE (the latest is ${first_version:-missing}): update the table before tagging (docs/reference/release.md)"
  else
    fail "tag '$TAG' is not vMAJOR.MINOR.PATCH[-suffix]"
  fi
fi

if [ "$errors" -gt 0 ]; then
  echo "support table check failed ($errors error(s)); see docs/reference/release.md#update-the-support-table" >&2
  exit 1
fi
echo "support table ok: latest is $first_version${TAG:+ (tag $TAG)}"
