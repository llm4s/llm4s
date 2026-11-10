#!/usr/bin/env bash
# Tests for scripts/check-support-table.sh (#2289): the real table and CHANGELOG pass; a release tag missing from the
# table, a CHANGELOG release missing from it, a date that disagrees, two `latest` lines, an unknown status and a
# duplicate version each fail; a pre-release tag is not checked against the table. No network.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/check-support-table.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
expect() { # name expected-exit actual-exit
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (exit $3, wanted $2)"; fails=$((fails + 1)); fi
}

cat > "$WORK/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

## [0.6.0] - 2026-12-01

## [0.5.1] - 2026-10-15

## [0.1.0] – [0.1.7] - 2025-04-06 – 2025-08-01
EOF
table() { # file, then lines of "version released status"
  local f="$1"; shift
  echo "lines:" > "$f"
  for l in "$@"; do
    set -- $l
    printf '  - version: "%s"\n    released: "%s"\n    status: "%s"\n    note: "x"\n' "$1" "$2" "$3" >> "$f"
  done
}
run() { "$SCRIPT" --table "$1" --changelog "$WORK/CHANGELOG.md" "${@:2}" >/dev/null 2>&1; echo $?; }

"$SCRIPT" >/dev/null; expect "the repository's table agrees with its CHANGELOG" 0 $?

table "$WORK/good.yml" "0.6.0 2026-12-01 latest" "0.5.1 2026-10-15 superseded"
expect "a consistent table passes" 0 "$(run "$WORK/good.yml")"
expect "the release tag of the latest line passes" 0 "$(run "$WORK/good.yml" --tag v0.6.0)"
expect "a release tag not in the table fails" 1 "$(run "$WORK/good.yml" --tag v0.6.1)"
expect "a release tag of a superseded line fails" 1 "$(run "$WORK/good.yml" --tag v0.5.1)"
expect "a pre-release tag is not checked against the table" 0 "$(run "$WORK/good.yml" --tag v1.0.0-RC1)"
expect "a malformed tag fails" 1 "$(run "$WORK/good.yml" --tag v1.0)"

table "$WORK/stale.yml" "0.5.1 2026-10-15 latest"
expect "a CHANGELOG release missing from the table fails" 1 "$(run "$WORK/stale.yml")"

table "$WORK/date.yml" "0.6.0 2026-12-02 latest" "0.5.1 2026-10-15 superseded"
expect "a release date that disagrees with the CHANGELOG fails" 1 "$(run "$WORK/date.yml")"

table "$WORK/two.yml" "0.6.0 2026-12-01 latest" "0.5.1 2026-10-15 latest"
expect "two latest lines fail" 1 "$(run "$WORK/two.yml")"

table "$WORK/order.yml" "0.5.1 2026-10-15 superseded" "0.6.0 2026-12-01 latest"
expect "a latest line that is not first fails" 1 "$(run "$WORK/order.yml")"

table "$WORK/status.yml" "0.6.0 2026-12-01 latest" "0.5.1 2026-10-15 maintained"
expect "an unknown status fails" 1 "$(run "$WORK/status.yml")"

table "$WORK/dup.yml" "0.6.0 2026-12-01 latest" "0.5.1 2026-10-15 superseded" "0.5.1 2026-10-15 eol"
expect "a version listed twice fails" 1 "$(run "$WORK/dup.yml")"

table "$WORK/badver.yml" "0.6.0 2026-12-01 latest" "0.5 2026-10-15 superseded"
expect "a version that is not MAJOR.MINOR.PATCH fails" 1 "$(run "$WORK/badver.yml")"

echo "lines:" > "$WORK/empty.yml"
expect "an empty table fails" 1 "$(run "$WORK/empty.yml")"

"$SCRIPT" --bogus >/dev/null 2>&1; expect "an unknown option is a usage error" 2 $?

if [ "$fails" -gt 0 ]; then echo "$fails test(s) failed"; exit 1; fi
echo "all check-support-table tests passed"
