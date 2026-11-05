#!/usr/bin/env bash
# Checks what a link preview (Discord, Slack, LinkedIn, X, Mastodon) and a search engine read from each page of the
# built llm4s.org site (#2316): its share card and its structured data.
#
# Usage: scripts/check-site-share-cards.sh SITE_DIR [PROJECT_YML]
#   SITE_DIR     the built site (docs/_site). pages.yml runs this after the Jekyll build, before the deploy.
#   PROJECT_YML  the data file the site was built with (default: docs/_data/project.yml under this script's repo);
#                pages.yml writes the latest published release into it before the build.
#
# Pages checked: every page Jekyll rendered (it carries `<meta name="generator" content="Jekyll ...">`) except the
# redirect stubs of jekyll-redirect-from. The Scaladoc, copied in as-is, has no generator tag.
#
# Rules, on every page:
#   - og:image, og:image:alt and twitter:image are present; og:image is an absolute https URL whose file is in the
#     site, and twitter:image is the same URL.
#   - twitter:card is summary_large_image (jekyll-seo-tag sends `summary`, a card with no picture, when a page has no
#     image).
#   - twitter:creator, if present, is a handle: @ and 1 to 15 letters, digits or underscores. Not `@LLM4S Team`.
#   - every <script type="application/ld+json"> is valid JSON, and each object in it has an @type.
# And on two pages:
#   - the home page (index.html) describes the project as a SoftwareSourceCode whose version is PROJECT_YML's
#     latest_release, and carries no other description of itself (jekyll-seo-tag's WebSite block is removed);
#   - /foundation/ describes the LLM4S Foundation as an Organization.
#
# Exit code 0 = every page passes. Non-zero = each failing page with its reason.
set -euo pipefail

SITE="${1:?usage: check-site-share-cards.sh SITE_DIR [PROJECT_YML]}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${2:-$REPO_ROOT/docs/_data/project.yml}"
[ -d "$SITE" ] || { echo "no such site directory: $SITE" >&2; exit 2; }
[ -f "$PROJECT" ] || { echo "no such project file: $PROJECT" >&2; exit 2; }

SITE="$SITE" PROJECT="$PROJECT" python3 - <<'PYEOF'
import html, json, os, pathlib, re, sys
from urllib.parse import urlparse

SITE, PROJECT = pathlib.Path(os.environ["SITE"]), pathlib.Path(os.environ["PROJECT"])
HANDLE = re.compile(r"@[A-Za-z0-9_]{1,15}")

m = re.search(r'^latest_release:\s*"?([^"\s]+)"?\s*$', PROJECT.read_text(encoding="utf-8"), re.M)
if not m:
    print(f"{PROJECT}: no latest_release", file=sys.stderr)
    sys.exit(2)
LATEST = m.group(1)

def metas(head):
    """Every <meta> in the head as {name-or-property: [content, ...]}."""
    out = {}
    for tag in re.findall(r"<meta\s[^>]*>", head, re.I):
        key = re.search(r'\b(?:name|property)="([^"]*)"', tag)
        content = re.search(r'\bcontent="([^"]*)"', tag)
        if key and content:
            out.setdefault(key.group(1), []).append(html.unescape(content.group(1)))
    return out

def typed_objects(value):
    """The top-level JSON-LD objects: a single object, a list of them, or an @graph's items."""
    items = value if isinstance(value, list) else [value]
    for it in items:
        if isinstance(it, dict) and "@graph" in it and "@type" not in it:
            yield from typed_objects(it["@graph"])
        else:
            yield it

def local_file(url):
    """The file a same-site URL is served from, or None for another host."""
    u = urlparse(url)
    path = u.path.lstrip("/")
    return SITE / path if path else None

problems, checked = [], 0
for page in sorted(SITE.rglob("*.html")):
    rel = page.relative_to(SITE).as_posix()
    text = page.read_text(encoding="utf-8", errors="replace")
    head = text[: text.find("</head>")] if "</head>" in text else text
    if not re.search(r'<meta\s+name="generator"\s+content="Jekyll', head):
        continue
    if re.search(r'http-equiv="refresh"', head, re.I):     # a redirect_from stub
        continue
    checked += 1
    def bad(reason):
        problems.append(f"{rel}: {reason}")
    mt = metas(head)
    one = lambda k: (mt.get(k) or [None])[0]

    og_image, og_alt, tw_image, tw_card = one("og:image"), one("og:image:alt"), one("twitter:image"), one("twitter:card")
    if not og_image:
        bad("has no og:image (set `image` in its front matter or rely on the default in _config.yml)")
    else:
        if not og_image.startswith("https://"):
            bad(f"og:image is not an absolute https URL: {og_image}")
        f = local_file(og_image)
        if f is None or not f.is_file():
            bad(f"og:image {og_image} is not a file of the site")
        if tw_image != og_image:
            bad(f"twitter:image is {tw_image!r}, not og:image {og_image!r}")
    if not (og_alt or "").strip():
        bad("has no og:image:alt (set `image.alt`)")
    if tw_card != "summary_large_image":
        bad(f"twitter:card is {tw_card!r}, not 'summary_large_image'")
    for creator in mt.get("twitter:creator", []):
        if not HANDLE.fullmatch(creator):
            bad(f"twitter:creator {creator!r} is not a handle (@ and 1-15 letters, digits or _)")

    types = []
    for raw in re.findall(r'<script\s+type="application/ld\+json"[^>]*>(.*?)</script>', text, re.S):
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as e:
            bad(f"JSON-LD is not valid JSON: {e}")
            continue
        for obj in typed_objects(data):
            if not isinstance(obj, dict) or not obj.get("@type"):
                bad("a JSON-LD object has no @type")
            else:
                types.append(obj)

    if rel == "index.html":
        code = [o for o in types if o["@type"] == "SoftwareSourceCode"]
        others = [o["@type"] for o in types if o["@type"] not in ("SoftwareSourceCode", "BreadcrumbList")]
        if len(code) != 1:
            bad(f"has {len(code)} SoftwareSourceCode block(s), expected 1")
        elif code[0].get("version") != LATEST:
            bad(f"SoftwareSourceCode.version is {code[0].get('version')!r}, latest_release is {LATEST!r}")
        if others:
            bad(f"also describes itself as {', '.join(others)}: one description per page")
    if rel == "foundation/index.html":
        if not any(o["@type"] == "Organization" and o.get("name") == "LLM4S Foundation" for o in types):
            bad("has no Organization block for the LLM4S Foundation")

for required in ("index.html", "foundation/index.html"):
    if not (SITE / required).is_file():
        problems.append(f"{required}: missing from the site")

if checked == 0:
    print("no Jekyll pages found: wrong directory?", file=sys.stderr)
    sys.exit(2)
for p in problems:
    print(p)
if problems:
    print(f"\n{len(problems)} problem(s); see scripts/check-site-share-cards.sh.", file=sys.stderr)
    sys.exit(1)
print(f"ok: {checked} pages checked, each with a large share card and valid structured data")
PYEOF
