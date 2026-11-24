#!/usr/bin/env bash
# Fails when a docs page links an llm4s issue without saying how it cites it, or cites an issue as open and the issue is
# closed, or as done and it is open (#2290, #2833).
#
# docs/guide/comparison.md described six closed issues as missing features for weeks, and guide/java.md, spring-boot.md
# and quarkus.md cited four closed issues as open, because nothing noticed when they closed. Every page that links an
# issue of llm4s/llm4s (https://github.com/llm4s/llm4s/issues/N) declares, in its front matter, how it cites each one:
#
#   cites_open_issues: [1442]          # "is an open issue", "tracked in", "not yet": each must still be open
#   cites_closed_issues: [1475, 1493]  # "added in", "fixed by": each must be closed
#   cites_issues: [2341]               # a pointer whose state the text does not depend on ("see #2341"): any state
#   cites_issues: all                  # every link is such a pointer: a release post, a migration guide, an RFC
#
# and this check fails when
#   - a page links an issue that no list names, so a new citation cannot skip the check;
#   - a listed issue is not linked on the page (a stale list), or is in two lists;
#   - a cites_open_issues issue is closed, or a cites_closed_issues issue is open: the page needs refreshing.
#
# The declaration is required rather than inferred because a link alone does not say what the text claims about the
# issue: only the author knows whether "(#1472)" means "tracked in" or "added in".
#
# Pages checked: by default every page the site serves (docs/**/*.md, except under a `_` directory other than _posts
# and the paths `exclude:` in docs/_config.yml names, as scripts/check-doc-seo.sh reads them); or the pages given.
#
# The states come from the GitHub REST API through `gh api` (GH_TOKEN must be set in CI), each issue read once. CI runs
# this weekly and on pull requests that touch docs/ (.github/workflows/docs-cited-issues.yml). `--offline` checks only
# the declarations, with no network: Quick Checks and scripts/check-docs.sh run it so. scripts/test-check-cited-issues.sh
# runs it against a fake `gh`.
#
# Usage: scripts/check-cited-issues.sh [--offline] [page.md ...]
#   GH        the gh executable (default: gh)
#   REPO      the repository the issues belong to (default: llm4s/llm4s)
#   DOCS_ROOT the repository root whose docs/ are checked by default (default: this script's)
set -euo pipefail

REPO_ROOT="${DOCS_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
GH="${GH:-gh}"
REPO="${REPO:-llm4s/llm4s}"
OFFLINE=false
if [ "${1:-}" = "--offline" ]; then
  OFFLINE=true
  shift
fi

pages=()
if [ $# -gt 0 ]; then
  pages=("$@")
else
  while IFS= read -r p; do pages+=("$p"); done < <(
    cd "$REPO_ROOT" && python3 - <<'PYEOF'
import pathlib, re
cfg = pathlib.Path("docs/_config.yml")
excludes = []
if cfg.exists():
    m = re.search(r"^exclude:[ \t]*\n((?:[ \t]*(?:-.*|#.*)?\n)+)", cfg.read_text(encoding="utf-8"), re.M)
    if m:
        excludes = [re.sub(r"\s+#.*$", "", l.strip()[1:]).strip().strip("\"'")
                    for l in m.group(1).splitlines() if l.strip().startswith("-")]
root = pathlib.Path("docs")
for p in sorted(root.rglob("*.md")):
    rel = p.relative_to(root).as_posix()
    parts = rel.split("/")
    if any(x.startswith(("_", ".")) and x != "_posts" for x in parts[:-1]):
        continue
    if any(ex.endswith("/") and (rel + "/").startswith(ex) or rel == ex.rstrip("/") for ex in excludes):
        continue
    print(p.resolve())
PYEOF
  )
  [ "${#pages[@]}" -gt 0 ] || { echo "No docs pages found under $REPO_ROOT/docs" >&2; exit 2; }
fi

# The value of a front-matter key, on one line.
front_matter_value() {
  local key="$1" file="$2"
  awk 'NR == 1 && $0 == "---" { inside = 1; next } inside && $0 == "---" { exit } inside { print }' "$file" |
    sed -nE "s/^${key}:[[:space:]]*(.*)$/\1/p" | head -1
}

# The numbers of a front-matter list `key: [1, 2]`, one per line.
front_matter_list() {
  front_matter_value "$1" "$2" | sed -nE 's/^\[([^]]*)\].*/\1/p' | tr ',' '\n' | tr -d ' ' | grep -E '^[0-9]+$' || true
}

errors=0
fail() { echo "ERROR: $*" >&2; errors=$((errors + 1)); }

STATES="$(mktemp)"
trap 'rm -f "$STATES"' EXIT
# The state of issue $1, read once per run.
state_of() {
  local n="$1" s
  s="$(sed -nE "s/^$n (.*)/\1/p" "$STATES")"
  if [ -z "$s" ]; then
    s="$("$GH" api "repos/$REPO/issues/$n" --jq .state)" || { echo "Could not read the state of #$n" >&2; exit 2; }
    echo "$n $s" >> "$STATES"
  fi
  echo "$s"
}

checked=0
for page in "${pages[@]}"; do
  [ -f "$page" ] || { echo "No such file: $page" >&2; exit 2; }
  name="${page#"$REPO_ROOT"/}"
  linked="$(grep -oE "https://github\.com/${REPO}/issues/[0-9]+" "$page" | sed -E 's#.*/##' | sort -un || true)"
  open_list="$(front_matter_list cites_open_issues "$page" | sort -un)"
  closed_list="$(front_matter_list cites_closed_issues "$page" | sort -un)"
  any_value="$(front_matter_value cites_issues "$page")"
  any_list="$(front_matter_list cites_issues "$page" | sort -un)"
  all=false
  [ "$any_value" = "all" ] && all=true
  [ -n "$linked$open_list$closed_list$any_list" ] || [ "$all" = true ] || continue
  checked=$((checked + 1))

  if [ "$all" = true ]; then
    [ -z "$open_list$closed_list" ] ||
      fail "$name declares cites_issues: all, so it cannot also list issues as open or closed"
    [ -n "$linked" ] || fail "$name declares cites_issues: all but links no issue: remove it"
    continue
  fi
  for n in $linked; do
    if ! grep -qx "$n" <<< "$open_list" && ! grep -qx "$n" <<< "$closed_list" && ! grep -qx "$n" <<< "$any_list"; then
      fail "$name links #$n, which neither cites_open_issues, cites_closed_issues nor cites_issues lists"
    fi
  done
  for n in $open_list $closed_list $any_list; do
    grep -qx "$n" <<< "$linked" || fail "$name lists #$n in its front matter but does not link it"
  done
  for n in $(printf '%s\n' $open_list $closed_list $any_list | sort -n | uniq -d); do
    fail "$name lists #$n in more than one of cites_open_issues, cites_closed_issues and cites_issues"
  done

  [ "$OFFLINE" = false ] || continue
  for n in $open_list; do
    state="$(state_of "$n")"
    [ "$state" = "open" ] || fail "$name cites #$n as open, but it is $state: update the page and move it to cites_closed_issues"
  done
  for n in $closed_list; do
    state="$(state_of "$n")"
    [ "$state" = "closed" ] || fail "$name cites #$n as closed, but it is $state"
  done
done

if [ "$errors" -gt 0 ]; then
  echo "$errors problem(s) with cited issues." >&2
  exit 1
fi
if [ "$OFFLINE" = true ]; then
  echo "Every linked issue is declared ($checked page(s) cite issues; states not checked: --offline)."
else
  echo "Cited issues are in the state their pages say ($checked page(s) cite issues)."
fi
