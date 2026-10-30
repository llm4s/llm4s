#!/usr/bin/env bash
# Writes scaladoc-sitemap.xml for the built site: one <url> per Scaladoc package, class, trait, object and enum page
# under <site>/scaladoc/ (#2318). sitemap.xml (jekyll-sitemap) lists the guide pages only, so a crawler is not told
# that 95% of the site is generated API docs. Member anchors (#foo) are fragments, not files, so they never appear.
# No <lastmod>: every Scaladoc file changes on each deploy, so a date would say nothing about the content.
#
# Usage: scripts/generate-scaladoc-sitemap.sh [SITE_DIR] [SITE_URL]     (defaults: docs/_site, https://llm4s.org)
# Run after `jekyll build`; the output is SITE_DIR/scaladoc-sitemap.xml. Exits 1 when there is no Scaladoc, and 1 when
# there are more than 50,000 pages (the sitemap protocol's limit per file).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SITE_DIR="${1:-$REPO_ROOT/docs/_site}"
SITE_URL="${2:-https://llm4s.org}"
exec python3 - "$SITE_DIR" "$SITE_URL" <<'PYEOF'
import os
import sys
from urllib.parse import quote
from xml.sax.saxutils import escape

site, base = sys.argv[1], sys.argv[2].rstrip("/")
root = os.path.join(site, "scaladoc")
if not os.path.isdir(root):
    sys.exit(f"error: no Scaladoc under {root}")

urls = []
for dirpath, dirnames, filenames in os.walk(root):
    dirnames.sort()
    for name in sorted(filenames):
        if not name.endswith(".html"):
            continue
        rel = os.path.relpath(os.path.join(dirpath, name), site).replace(os.sep, "/")
        urls.append(f"{base}/{quote(rel)}")

if not urls:
    sys.exit(f"error: no .html pages under {root}")
if len(urls) > 50000:
    sys.exit(f"error: {len(urls)} Scaladoc pages exceed the 50,000 URLs a sitemap file may hold")

out = os.path.join(site, "scaladoc-sitemap.xml")
with open(out, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
    f.write('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n')
    for u in urls:
        f.write(f"  <url><loc>{escape(u)}</loc></url>\n")
    f.write("</urlset>\n")
print(f"wrote {out}: {len(urls)} URLs")
PYEOF
