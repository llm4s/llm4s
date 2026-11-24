#!/usr/bin/env bash
# Tests for scripts/check-docs.sh: every script CI's Quick Checks job runs is in one of its lists, so a new doc check
# in CI cannot be missed locally; every script it lists exists; and, on a fixture tree of fake checks, it runs every
# check after one fails, names each failure and exits non-zero, passes --offline to the cited-issue check, and runs
# the build-model checks only with --model. No network, no sbt.
#
# Usage: scripts/test-check-docs.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/check-docs.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASSED=0
FAILED=0
ok() { PASSED=$((PASSED + 1)); }
ko() { FAILED=$((FAILED + 1)); echo "FAIL: $*"; }

# ---- the real lists against the real workflow
listed="$("$CHECK" --list | awk '{print $2}' | sort -u)"
ci="$(awk '/^  quick-checks:/ { on = 1; next } on && /^  [a-z-]+:$/ { exit } on' "$REPO_ROOT/.github/workflows/ci.yml" |
  grep -v '^[[:space:]]*#' | grep -oE '(^|[^/A-Za-z0-9_.-])scripts/[A-Za-z0-9_.-]+\.sh' | sed 's#.*scripts/##' | sort -u)"
[ -n "$ci" ] && ok || ko "found no script in the quick-checks job of ci.yml"
missing="$(comm -23 <(echo "$ci") <(echo "$listed"))"
[ -z "$missing" ] && ok || ko "Quick Checks runs scripts check-docs.sh does not list: $missing"
for s in $listed; do
  [ -x "$REPO_ROOT/scripts/$s" ] || { ko "check-docs.sh lists scripts/$s, which does not exist or is not executable"; continue; }
done
ok

# ---- behaviour, on a fixture tree whose checks only record that they ran
mkdir -p "$WORK/scripts"
cp "$CHECK" "$WORK/scripts/check-docs.sh"
for s in $listed; do
  printf '#!/usr/bin/env bash\necho "%s $*" >> "%s/ran"\nexit 0\n' "$s" "$WORK" > "$WORK/scripts/$s"
  chmod +x "$WORK/scripts/$s"
done
printf '#!/usr/bin/env bash\necho "check-doc-nav-order.sh" >> "%s/ran"\necho "duplicate nav_order" >&2\nexit 1\n' "$WORK" \
  > "$WORK/scripts/check-doc-nav-order.sh"

: > "$WORK/ran"
out="$("$WORK/scripts/check-docs.sh" 2>&1)" && rc=0 || rc=$?
[ "$rc" -ne 0 ] && ok || ko "a failing check must fail check-docs.sh"
grep -q "FAILED: scripts/check-doc-nav-order.sh" <<< "$out" && ok || ko "the failing check is named: $out"
grep -q "^check-docs-site-links.sh" "$WORK/ran" && ok || ko "the checks after a failure still run"
grep -q "^check-cited-issues.sh --offline" "$WORK/ran" && ok || ko "the cited-issue check runs offline"
grep -q "^check-scala-cli-quickstart.sh" "$WORK/ran" && ko "a network check ran" || ok
grep -q "^check-doc-support.sh" "$WORK/ran" && ko "a build-model check ran without --model" || ok
grep -q "^check-coverage-legs.sh" "$WORK/ran" && ko "a check of something other than the docs ran" || ok

printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/scripts/check-doc-nav-order.sh"
echo '{}' > "$WORK/model.json"
: > "$WORK/ran"
"$WORK/scripts/check-docs.sh" --model "$WORK/model.json" --online > /dev/null 2>&1 && ok || ko "all passing checks pass"
grep -q "^check-doc-support.sh --model $WORK/model.json" "$WORK/ran" && ok || ko "--model runs the build-model checks"
grep -qx "check-cited-issues.sh " "$WORK/ran" && ok || ko "--online also checks the cited issues' states"

"$WORK/scripts/check-docs.sh" --bogus > /dev/null 2>&1 && ko "an unknown argument must fail" || ok

echo "check-docs tests: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
