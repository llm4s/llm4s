#!/usr/bin/env bash
# Tests for scripts/check-support-table.sh (#2289): the real table and CHANGELOG pass; a release tag missing from the
# table, a CHANGELOG release missing from it, a date that disagrees, two `latest` lines, an unknown status, a
# duplicate line and a line's missing or misplaced security_until / ended_on (#2835) each fail; a pre-release tag is
# not checked against the table. No network.
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
table() { # file, then lines of "line version released status [security_until|-] [ended_on|-]"
  local f="$1"; shift
  echo "lines:" > "$f"
  for l in "$@"; do
    set -- $l
    printf '  - line: "%s"\n    version: "%s"\n    released: "%s"\n    status: "%s"\n' "$1" "$2" "$3" "$4" >> "$f"
    [ "${5:--}" = "-" ] || printf '    security_until: "%s"\n' "$5" >> "$f"
    [ "${6:--}" = "-" ] || printf '    ended_on: "%s"\n' "$6" >> "$f"
    echo '    note: "x"' >> "$f"
  done
}
run() { "$SCRIPT" --table "$1" --changelog "$WORK/CHANGELOG.md" --today 2027-01-01 "${@:2}" >/dev/null 2>&1; echo $?; }

"$SCRIPT" >/dev/null; expect "the repository's table agrees with its CHANGELOG" 0 $?

OLD="0.5 0.5.1 2026-10-15 superseded - 2026-12-01"
table "$WORK/good.yml" "0.6 0.6.0 2026-12-01 latest" "$OLD"
expect "a consistent table passes" 0 "$(run "$WORK/good.yml")"
expect "the release tag of the latest line passes" 0 "$(run "$WORK/good.yml" --tag v0.6.0)"
expect "a release tag not in the table fails" 1 "$(run "$WORK/good.yml" --tag v0.6.1)"
expect "a release tag of a superseded line fails" 1 "$(run "$WORK/good.yml" --tag v0.5.1)"
expect "a pre-release tag is not checked against the table" 0 "$(run "$WORK/good.yml" --tag v1.0.0-RC1)"
expect "a malformed tag fails" 1 "$(run "$WORK/good.yml" --tag v1.0)"

table "$WORK/stale.yml" "0.5 0.5.1 2026-10-15 latest"
expect "a CHANGELOG release missing from the table fails" 1 "$(run "$WORK/stale.yml")"

table "$WORK/date.yml" "0.6 0.6.0 2026-12-02 latest" "$OLD"
expect "a release date that disagrees with the CHANGELOG fails" 1 "$(run "$WORK/date.yml")"

table "$WORK/two.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 latest"
expect "two latest lines fail" 1 "$(run "$WORK/two.yml")"

table "$WORK/order.yml" "$OLD" "0.6 0.6.0 2026-12-01 latest"
expect "a latest line that is not first fails" 1 "$(run "$WORK/order.yml")"

table "$WORK/status.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 maintained"
expect "an unknown status fails" 1 "$(run "$WORK/status.yml")"

table "$WORK/dup.yml" "0.6 0.6.0 2026-12-01 latest" "$OLD" "0.5 0.5.0 2026-10-15 eol - 2026-12-01"
expect "a line listed twice fails" 1 "$(run "$WORK/dup.yml")"

table "$WORK/badver.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5 2026-10-15 superseded - 2026-12-01"
expect "a version that is not MAJOR.MINOR.PATCH fails" 1 "$(run "$WORK/badver.yml")"

table "$WORK/badline.yml" "0.6 0.6.0 2026-12-01 latest" "0.5.1 0.5.1 2026-10-15 superseded - 2026-12-01"
expect "a line that is not MAJOR.MINOR fails" 1 "$(run "$WORK/badline.yml")"

table "$WORK/otherline.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.4.1 2026-10-15 superseded - 2026-12-01"
expect "a version outside its line fails" 1 "$(run "$WORK/otherline.yml")"

table "$WORK/security.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 security-only 2027-03-01"
expect "a security-only line with security_until passes" 0 "$(run "$WORK/security.yml")"

table "$WORK/nountil.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 security-only"
expect "a security-only line without security_until fails" 1 "$(run "$WORK/nountil.yml")"

table "$WORK/secended.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 security-only 2027-03-01 2027-03-01"
expect "a security-only line with ended_on fails" 1 "$(run "$WORK/secended.yml")"

table "$WORK/passed.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 security-only 2026-12-15"
expect "a security-only line whose window has passed only warns" 0 "$(run "$WORK/passed.yml")"
"$SCRIPT" --table "$WORK/passed.yml" --changelog "$WORK/CHANGELOG.md" --today 2027-01-01 2>&1 >/dev/null | grep -q "warning: 0.5: security fixes ended on 2026-12-15"
expect "a security-only line whose window has passed is named in a warning" 0 $?

table "$WORK/noend.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 eol"
expect "an eol line without ended_on fails" 1 "$(run "$WORK/noend.yml")"

table "$WORK/supnoend.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 superseded"
expect "a superseded line without ended_on fails" 1 "$(run "$WORK/supnoend.yml")"

table "$WORK/eolkept.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 eol 2027-03-01 2027-03-01"
expect "an eol line keeps its security_until" 0 "$(run "$WORK/eolkept.yml")"

table "$WORK/latestend.yml" "0.6 0.6.0 2026-12-01 latest - 2027-01-01" "$OLD"
expect "a latest line with ended_on fails" 1 "$(run "$WORK/latestend.yml")"

table "$WORK/baddate.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 eol - 1-12-2026"
expect "an ended_on that is not YYYY-MM-DD fails" 1 "$(run "$WORK/baddate.yml")"

table "$WORK/early.yml" "0.6 0.6.0 2026-12-01 latest" "0.5 0.5.1 2026-10-15 eol - 2026-10-01"
expect "an ended_on before the line's release fails" 1 "$(run "$WORK/early.yml")"

echo "lines:" > "$WORK/empty.yml"
expect "an empty table fails" 1 "$(run "$WORK/empty.yml")"

"$SCRIPT" --bogus >/dev/null 2>&1; expect "an unknown option is a usage error" 2 $?

if [ "$fails" -gt 0 ]; then echo "$fails test(s) failed"; exit 1; fi
echo "all check-support-table tests passed"
