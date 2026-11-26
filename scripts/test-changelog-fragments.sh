#!/usr/bin/env bash
# Tests for scripts/changelog-fragments.sh (#3121): `assemble` folds sample fragments into a CHANGELOG with today's
# layout (a missing area heading goes in its place, fragments follow the entries already there in issue order, a
# breaking line goes under ### Breaking changes) and deletes them; it only adds the folded lines (and a heading) to the
# real CHANGELOG.md; and `check` fails each kind of malformed fragment with a message naming it.
#
# Usage: scripts/test-changelog-fragments.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/changelog-fragments.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0
ok() { PASSED=$((PASSED + 1)); }
ko() { FAILED=$((FAILED + 1)); echo "FAIL: $*"; }
link() { echo "[#$1](https://github.com/llm4s/llm4s/issues/$1)"; }

# ---- assemble, on a sample CHANGELOG and sample fragments
S="$WORK/sample"
mkdir -p "$S/changelog.d/core" "$S/changelog.d/observability" "$S/changelog.d/build-tooling" "$S/docs/reference"
touch "$S/docs/reference/migration.md"
cat > "$S/CHANGELOG.md" <<EOF
# Changelog

## [Unreleased]

<!-- The net change since 1.0.0, by area. -->

### Breaking changes

- An existing breaking line $(link 10).

### Core

- **Added** an existing core entry $(link 11).

### Build & tooling

- **Changed** an existing tooling entry
  on two lines $(link 12).

## [1.0.0] - 2026-11-25

### Core

- **Added** a released entry $(link 1).
EOF
cat > "$S/changelog.d/core/30.md" <<EOF
- **Fixed** core thirty $(link 30).
EOF
cat > "$S/changelog.d/core/20.md" <<EOF
---
breaking: Behaviour: twenty breaks ([migration](docs/reference/migration.md#twenty)) $(link 20).
---
- **Changed** core twenty
  continues here $(link 20).
- **Added** core twenty again $(link 20).
EOF
cat > "$S/changelog.d/observability/25.md" <<EOF
- **Added** observability twenty-five $(link 25).
EOF
cat > "$S/changelog.d/build-tooling/21.md" <<EOF
- **Removed** tooling twenty-one $(link 21).
EOF
cat > "$WORK/expected.md" <<EOF
# Changelog

## [Unreleased]

<!-- The net change since 1.0.0, by area. -->

### Breaking changes

- An existing breaking line $(link 10).
- Behaviour: twenty breaks ([migration](docs/reference/migration.md#twenty)) $(link 20).

### Core

- **Added** an existing core entry $(link 11).
- **Changed** core twenty
  continues here $(link 20).
- **Added** core twenty again $(link 20).
- **Fixed** core thirty $(link 30).

### Observability

- **Added** observability twenty-five $(link 25).

### Build & tooling

- **Changed** an existing tooling entry
  on two lines $(link 12).
- **Removed** tooling twenty-one $(link 21).

## [1.0.0] - 2026-11-25

### Core

- **Added** a released entry $(link 1).
EOF
"$SCRIPT" check --root "$S" > /dev/null 2>&1 && ok || ko "the sample fragments pass check"
"$SCRIPT" assemble --root "$S" > "$WORK/out" 2>&1 && ok || { ko "assemble fails on the sample"; cat "$WORK/out"; }
diff -u "$WORK/expected.md" "$S/CHANGELOG.md" > "$WORK/diff" && ok || { ko "assembled CHANGELOG differs:"; cat "$WORK/diff"; }
[ -z "$(find "$S/changelog.d" -type f)" ] && ok || ko "assemble leaves fragments behind: $(find "$S/changelog.d" -type f)"
cp "$S/CHANGELOG.md" "$WORK/again.md"
"$SCRIPT" assemble --root "$S" > /dev/null 2>&1 && cmp -s "$WORK/again.md" "$S/CHANGELOG.md" && ok ||
  ko "assemble with no fragments changes the CHANGELOG"

# An [Unreleased] with no area heading yet: the headings are created in order.
E="$WORK/empty"
mkdir -p "$E/changelog.d/tools" "$E/changelog.d/core"
printf '# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-11-25\n\n- old\n' > "$E/CHANGELOG.md"
echo "- **Fixed** a tool $(link 5)." > "$E/changelog.d/tools/5.md"
echo "- **Added** a core thing $(link 6)." > "$E/changelog.d/core/6.md"
"$SCRIPT" assemble --root "$E" > /dev/null 2>&1
printf '# Changelog\n\n## [Unreleased]\n\n### Core\n\n- **Added** a core thing %s.\n\n### Tools\n\n- **Fixed** a tool %s.\n\n## [1.0.0] - 2026-11-25\n\n- old\n' \
  "$(link 6)" "$(link 5)" > "$WORK/expected-empty.md"
diff -u "$WORK/expected-empty.md" "$E/CHANGELOG.md" > "$WORK/diff" && ok || { ko "headings created in order:"; cat "$WORK/diff"; }

# The real CHANGELOG.md, whatever [Unreleased] holds now: folding one fragment only adds lines (its entry, and the
# `### Tools` heading with its blank lines when the list is not there yet) and removes or changes nothing.
R="$WORK/real"
mkdir -p "$R/changelog.d/tools"
cp "$REPO_ROOT/CHANGELOG.md" "$R/"
echo "- **Fixed** a sample $(link 99999)." > "$R/changelog.d/tools/99999.md"
"$SCRIPT" assemble --root "$R" > /dev/null 2>&1
changed="$(diff "$REPO_ROOT/CHANGELOG.md" "$R/CHANGELOG.md" | grep '^[<>]')"
removed="$(grep -c '^<' <<< "$changed")"
unexpected="$(grep '^>' <<< "$changed" | grep -vxF -e '> ' -e '>' -e '> ### Tools' -e "> - **Fixed** a sample $(link 99999).")"
[ "$removed" -eq 0 ] && [ -z "$unexpected" ] && grep -qxF "> - **Fixed** a sample $(link 99999)." <<< "$changed" && ok ||
  ko "folding into the real CHANGELOG.md changes more than the entry (and its heading): $changed"
# ... and into a fixed copy of it whose [Unreleased] is empty, as just after a release: the heading is created too.
awk '/^## \[Unreleased\]/ { print; print ""; skip = 1; next } skip && /^## / { skip = 0 } !skip' \
  "$REPO_ROOT/CHANGELOG.md" > "$R/CHANGELOG.md"
cp "$R/CHANGELOG.md" "$WORK/released.md"
echo "- **Fixed** a sample $(link 99999)." > "$R/changelog.d/tools/99999.md"
"$SCRIPT" assemble --root "$R" > /dev/null 2>&1
entry="- **Fixed** a sample $(link 99999)."
ENTRY="$entry" awk '{ print } /^## \[Unreleased\]/ { print ""; print "### Tools"; print ""; print ENVIRON["ENTRY"] }' \
  "$WORK/released.md" > "$WORK/expected-released.md"
diff -u "$WORK/expected-released.md" "$R/CHANGELOG.md" > "$WORK/diff" && ok ||
  { ko "folding into an empty [Unreleased] adds more than the heading and the entry:"; cat "$WORK/diff"; }

# changelog.d/README.md's table lists the script's areas, in its order.
table="$(sed -nE 's/^\| `([a-z-]+)` \| (.*) \|$/\1\t\2/p' "$REPO_ROOT/changelog.d/README.md")"
[ -n "$table" ] && [ "$table" = "$("$SCRIPT" areas)" ] && ok ||
  ko "changelog.d/README.md's table does not match \`changelog-fragments.sh areas\`"
# Every area heading the real [Unreleased] uses has a directory.
unknown="$(awk '/^## /{n++} n==1 && /^### /{print substr($0,5)}' "$REPO_ROOT/CHANGELOG.md" |
  grep -vxF -e 'Breaking changes' -e 'Highlights' | grep -vxF -f <("$SCRIPT" areas | cut -f2))"
[ -z "$unknown" ] && ok || ko "[Unreleased] uses headings with no fragment directory: $unknown"

# The repository's own fragments pass.
"$SCRIPT" check > "$WORK/out" 2>&1 && ok || { ko "the repository's fragments fail check:"; cat "$WORK/out"; }

# ---- check fails a malformed fragment
C="$WORK/check"
expect_fail() {
  local name="$1" path="$2" message="$3" content="$4"
  rm -rf "$C"
  mkdir -p "$C/changelog.d/$(dirname "$path")" "$C/docs"
  touch "$C/docs/exists.md"
  printf '%s\n' "$content" > "$C/changelog.d/$path"
  if "$SCRIPT" check --root "$C" > "$WORK/out" 2>&1; then
    ko "$name: expected failure"
  elif grep -qF -- "$message" "$WORK/out"; then
    ok
  else
    ko "$name: expected '$message' in:"; cat "$WORK/out"
  fi
}
good="- **Fixed** a thing $(link 7) ([Doc](docs/exists.md#x))."
expect_fail "an unknown area" "misc/7.md" "with <area> one of core" "$good"
expect_fail "a file directly under changelog.d" "7.md" "a fragment is changelog.d/<area>/<issue>.md" "$good"
expect_fail "a file not named for an issue" "core/my-change.md" "named for its issue" "$good"
expect_fail "a leading zero" "core/07.md" "named for its issue" "$good"
expect_fail "no leading verb" "core/7.md" "an entry starts" "- Fixed a thing $(link 7)."
expect_fail "an unknown verb" "core/7.md" "an entry starts" "- **Improved** a thing $(link 7)."
expect_fail "a line that is not an entry" "core/7.md" "line 1 is neither an entry" "Fixed a thing $(link 7)."
expect_fail "three lines" "core/7.md" "is 3 lines" $'- **Fixed** a\n  b\n  c '"$(link 7)"
expect_fail "too long" "core/7.md" "characters; an entry is at most 900" "- **Fixed** $(printf 'x%.0s' $(seq 1 900)) $(link 7)"
expect_fail "no issue link" "core/7.md" "does not link its issue" "- **Fixed** a thing."
expect_fail "another issue's link" "core/7.md" "does not link its issue" "- **Fixed** a thing $(link 8)."
expect_fail "a dead relative link" "core/7.md" "links docs/missing.md, which does not exist" \
  "- **Fixed** a thing $(link 7) ([Doc](docs/missing.md#x))."
expect_fail "an empty fragment" "core/7.md" "has no entry" ""
expect_fail "unclosed front matter" "core/7.md" "never closed" $'---\nbreaking: x\n'"$good"
expect_fail "an unknown front-matter key" "core/7.md" "front matter line 2" $'---\ntitle: x\n---\n'"$good"
expect_fail "a breaking line without a migration note" "core/7.md" "does not link its note in docs/reference/migration.md" \
  $'---\nbreaking: it breaks '"$(link 7)"$'.\n---\n'"$good"

echo "changelog-fragments tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
