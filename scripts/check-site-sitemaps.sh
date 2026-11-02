#!/usr/bin/env bash
# Checks the built site's sitemaps (#2318): sitemap.xml lists guide pages only (no /scaladoc/ URL, no .pdf, and no more
# than MAX_URLS entries, so a new generated tree cannot flood it unnoticed), scaladoc-sitemap.xml exists and lists only
# /scaladoc/ pages, both are well-formed sitemap files, and robots.txt names both.
#
# Usage: scripts/check-site-sitemaps.sh [SITE_DIR] [SITE_URL]     (defaults: docs/_site, https://llm4s.org)
# MAX_URLS (default 600) is the ceiling for sitemap.xml; it held about 140 URLs when it was set, raise it deliberately.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SITE_DIR="${1:-$REPO_ROOT/docs/_site}"
SITE_URL="${2:-https://llm4s.org}"
export MAX_URLS="${MAX_URLS:-600}"
exec python3 - "$SITE_DIR" "$SITE_URL" <<'PYEOF'
import os
import sys
import xml.etree.ElementTree as ET

site, base = sys.argv[1], sys.argv[2].rstrip("/")
max_urls = int(os.environ["MAX_URLS"])
ns = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
errors = []


def locs(name):
    path = os.path.join(site, name)
    if not os.path.isfile(path):
        errors.append(f"{name} is missing")
        return []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        errors.append(f"{name} is not well-formed XML: {e}")
        return []
    if root.tag != ns + "urlset":
        errors.append(f"{name} root is {root.tag}, expected a sitemap <urlset>")
        return []
    found = []
    for url in root:
        loc = url.find(ns + "loc")
        if url.tag != ns + "url" or loc is None or not (loc.text or "").strip():
            errors.append(f"{name} has an entry without a <loc>")
        else:
            found.append(loc.text.strip())
    return found


guide = locs("sitemap.xml")
if len(guide) > max_urls:
    errors.append(f"sitemap.xml lists {len(guide)} URLs, more than the {max_urls} allowed (MAX_URLS)")
for u in guide:
    if "/scaladoc/" in u:
        errors.append(f"sitemap.xml lists a Scaladoc URL: {u}")
    if u.lower().endswith(".pdf"):
        errors.append(f"sitemap.xml lists a PDF: {u}")
    if not u.startswith(base + "/"):
        errors.append(f"sitemap.xml lists a URL outside {base}: {u}")
if not guide and not errors:
    errors.append("sitemap.xml lists no URLs")

api = locs("scaladoc-sitemap.xml")
if len(api) > 50000:
    errors.append(f"scaladoc-sitemap.xml lists {len(api)} URLs, over the 50,000 limit")
for u in api:
    if not u.startswith(base + "/scaladoc/"):
        errors.append(f"scaladoc-sitemap.xml lists a non-Scaladoc URL: {u}")
if not api and not any(e.startswith("scaladoc-sitemap.xml") for e in errors):
    errors.append("scaladoc-sitemap.xml lists no URLs")

robots = os.path.join(site, "robots.txt")
text = open(robots, encoding="utf-8").read() if os.path.isfile(robots) else ""
for name in ("sitemap.xml", "scaladoc-sitemap.xml"):
    if f"Sitemap: {base}/{name}" not in text.splitlines():
        errors.append(f"robots.txt does not list Sitemap: {base}/{name}")

if errors:
    for e in errors:
        print(f"::error::{e}")
    sys.exit(1)
print(f"ok: sitemap.xml {len(guide)} URLs, scaladoc-sitemap.xml {len(api)} URLs, robots.txt lists both")
PYEOF
