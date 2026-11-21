#!/usr/bin/env bash
# Checks that every `{% include api.html t="..." %}` in the docs names a page the Scaladoc build generated (#2291), so a
# moved or renamed type fails the build instead of leaving a guide linking to a 404.
#
#   scripts/check-api-links.sh <scaladoc-dir> [docs-dir]
#
# <scaladoc-dir> is the output of `sbt docs/doc` (modules/docs/target/scala-*/api) or the site's copy of it;
# [docs-dir] defaults to docs/. `t` is a fully qualified name, mapped to its page the way docs/_includes/api.html
# maps it: dots to slashes, plus `.html` (an object-only type ends in `$`, as its page does).
set -euo pipefail

API="${1:?usage: check-api-links.sh <scaladoc-dir> [docs-dir]}"
DOCS="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docs}"
[ -f "$API/index.html" ] || { echo "::error::$API is not a generated Scaladoc (no index.html)"; exit 1; }

includes="$(grep -rnoE --include='*.md' --include='*.html' '\{%-?[[:space:]]*include[[:space:]]+api\.html[^%]*%\}' \
  "$DOCS" | grep -v "/_includes/api.html:" || true)"
if [ -z "$includes" ]; then
  echo "::error::no api.html include found under $DOCS: the guides should link their core types to the Scaladoc"
  exit 1
fi

checked=0
broken=0
while IFS= read -r line; do
  where="${line%%:\{*}"
  tag="${line#"$where":}"
  checked=$((checked + 1))
  t="$(printf '%s' "$tag" | sed -nE 's/.*[[:space:]]t="([^"]*)".*/\1/p')"
  if [ -z "$t" ] || ! [[ "$t" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+\$?$ ]]; then
    echo "::error::$where: api.html needs t=\"<fully.qualified.Name>\": $tag"
    broken=$((broken + 1))
    continue
  fi
  page="${t//.//}.html"
  if [ ! -f "$API/$page" ]; then
    hint=""
    [ -f "$API/${t//.//}\$.html" ] && hint=" (only the object has a page: use t=\"$t\$\")"
    echo "::error::$where: no Scaladoc page for $t: $page is missing$hint"
    broken=$((broken + 1))
  fi
done <<< "$includes"

if [ "$broken" -gt 0 ]; then
  echo "$broken of $checked api.html links do not resolve to a Scaladoc page."
  exit 1
fi
echo "All $checked api.html links resolve to a Scaladoc page."
