#!/usr/bin/env bash
# Fails when docs/_data/support.yml, the table of release lines on the Support Policy page, is out of date (#2289).
#
# The table is updated by hand in the pull request that prepares a release (docs/reference/release.md, "Update the
# support table"). This script checks it:
#
#   always       every entry has a line (MAJOR.MINOR), a version (MAJOR.MINOR.PATCH within that line), a released
#                date (YYYY-MM-DD) and a known status (latest | superseded | security-only | eol); no line is listed
#                twice; exactly one line is `latest` and it is the first; a `security-only` line has a
#                `security_until` date and no `ended_on`; a `superseded` or `eol` line has an `ended_on` date, and a
#                `latest` line neither; no date is before the line's release (#2835); and the newest released section
#                of CHANGELOG.md (`## [X.Y.Z] - YYYY-MM-DD`) is that `latest` line's version, with the same date.
#                A `security-only` line whose `security_until` has passed is reported as a warning: mark it `eol`.
#   --tag vX.Y.Z also: the tag's version is the `latest` line. The release workflow runs this before anything is
#                published, so a release tag missing from the table fails the release. A pre-release tag
#                (`v1.0.0-RC1`) is not a support line and is not checked against the table.
#
# Usage: scripts/check-support-table.sh [--tag vX.Y.Z] [--table FILE] [--changelog FILE] [--today YYYY-MM-DD]
# Exit status: 0 when the table is consistent, 1 when it is not, 2 on a usage error.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TABLE="$REPO_ROOT/docs/_data/support.yml"
CHANGELOG="$REPO_ROOT/CHANGELOG.md"
TAG=""
TODAY="$(date -u +%Y-%m-%d)"

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --table) TABLE="${2:-}"; shift 2 ;;
    --changelog) CHANGELOG="${2:-}"; shift 2 ;;
    --today) TODAY="${2:-}"; shift 2 ;;
    *) echo "usage: $0 [--tag vX.Y.Z] [--table FILE] [--changelog FILE] [--today YYYY-MM-DD]" >&2; exit 2 ;;
  esac
done
for f in "$TABLE" "$CHANGELOG"; do
  [ -f "$f" ] || { echo "error: $f not found" >&2; exit 2; }
done

errors=0
fail() { echo "error: $*" >&2; errors=$((errors + 1)); }
is_date() { [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; }

# One "line|version|released|status|security_until|ended_on" row per `- line:` entry, in file order. A missing field
# is left empty. `|` rather than a tab, because `read` merges adjacent whitespace separators.
rows="$(awk '
  function val(line) { sub(/^[^:]*:[[:space:]]*/, "", line); gsub(/^"|"[[:space:]]*$/, "", line); return line }
  function flush() { if (seen) printf "%s|%s|%s|%s|%s|%s\n", l, v, r, s, u, e; l = v = r = s = u = e = "" }
  /^[[:space:]]*-[[:space:]]+line:/ { flush(); seen = 1; l = val($0); next }
  /^[[:space:]]+version:/        { v = val($0); next }
  /^[[:space:]]+released:/       { r = val($0); next }
  /^[[:space:]]+status:/         { s = val($0); next }
  /^[[:space:]]+security_until:/ { u = val($0); next }
  /^[[:space:]]+ended_on:/       { e = val($0); next }
  END { flush() }
' "$TABLE")"

[ -n "$rows" ] || { fail "$TABLE lists no release lines (each entry starts with '- line:')"; rows=""; }

latest_count=0; first_version=""; first_status=""; latest_date=""
seen_lines=" "
while IFS='|' read -r line version released status until ended; do
  [ -n "$line$version$released$status$until$ended" ] || continue
  [ -n "$first_version" ] || { first_version="$version"; first_status="$status"; }
  name="${line:-?}"
  [[ "$line" =~ ^[0-9]+\.[0-9]+$ ]] || fail "line '$line' is not MAJOR.MINOR"
  if [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    [ "${version%.*}" = "$line" ] || fail "$name: version $version is not in line $line"
  else
    fail "$name: version '$version' is not MAJOR.MINOR.PATCH"
  fi
  is_date "$released" || fail "$name: released '$released' is not YYYY-MM-DD"
  for d in "security_until:$until" "ended_on:$ended"; do
    key="${d%%:*}"; value="${d#*:}"
    [ -z "$value" ] && continue
    if ! is_date "$value"; then fail "$name: $key '$value' is not YYYY-MM-DD"
    elif is_date "$released" && [[ "$value" < "$released" ]]; then fail "$name: $key $value is before its release ($released)"
    fi
  done
  case "$status" in
    latest)
      latest_count=$((latest_count + 1)); latest_date="$released"
      [ -z "$until$ended" ] || fail "$name: a latest line has no security_until or ended_on"
      ;;
    security-only)
      [ -n "$until" ] || fail "$name: a security-only line needs security_until, the last day of security fixes"
      [ -z "$ended" ] || fail "$name: a security-only line has not ended: remove ended_on, or mark it eol"
      if [ -n "$until" ] && is_date "$until" && [[ "$until" < "$TODAY" ]]; then
        echo "warning: $name: security fixes ended on $until; mark the line eol with ended_on: \"$until\"" >&2
      fi
      ;;
    superseded|eol)
      [ -n "$ended" ] || fail "$name: a $status line needs ended_on, the day it stopped getting fixes"
      ;;
    *) fail "$name: status '$status' is not latest, superseded, security-only or eol" ;;
  esac
  case "$seen_lines" in *" $line "*) fail "line $line is listed twice" ;; esac
  seen_lines="$seen_lines$line "
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
