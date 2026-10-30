#!/usr/bin/env bash
# Tests for scripts/generate-scaladoc-sitemap.sh and scripts/check-site-sitemaps.sh (#2318), on a fake built site.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$REPO_ROOT/scripts/generate-scaladoc-sitemap.sh"
CHK="$REPO_ROOT/scripts/check-site-sitemaps.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0
expect() { # name expected-exit actual-exit
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (exit $3, wanted $2)"; fails=$((fails + 1)); fi
}
NS='xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"'
site() { # dir: a good site
  mkdir -p "$1/scaladoc/org/llm4s/agent"
  : > "$1/scaladoc/index.html"; : > "$1/scaladoc/org/llm4s/agent/Agent.html"; : > "$1/scaladoc/org/llm4s/agent/Agent.js"
  printf '<urlset %s><url><loc>https://llm4s.org/</loc></url><url><loc>https://llm4s.org/guide/x.html</loc></url></urlset>' "$NS" > "$1/sitemap.xml"
  printf 'Sitemap: https://llm4s.org/sitemap.xml\nSitemap: https://llm4s.org/scaladoc-sitemap.xml\n' > "$1/robots.txt"
}

site "$WORK/a"; "$GEN" "$WORK/a" >/dev/null; expect "generate" 0 $?
grep -q 'scaladoc/org/llm4s/agent/Agent.html' "$WORK/a/scaladoc-sitemap.xml" && ! grep -q '\.js' "$WORK/a/scaladoc-sitemap.xml"; expect "only html pages listed" 0 $?
"$CHK" "$WORK/a" >/dev/null; expect "good site passes" 0 $?

site "$WORK/b"; "$GEN" "$WORK/b" >/dev/null
sed -i.bak 's#</urlset>#<url><loc>https://llm4s.org/scaladoc/org/X.html</loc></url></urlset>#' "$WORK/b/sitemap.xml"
"$CHK" "$WORK/b" >/dev/null; expect "scaladoc URL in sitemap.xml fails" 1 $?

site "$WORK/c"; "$GEN" "$WORK/c" >/dev/null
sed -i.bak 's#</urlset>#<url><loc>https://llm4s.org/image_assets/a.pdf</loc></url></urlset>#' "$WORK/c/sitemap.xml"
"$CHK" "$WORK/c" >/dev/null; expect "pdf in sitemap.xml fails" 1 $?

site "$WORK/d"; "$GEN" "$WORK/d" >/dev/null
MAX_URLS=1 "$CHK" "$WORK/d" >/dev/null; expect "too many URLs fails" 1 $?

site "$WORK/e"
"$CHK" "$WORK/e" >/dev/null; expect "missing scaladoc-sitemap.xml fails" 1 $?

site "$WORK/f"; "$GEN" "$WORK/f" >/dev/null; printf 'Sitemap: https://llm4s.org/sitemap.xml\n' > "$WORK/f/robots.txt"
"$CHK" "$WORK/f" >/dev/null; expect "robots.txt missing a sitemap fails" 1 $?

mkdir -p "$WORK/g"; "$GEN" "$WORK/g" >/dev/null 2>&1; expect "generate without scaladoc fails" 1 $?
[ "$fails" = 0 ] || { echo "$fails failed"; exit 1; }
