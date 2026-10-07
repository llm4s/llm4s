#!/usr/bin/env bash
# Tests for scripts/check-docs-site-links.sh: every kind of broken link fails with a message naming it, every
# link the site serves passes, and the baseline can neither grow nor keep a link that no longer breaks. No sbt
# and no network: each case builds a small fixture docs tree and runs the check against it.
#
# Usage: scripts/test-check-docs-site-links.sh [CHECK_SCRIPT]
#   CHECK_SCRIPT  the check to test (default: scripts/check-docs-site-links.sh). Pointing it at a deliberately
#                 broken copy shows that this test notices: see the mutation list in the pull request.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${1:-$REPO_ROOT/scripts/check-docs-site-links.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
CASE=0
OUT=""
RC=0

# ---- the fixture: a docs/ tree shaped like the real one, one page (guide/t.md) varied per case
new_case() {
  CASE=$((CASE + 1))
  C="$WORK/case$CASE"
  mkdir -p "$C/docs/guide/sub" "$C/docs/img"
  printf 'exclude:\n  - Gemfile\n' > "$C/docs/_config.yml"
  printf -- '---\npermalink: /\n---\n# Home\n' > "$C/docs/index.md"
  printf -- '---\ntitle: Guide\n---\n# Guide\n' > "$C/docs/guide/index.md"
  printf -- '---\ntitle: A\n---\n# A\n\n## Intro\n\n## Setup Steps (v2)\n\n## Intro\n\n## Uses `Config` here\n' > "$C/docs/guide/a.md"
  printf -- '---\ntitle: B\n---\n# B\n' > "$C/docs/guide/b.md"
  printf -- '---\ntitle: C\n---\n# C\n' > "$C/docs/guide/sub/c.md"
  printf '# Raw page without front matter\n\n## Raw heading\n' > "$C/docs/raw.md"
  printf 'png' > "$C/docs/img/x.png"
  printf '# Repo file outside docs\n' > "$C/README.md"
  printf '# Docs readme, served raw\n' > "$C/docs/README.md"
  : > "$C/baseline.txt"
}

# page NAME BODY: write a rendered page docs/guide/NAME.md
page() {
  printf -- '---\ntitle: T\n---\n%s\n' "$2" > "$C/docs/guide/$1.md"
}

run_check() {
  RC=0
  OUT="$("$CHECK" --root "$C/docs" --baseline "$C/baseline.txt" "$@" 2>&1)" || RC=$?
}

pass() { echo "ok   [$1]"; }
fail() {
  echo "FAIL [$1]: $2"
  echo "$OUT" | sed 's/^/       /'
  FAILURES=$((FAILURES + 1))
}

expect_pass() {
  run_check
  if [ "$RC" -eq 0 ]; then pass "$1"; else fail "$1" "expected the check to pass (exit $RC)"; fi
}

# expect_fail NAME PATTERN: the check must exit 1 and print PATTERN (a fixed string)
expect_fail() {
  run_check
  if [ "$RC" -ne 1 ]; then fail "$1" "expected exit 1, got $RC"; return; fi
  if printf '%s' "$OUT" | grep -qF -- "$2"; then pass "$1"; else fail "$1" "output lacks: $2"; fi
}

# ---- links the site serves
new_case; page t '[a](a) [b](b.html) [c](sub/c) [up](../guide/b) [self](./) [home](../)'
expect_pass "relative links resolve against the page URL, with and without .html, ./ and ../"

new_case; page t '[a](/guide/a) [g](/guide/) [h](/) [x](/guide/a.html) [dir](/guide)'
expect_pass "site-absolute links, the directory index and the root permalink resolve"

new_case; page t '[i](a#intro) [i2](a#intro-1) [s](a#setup-steps-v2) [c](a#uses-config-here) [m](#my-section)
## My section'
expect_pass "anchors: headings, the repeat suffix, punctuation, inline code, and the page's own headings"

new_case; page t '[img](/img/x.png) [ext](https://example.org/x) [m](mailto:a@b.c) [t](tel:1) [l](/guide/{{ page.x }}) [s](/scaladoc/) [sd](/scaladoc/org/llm4s/index.html)'
expect_pass "assets pass; external, mailto, Liquid and the generated /scaladoc/ are skipped"

new_case; page t '```
[fenced](missing-in-fence)
```
`[inline](missing-inline)` and text'
expect_pass "links inside fenced code and inline code are ignored"

new_case; printf -- '---\npermalink: /pp/\n---\n# P\n[sib](x)\n' > "$C/docs/p.md"
page t '[ok](/pp/) [no](/p)'
expect_fail "a permalink replaces the page URL" "NEW BROKEN LINK docs/guide/t.md:4 -> /p (page-missing)"

new_case; mkdir -p "$C/docs/other"; printf -- '---\npermalink: /guide/y/\n---\n# Y\n[up](../b) [sib](./)\n' > "$C/docs/other/y.md"
expect_pass "a relative link on a permalink page resolves against the permalink URL, not the source path"

new_case; printf -- '---\npermalink: /pp/\n---\n# P\n[sib](b)\n' > "$C/docs/p.md"
expect_fail "a permalink page's sibling link is read under the permalink (/pp/b)" "NEW BROKEN LINK docs/p.md:5 -> b (page-missing)"

# ---- broken links, each with its cause
new_case; page t 'line one
[gone](nothing-here)'
expect_fail "a new broken link fails and names the file, the line and the target" "NEW BROKEN LINK docs/guide/t.md:5 -> nothing-here (page-missing)"

new_case; page t '[x](a#nope)'
expect_fail "a stale anchor fails" "(stale-anchor)"

new_case; page t '[x](#nope)'
expect_fail "a stale anchor on the page itself fails" "docs/guide/t.md:4 -> #nope (stale-anchor)"

new_case; page t '[r](../raw) [r2](../raw.md)'
expect_fail "a link to a page without front matter fails (it is served raw)" "(no-front-matter)"

new_case; page t '[x](a.md)'
expect_fail "a .md link to a rendered page fails (the site 404s it)" "-> a.md (md-extension)"

new_case; page t '[x](/guide/a/)'
expect_fail "a trailing slash on a page that is not an index is a 404" "-> /guide/a/ (page-missing)"

new_case; page t '[x](/guide/sub/)'
expect_fail "a directory without an index page fails" "(page-missing)"

new_case; page t '[x](../../README.md)'
expect_fail "a link that leaves docs/ for a repository file fails with its own cause, even when the clamped URL names a site page" "-> ../../README.md (repo-source)"

new_case; printf -- '---\ntitle: T\n---\n<a href="gone.html">x</a> <img src="/img/missing.png">\n[ref]: nothing-ref\n[use][ref]\n' > "$C/docs/guide/t.md"
expect_fail "HTML href, src and reference-style links are checked" "gone.html"

new_case; mkdir -p "$C/docs/other"; printf '# Repo file\n' > "$C/CLAUDE.md"
printf -- '---\npermalink: /guide/y/\n---\n# Y\n[src](../../CLAUDE.md)\n' > "$C/docs/other/y.md"
expect_fail "a source-relative path to a repository file on a permalinked page is a repo-source link" "docs/other/y.md:5 -> ../../CLAUDE.md (repo-source)"

# ---- links read on GitHub: pages without front matter
new_case; printf '[a](guide/a.md) [dir](guide/) [repo](../README.md) [anchor](guide/a.md#intro) [self](#raw-heading) [abs](/guide/a)\n\n## Raw heading\n' > "$C/docs/raw.md"
expect_pass "inside a page without front matter, links resolve as on GitHub (files, repository files, anchors)"

new_case; printf '[x](guide/missing.md) [y](guide/a.md#nope)\n' > "$C/docs/raw.md"
run_check
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -qF "docs/raw.md:1 -> guide/missing.md (page-missing)" \
   && printf '%s' "$OUT" | grep -qF "guide/a.md#nope (stale-anchor)"; then
  pass "inside a page without front matter, a missing file and a stale anchor still fail"
else fail "raw page broken links" "expected both to be reported"; fi

# ---- the baseline
new_case; page t '[gone](nothing-here)'; printf 'docs/guide/t.md -> nothing-here\n' > "$C/baseline.txt"
expect_pass "a broken link that is in the baseline passes"

new_case; page t '[ok](a)'; printf 'docs/guide/t.md -> nothing-here\n' > "$C/baseline.txt"
expect_fail "a baseline entry that no longer breaks fails: remove it" "STALE BASELINE ENTRY, remove this baseline entry: docs/guide/t.md -> nothing-here"

new_case; page t '[gone](nothing-here) [new](another-gone)'; printf 'docs/guide/t.md -> nothing-here\n' > "$C/baseline.txt"
expect_fail "the baseline cannot grow: a second broken link fails" "-> another-gone (page-missing)"

new_case; page t '[gone](missing2)'; printf 'docs/guide/t.md -> missing\n' > "$C/baseline.txt"
run_check
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -qF "NEW BROKEN LINK docs/guide/t.md:4 -> missing2" \
   && printf '%s' "$OUT" | grep -qF "STALE BASELINE ENTRY, remove this baseline entry: docs/guide/t.md -> missing"; then
  pass "baseline keys match exactly, not as substrings"
else fail "exact keys" "a baseline key that is a prefix of a new target must not hide it"; fi

new_case; page t '[gone](nothing-here)'; printf 'docs/guide/t.md -> nothing-here\n' > "$C/baseline.txt"
page t 'one

two

three

[gone](nothing-here)'
expect_pass "baseline keys do not contain line numbers: moving a link does not change them"

new_case; page t '[gone](nothing-here) [gone](nothing-here)'
run_check --print
if [ "$RC" -eq 0 ] && [ "$(printf '%s\n' "$OUT" | grep -c 'nothing-here')" -eq 1 ] \
   && printf '%s' "$OUT" | grep -qF "$(printf 'docs/guide/t.md -> nothing-here\tpage-missing')"; then
  pass "--print lists each broken link once, with its cause"
else fail "--print" "expected one 'key<TAB>cause' line"; fi

new_case; page t '[gone](nothing-here)'
run_check --update-baseline
if [ "$RC" -eq 0 ] && grep -qxF 'docs/guide/t.md -> nothing-here' "$C/baseline.txt"; then
  run_check
  if [ "$RC" -eq 0 ]; then pass "--update-baseline writes the baseline, and the check then passes"
  else fail "--update-baseline" "the check fails after updating"; fi
else fail "--update-baseline" "baseline lacks the entry"; fi

new_case; page t '[x](a)'
run_check --root "$WORK/does-not-exist"
if [ "$RC" -eq 2 ]; then pass "a missing docs directory is a usage error (exit 2)"; else fail "usage error" "expected exit 2, got $RC"; fi
run_check --frobnicate
if [ "$RC" -eq 2 ]; then pass "an unknown flag is a usage error (exit 2)"; else fail "unknown flag" "expected exit 2, got $RC"; fi

# ---- files the site does not serve
new_case; printf -- '---\ntitle: S\n---\n# S\n' > "$C/docs/_hidden.md"; page t '[h](_hidden) [g](/Gemfile)'
expect_fail "files under an underscore name and the _config.yml exclude list are not served" "(page-missing)"

echo
if [ "$FAILURES" -eq 0 ]; then echo "All $CASE docs-link cases passed"; else echo "$FAILURES failure(s)"; exit 1; fi
