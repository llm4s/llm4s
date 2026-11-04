#!/usr/bin/env bash
# Tests for scripts/check-doc-seo.sh: a missing, a too-short, a too-long, a duplicated and a site-wide description
# fail, so do a bad search_title and (in the built-site mode) a <title> that is only `Home`; a good tree passes; the
# baseline exempts a page and fails when it lists one that passes. No Jekyll and no network: each case builds a small
# fixture tree.
#
# Usage: scripts/test-check-doc-seo.sh [CHECK_SCRIPT]
#   CHECK_SCRIPT  the check to test (default: scripts/check-doc-seo.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${1:-$REPO_ROOT/scripts/check-doc-seo.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
CASE=0
OUT=""
RC=0
D1="Call an LLM from Java with llm4s-java-api: add the dependency and send a prompt."
D2="Stream a model reply token by token in Scala and read the whole completion at the end."

# A fixture root with two good pages and the site-wide description in _config.yml.
new_case() {
  CASE=$((CASE + 1))
  C="$WORK/case$CASE"
  mkdir -p "$C/docs/guide" "$C/docs/reference" "$C/scripts"
  printf 'title: LLM4S\ndescription: >-\n  LLM4S - the site wide description,\n  shared by every page that sets none.\n' > "$C/docs/_config.yml"
  page guide/java.md "Java" "$D1"
  page guide/streaming.md "Streaming" "$D2"
  : > "$C/scripts/doc-seo-baseline.txt"
}

# page PATH TITLE DESCRIPTION [EXTRA_FRONT_MATTER_LINE]; an empty DESCRIPTION writes none.
page() {
  local f="$C/docs/$1"
  mkdir -p "$(dirname "$f")"
  { echo "---"; echo "layout: page"; [ -n "$2" ] && echo "title: $2"
    [ -n "$3" ] && echo "description: \"$3\""; [ -n "${4:-}" ] && echo "$4"
    echo "---"; echo; echo "body"; } > "$f"
}

# html PATH TITLE OG DESCRIPTION: a built page under $C/site.
html() {
  local f="$C/site/$1"
  mkdir -p "$(dirname "$f")"
  printf '<!DOCTYPE html><html><head><title>%s</title>\n<meta property="og:title" content="%s" />\n<meta name="description" content="%s" />\n</head><body><svg><title>Link</title></svg></body></html>\n' "$2" "$3" "$4" > "$f"
}

run() {
  OUT=$(DOC_SEO_ROOT="$C" "$CHECK" "$@" 2>&1) && RC=0 || RC=$?
}

fail() { echo "FAIL case $CASE ($1)"; echo "$OUT" | sed 's/^/    /'; FAILURES=$((FAILURES + 1)); }
expect_pass() { run "${@:2}"; [ "$RC" -eq 0 ] || fail "$1: expected a pass, got exit $RC"; }
expect_fail() { # NAME PATTERN [args]
  run "${@:3}"
  if [ "$RC" -eq 0 ]; then fail "$1: expected a failure, got a pass"
  elif ! echo "$OUT" | grep -qE "$2"; then fail "$1: output does not match /$2/"; fi
}

# ---- source mode
new_case; expect_pass "good tree"

new_case; page guide/none.md "None" ""
expect_fail "missing description" "guide/none.md: has no description"

new_case; page guide/short.md "Short" "Too short."
expect_fail "short description" "guide/short.md: description is 10 characters"

new_case; page guide/long.md "Long" "$(printf 'word %.0s' $(seq 1 40))"
expect_fail "too-long description" "guide/long.md: description is 199 characters"

new_case; page guide/dup.md "Dup" "$D1"
expect_fail "duplicated description" "guide/dup.md: description is shared with .*guide/java.md"

new_case; page guide/dup.md "Dup" "$(echo "$D1" | tr 'a-z' 'A-Z')"
expect_fail "duplicate ignoring case" "description is shared with"

new_case; page guide/site.md "Site" "LLM4S - the site wide description, shared by every page that sets none."
expect_fail "site-wide description" "guide/site.md: description is the site-wide description"

new_case; page guide/liquid.md "Liquid" "Install LLM4S {{ site.data.project.latest_release }} with sbt and add the first provider module."
expect_fail "Liquid in a description" "description contains Liquid"

new_case; page guide/notitle.md "" "Explain how a page without a title is found by the check, in one sentence."
expect_fail "no title" "guide/notitle.md: has no title"

new_case; page guide/st.md "St" "Explain a page whose search title is far too long to show in a search result." 'search_title: "A search title that goes on and on well past what any search engine will show"'
expect_fail "long search_title" "guide/st.md: search_title is [0-9]+ characters"

new_case; page guide/st1.md "St1" "Explain a page that has a search title, which is allowed and checked." 'search_title: "Same search title"'
page guide/st2.md "St2" "Explain another page that reuses the search title of the other page." 'search_title: "Same search title"'
expect_fail "duplicated search_title" "search title is shared with"

new_case; page guide/folded.md "Folded" "" 'description: >-
  A folded description that runs over two lines and is still read
  as one description by the check.'
expect_pass "folded block description"

new_case; mkdir -p "$C/docs/design"; page design/out.md "Out" ""
expect_pass "pages outside the checked sections are ignored"

new_case; printf 'no front matter, so Jekyll copies it and it is not a page\n' > "$C/docs/guide/raw.md"
expect_pass "a file without front matter is not a page"

# ---- baseline
new_case; page guide/none.md "None" ""; echo "src:docs/guide/none.md" > "$C/scripts/doc-seo-baseline.txt"
expect_pass "baseline exempts a failing page"

new_case; echo "src:docs/guide/java.md" > "$C/scripts/doc-seo-baseline.txt"
expect_fail "stale baseline entry" "listed in scripts/doc-seo-baseline.txt but passes"

new_case; echo "src:docs/guide/gone.md" > "$C/scripts/doc-seo-baseline.txt"
expect_fail "baseline entry for a missing page" "but does not exist"

new_case; echo "site:guide/java.html" > "$C/scripts/doc-seo-baseline.txt"
expect_pass "a site: entry is not judged in the source mode"

# ---- built-site mode
good_site() {
  html index.html "Scala LLM framework for the JVM | LLM4S" "Scala LLM framework for the JVM" "Scala LLM4S home: type-safe agents, tools and RAG for the JVM with many providers."
  html guide/java.html "Call LLMs from Java with LLM4S" "Call LLMs from Java with LLM4S" "$D1"
  html guide/streaming.html "Streaming | LLM4S" "Streaming" "$D2"
}

new_case; good_site; expect_pass "good built site" --site "$C/site"

new_case; good_site; html guide/home.html "Home | LLM4S" "Home" "Explain a page whose rendered title is only the nav label, which the check rejects."
expect_fail "title is only 'Home'" "guide/home.html: <title> is only the nav label 'Home'" --site "$C/site"
expect_fail "og:title is only 'Home'" "guide/home.html: og:title is only the nav label 'Home'" --site "$C/site"

new_case; good_site; html guide/overview.html "Overview | LLM4S" "A distinct og title" "Explain a page titled only Overview, which is one of the denied labels here."
expect_fail "title is only 'Overview'" "<title> is only the nav label 'Overview'" --site "$C/site"

new_case; good_site; html guide/nodesc.html "No description | LLM4S" "No description" ""
sed -i.bak '/name="description"/d' "$C/site/guide/nodesc.html"
expect_fail "built page without a description" "guide/nodesc.html: has no description" --site "$C/site"

new_case; good_site; html guide/sitewide.html "Sitewide | LLM4S" "Sitewide" "LLM4S - the site wide description, shared by every page that sets none."
expect_fail "built page with the site-wide description" "description is the site-wide description" --site "$C/site"

new_case; good_site; html guide/dup.html "Dup | LLM4S" "Dup" "$D1"
expect_fail "built duplicate description" "guide/dup.html: description is shared with" --site "$C/site"

new_case; good_site; html guide/dupt.html "Streaming | LLM4S" "Other" "Explain a page whose rendered title equals the title of another built page."
expect_fail "built duplicate title" "<title> is shared with" --site "$C/site"

new_case; good_site; html guide/longt.html "$(printf 'long title %.0s' $(seq 1 8)) | LLM4S" "Long" "Explain a page whose rendered title is much too long for a search result to show."
expect_fail "built title too long" "<title> is [0-9]+ characters, at most 70" --site "$C/site"

new_case; good_site; html guide/shortd.html "Shortd | LLM4S" "Shortd" "Too short."
expect_fail "built short description" "description is 10 characters" --site "$C/site"

new_case; good_site; printf '<html><head><meta http-equiv="refresh" content="0; url=/guide/java" /></head></html>\n' > "$C/site/guide/old.html"
expect_pass "a redirect stub is not a page" --site "$C/site"

new_case; good_site; html scaladoc/x.html "Home | LLM4S" "Home" "Scaladoc pages are outside the checked sections, so this one is never read at all."
expect_pass "pages outside the checked sections are ignored in the built site" --site "$C/site"

new_case; good_site; html guide/none.html "None | LLM4S" "None" "Explain a page that fails in the built site and is listed in the site baseline file."
sed -i.bak 's/content="Explain a page that fails.*" \/>/content="x" \/>/' "$C/site/guide/none.html"; echo "site:guide/none.html" > "$C/scripts/doc-seo-baseline.txt"
expect_pass "site baseline exempts a failing page" --site "$C/site"

new_case; good_site; echo "site:guide/java.html" > "$C/scripts/doc-seo-baseline.txt"
expect_fail "stale site baseline entry" "listed in scripts/doc-seo-baseline.txt but passes" --site "$C/site"

new_case; expect_fail "unknown argument" "usage:" --bogus

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES of $CASE case(s) failed" >&2
  exit 1
fi
echo "ok: $CASE check-doc-seo cases passed"
