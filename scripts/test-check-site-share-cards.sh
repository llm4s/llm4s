#!/usr/bin/env bash
# Tests for scripts/check-site-share-cards.sh: a good built site passes; a page without og:image, its alt text or a
# large card fails, so do an image that is not in the site, a twitter:creator that is not a handle, JSON-LD that does not
# parse or has no @type, a home page whose version is not latest_release or that keeps jekyll-seo-tag's WebSite block,
# and a /foundation/ without its Organization. Redirect stubs and pages Jekyll did not render are skipped. No Jekyll
# and no network: each case builds a small fixture site.
#
# Usage: scripts/test-check-site-share-cards.sh [CHECK_SCRIPT]
#   CHECK_SCRIPT  the check to test (default: scripts/check-site-share-cards.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${1:-$REPO_ROOT/scripts/check-site-share-cards.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
CASE=0
OUT=""
RC=0
IMG="https://llm4s.org/image_assets/social/default.png"
HOME_LD='{"@context":"https://schema.org","@type":"SoftwareSourceCode","name":"LLM4S","version":"1.2.3"}'
ORG_LD='{"@context":"https://schema.org","@type":"Organization","name":"LLM4S Foundation"}'
CRUMB_LD='{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[{"@type":"ListItem","position":1,"name":"Home","item":"https://llm4s.org/"}]}'

# page PATH [JSON_LD] [EXTRA_HEAD]: a built page with a full share card; JSON_LD defaults to a WebPage block.
page() {
  local f="$C/site/$1" ld="${2:-}"
  [ -n "$ld" ] || ld='{"@context":"https://schema.org","@type":"WebPage"}'
  mkdir -p "$(dirname "$f")"
  cat > "$f" <<EOF
<!DOCTYPE html><html><head>
<meta name="generator" content="Jekyll v3.10.0" />
<meta property="og:image" content="$IMG" />
<meta property="og:image:alt" content="LLM4S: LLM framework for Scala and the JVM. llm4s.org" />
<meta name="twitter:card" content="summary_large_image" />
<meta property="twitter:image" content="$IMG" />
<meta name="twitter:creator" content="@llm4sfoundation" />
<script type="application/ld+json">$ld</script>
${3:-}
</head><body><p>body</p></body></html>
EOF
}

# A fixture site that passes: home, /foundation/, one guide page, the image, and project.yml.
new_case() {
  CASE=$((CASE + 1))
  C="$WORK/case$CASE"
  mkdir -p "$C/site/image_assets/social"
  printf 'png' > "$C/site/image_assets/social/default.png"
  printf 'version: "1.2.4-SNAPSHOT"\nlatest_release: "1.2.3"\n' > "$C/project.yml"
  page index.html "$HOME_LD"
  page foundation/index.html "$ORG_LD"
  page guide/java.html '{"@context":"https://schema.org","@type":"WebPage"}' "<script type=\"application/ld+json\">$CRUMB_LD</script>"
}

run() {
  OUT=$("$CHECK" "$C/site" "$C/project.yml" 2>&1) && RC=0 || RC=$?
}

fail() { echo "FAIL case $CASE ($1)"; echo "$OUT" | sed 's/^/    /'; FAILURES=$((FAILURES + 1)); }
expect_pass() { run; [ "$RC" -eq 0 ] || fail "$1: expected a pass, got exit $RC"; }
expect_fail() { # NAME PATTERN
  run
  if [ "$RC" -eq 0 ]; then fail "$1: expected a failure, got a pass"
  elif ! echo "$OUT" | grep -qE "$2"; then fail "$1: output does not match /$2/"; fi
}
drop() { sed -i.bak "/$2/d" "$C/site/$1"; }   # drop PAGE PATTERN: delete the head lines matching PATTERN

new_case; expect_pass "a good site"

new_case; drop guide/java.html 'og:image"'
expect_fail "no og:image" "guide/java.html: has no og:image"

new_case; drop guide/java.html 'og:image:alt'
expect_fail "no og:image:alt" "guide/java.html: has no og:image:alt"

new_case; drop guide/java.html 'twitter:image'
expect_fail "no twitter:image" "guide/java.html: twitter:image is None"

new_case; sed -i.bak 's/summary_large_image/summary/' "$C/site/guide/java.html"
expect_fail "a small card" "twitter:card is 'summary'"

new_case; rm "$C/site/image_assets/social/default.png"
expect_fail "an image the site does not have" "is not a file of the site"

new_case; sed -i.bak 's#https://llm4s.org/image_assets#/image_assets#g' "$C/site/guide/java.html"
expect_fail "a relative og:image" "og:image is not an absolute https URL"

new_case; sed -i.bak 's/@llm4sfoundation/@LLM4S Team/' "$C/site/guide/java.html"
expect_fail "an invalid twitter:creator" "twitter:creator '@LLM4S Team' is not a handle"

new_case; page guide/bad.html '{"@type": "WebPage",}'
expect_fail "JSON-LD that does not parse" "guide/bad.html: JSON-LD is not valid JSON"

new_case; page guide/untyped.html '{"@context":"https://schema.org","name":"x"}'
expect_fail "JSON-LD without @type" "guide/untyped.html: a JSON-LD object has no @type"

new_case; page guide/graph.html '{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"name":"x"}]}'
expect_fail "an untyped @graph item" "guide/graph.html: a JSON-LD object has no @type"

new_case; page index.html "${HOME_LD/1.2.3/1.2.2}"
expect_fail "home version is not latest_release" "SoftwareSourceCode.version is '1.2.2', latest_release is '1.2.3'"

new_case; page index.html '{"@context":"https://schema.org","@type":"WebSite"}'
expect_fail "home without SoftwareSourceCode" "index.html: has 0 SoftwareSourceCode"

new_case; page index.html "$HOME_LD" '<script type="application/ld+json">{"@context":"https://schema.org","@type":"WebSite"}</script>'
expect_fail "home with a second description" "index.html: also describes itself as WebSite"

new_case; page foundation/index.html '{"@context":"https://schema.org","@type":"WebPage"}'
expect_fail "foundation without its Organization" "foundation/index.html: has no Organization"

new_case; rm "$C/site/foundation/index.html"
expect_fail "foundation page missing" "foundation/index.html: missing from the site"

new_case; printf '<html><head><meta name="generator" content="Jekyll v3.10.0" /><meta http-equiv="refresh" content="0; url=/guide/java" /></head></html>\n' > "$C/site/guide/old.html"
expect_pass "a redirect stub is not checked"

new_case; mkdir -p "$C/site/scaladoc"; printf '<html><head><title>API</title></head></html>\n' > "$C/site/scaladoc/index.html"
expect_pass "a page Jekyll did not render (Scaladoc) is not checked"

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES of $CASE case(s) failed" >&2
  exit 1
fi
echo "ok: $CASE check-site-share-cards cases passed"
