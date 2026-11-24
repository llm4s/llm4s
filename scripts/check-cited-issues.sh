#!/usr/bin/env bash
# Fails when a docs page cites an llm4s issue as open and the issue is closed, or as done and it is open (#2290).
#
# docs/guide/comparison.md described six closed issues as missing features for weeks, because nothing noticed when
# they closed. A page that cites issues now declares, in its front matter, how it cites each one:
#
#   cites_open_issues: [1442]          # "is an open issue", "tracks the rest": each must still be open
#   cites_closed_issues: [1475, 1493]  # history, "added in": each must be closed
#
# and this check fails when
#   - a page links an issue of llm4s/llm4s (https://github.com/llm4s/llm4s/issues/N) that neither list names, so a new
#     citation cannot skip the check;
#   - a listed issue is not linked on the page (a stale list);
#   - a cites_open_issues issue is closed, or a cites_closed_issues issue is open: the page needs refreshing.
#
# The pages checked by default are found, not listed: every page a reader is sent to (docs/*.md and every page under
# docs/{getting-started,guide,examples,reference,migrations,scala-ai,advanced,api,community,tutorials}) that links an
# llm4s issue, plus any other page under docs/ that declares either list (#2833). A page in that set that links an
# issue without declaring it fails, so a new citation, or a new page with one, cannot skip the check. Design records,
# RFCs, plans, GSoC pages and news posts are history, and are checked only when they declare a list.
#
# The state comes from the GitHub REST API through `gh api` (GH_TOKEN must be set in CI), read once per issue. CI runs
# this weekly and on pull requests that touch docs/ (.github/workflows/docs-cited-issues.yml); `scripts/check-docs.sh
# --cited-issues` runs it locally. scripts/test-check-cited-issues.sh runs it against a fake `gh`, with no network.
#
# Usage: scripts/check-cited-issues.sh [page.md ...]
#   (default: the pages found as above)
#   GH          the gh executable (default: gh)
#   REPO        the repository the issues belong to (default: llm4s/llm4s)
#   DOCS_ROOT   the docs directory searched by default (default: docs/ under this repository)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH="${GH:-gh}"
REPO="${REPO:-llm4s/llm4s}"
DOCS_ROOT="${DOCS_ROOT:-$REPO_ROOT/docs}"
ISSUE_LINK="https://github\.com/${REPO}/issues/[0-9]+"
if [ $# -eq 0 ]; then
  pages=()
  while IFS= read -r f; do pages+=("$f"); done < <(
    {
      find "$DOCS_ROOT" -maxdepth 1 -name '*.md'
      for d in getting-started guide examples reference migrations scala-ai advanced api community tutorials; do
        [ -d "$DOCS_ROOT/$d" ] && find "$DOCS_ROOT/$d" -name '*.md'
      done
    } | xargs grep -lE "$ISSUE_LINK" 2>/dev/null || true
    find "$DOCS_ROOT" -name '*.md' -not -path '*/_site/*' -not -path '*/vendor/*' |
      xargs grep -lE '^cites_(open|closed)_issues:' 2>/dev/null || true
  )
  [ "${#pages[@]}" -gt 0 ] || { echo "No page under $DOCS_ROOT cites an issue: wrong directory?" >&2; exit 2; }
  set -- $(printf '%s\n' "${pages[@]}" | sort -u)
fi

# The numbers of a front-matter list `key: [1, 2]`, one per line.
front_matter_list() {
  local key="$1" file="$2"
  awk 'NR == 1 && $0 == "---" { inside = 1; next } inside && $0 == "---" { exit } inside { print }' "$file" |
    sed -nE "s/^${key}:[[:space:]]*\[([^]]*)\].*/\1/p" | tr ',' '\n' | tr -d ' ' | grep -E '^[0-9]+$' || true
}

# One API call per issue, however many pages cite it.
STATE_CACHE="$(mktemp -d)"
trap 'rm -rf "$STATE_CACHE"' EXIT
issue_state() {
  local n="$1"
  if [ ! -f "$STATE_CACHE/$n" ]; then
    "$GH" api "repos/$REPO/issues/$n" --jq .state > "$STATE_CACHE/$n" || { rm -f "$STATE_CACHE/$n"; return 1; }
  fi
  cat "$STATE_CACHE/$n"
}

errors=0
fail() { echo "ERROR: $*" >&2; errors=$((errors + 1)); }

for page in "$@"; do
  [ -f "$page" ] || { echo "No such file: $page" >&2; exit 2; }
  open_list="$(front_matter_list cites_open_issues "$page" | sort -un)"
  closed_list="$(front_matter_list cites_closed_issues "$page" | sort -un)"
  linked="$(grep -oE "$ISSUE_LINK" "$page" | sed -E 's#.*/##' | sort -un || true)"

  for n in $linked; do
    if ! grep -qx "$n" <<< "$open_list" && ! grep -qx "$n" <<< "$closed_list"; then
      fail "$page links #$n, which neither cites_open_issues nor cites_closed_issues lists"
    fi
  done
  for n in $open_list $closed_list; do
    grep -qx "$n" <<< "$linked" || fail "$page lists #$n in its front matter but does not link it"
  done
  for n in $open_list $closed_list; do
    if grep -qx "$n" <<< "$open_list" && grep -qx "$n" <<< "$closed_list"; then
      fail "$page lists #$n as both open and closed"
    fi
  done

  for n in $open_list; do
    state="$(issue_state "$n")" || { echo "Could not read the state of #$n" >&2; exit 2; }
    [ "$state" = "open" ] || fail "$page cites #$n as open, but it is $state: update the page and move it to cites_closed_issues"
  done
  for n in $closed_list; do
    state="$(issue_state "$n")" || { echo "Could not read the state of #$n" >&2; exit 2; }
    [ "$state" = "closed" ] || fail "$page cites #$n as closed, but it is $state"
  done
done

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s) with cited issues." >&2
  exit 1
fi
echo "Cited issues are in the state their pages say ($# pages)."
