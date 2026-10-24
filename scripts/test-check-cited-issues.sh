#!/usr/bin/env bash
# Tests for scripts/check-cited-issues.sh, with no network: a fake `gh` answers each issue's state from a fixture,
# and each way a page can fall out of step fails with a message naming it.
#
# Usage: scripts/test-check-cited-issues.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-cited-issues.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0

# A fake gh: `gh api repos/<repo>/issues/<n> --jq .state` prints the state listed for <n> in states.txt.
cat > "$WORK/gh" <<'FAKE'
#!/usr/bin/env bash
n="${2##*/}"
state="$(sed -nE "s/^$n (.*)/\1/p" "$(dirname "$0")/states.txt")"
[ -n "$state" ] || { echo "HTTP 404" >&2; exit 1; }
echo "$state"
FAKE
chmod +x "$WORK/gh"
printf '%s\n' '1 open' '2 closed' '3 open' > "$WORK/states.txt"

page() {
  printf -- '---\ntitle: T\n%s\n---\n\n%s\n' "$1" "$2" > "$WORK/page.md"
}

link() { echo "[#$1](https://github.com/llm4s/llm4s/issues/$1)"; }

run() { GH="$WORK/gh" "$CHECK" "$WORK/page.md" > "$WORK/out" 2>&1; }

expect_pass() {
  if run; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: $1: expected success"; cat "$WORK/out"; fi
}

expect_fail() {
  local name="$1" message="$2"
  if run; then
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected failure"
  elif grep -qF -- "$message" "$WORK/out"; then
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1)); echo "FAIL: $name: expected '$message' in:"; cat "$WORK/out"
  fi
}

page $'cites_open_issues: [1, 3]\ncites_closed_issues: [2]' "Open $(link 1) and $(link 3); done $(link 2)."
expect_pass "every cited issue in its declared state"

page $'cites_open_issues: []\ncites_closed_issues: []' "No issues here."
expect_pass "a page that cites no issue"

page $'cites_open_issues: [1, 2]\ncites_closed_issues: []' "$(link 1) $(link 2)"
expect_fail "an issue cited as open is closed" "cites #2 as open, but it is closed"

page $'cites_open_issues: []\ncites_closed_issues: [1, 2]' "$(link 1) $(link 2)"
expect_fail "an issue cited as closed is open" "cites #1 as closed, but it is open"

page $'cites_open_issues: [1]\ncites_closed_issues: []' "$(link 1) $(link 3)"
expect_fail "a linked issue neither list names" "links #3, which neither"

page $'cites_open_issues: [1, 3]\ncites_closed_issues: []' "$(link 1)"
expect_fail "a listed issue the page does not link" "lists #3 in its front matter but does not link it"

page $'cites_open_issues: [1]\ncites_closed_issues: [1]' "$(link 1)"
expect_fail "an issue in both lists" "lists #1 as both open and closed"

page $'cites_open_issues: [9]\ncites_closed_issues: []' "$(link 9)"
expect_fail "an issue whose state cannot be read" "Could not read the state of #9"

page $'title2: x' "$(link 1) in the body, with no lists in the front matter"
expect_fail "a page with links but no lists" "links #1, which neither"

# The real comparison page is well-formed: every issue it links is listed. (Its states are checked in CI, online.)
awk 'NR == 1 && $0 == "---" { inside = 1; next } inside && $0 == "---" { exit } inside { print }' \
  "$REPO_ROOT/docs/guide/comparison.md" | grep -q '^cites_open_issues:' &&
  PASSED=$((PASSED + 1)) || { FAILED=$((FAILED + 1)); echo "FAIL: docs/guide/comparison.md has no cites_open_issues"; }

echo "check-cited-issues tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
