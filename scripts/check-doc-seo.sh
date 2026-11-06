#!/usr/bin/env bash
# Checks what a search engine or an AI assistant reads from each documentation page: its <title> and its
# <meta name="description"> (#2314). jekyll-seo-tag falls back to the site-wide description when a page sets none, so
# a page without its own description looks like every other page in a search result.
#
# Pages checked: docs/index.md and every page under docs/{getting-started,guide,examples,reference,migrations,scala-ai,
# advanced,api,community}. Scaladoc, design records and the other top-level pages are out of scope.
#
# Two modes, one rule set:
#   scripts/check-doc-seo.sh                   the source: every page's front matter. No Jekyll needed, so it runs
#                                              in CI's Quick Checks.
#   scripts/check-doc-seo.sh --site docs/_site the built site: the <title>, og:title and description the pages are
#                                              really served with. pages.yml runs it after the Jekyll build, before
#                                              the deploy.
#
# Rules:
#   - description: present, 50 to 170 characters, no Liquid, not the site-wide description, and unique across pages.
#   - search_title (optional front matter, rendered into <title>, og:title and twitter:title by
#     docs/_plugins/search_title.rb; `title` stays the navigation label): at most 60 characters, unique.
#   - built site: the <title> is at most 70 characters, unique, and not just a deny-listed nav label (Home, Index,
#     Overview); og:title is present and not a deny-listed label either.
#   - source: a page has a `title`.
#
# scripts/doc-seo-baseline.txt lists pages that are exempt from every rule above, one key per line: `src:docs/<file>.md`
# in the source mode, `site:<path>.html` in the site mode. The baseline can only shrink: the check fails on a listed
# page that passes (or no longer exists), so delete its line. It is empty: add no line to hide a new page.
#
# Environment (for the tests): DOC_SEO_ROOT is the repository root (default: this script's), DOC_SEO_BASELINE the
# baseline file (default: scripts/doc-seo-baseline.txt under the root).
# Exit code 0 = every page passes. Non-zero = each failing page with its reason.
set -euo pipefail

REPO_ROOT="${DOC_SEO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASELINE="${DOC_SEO_BASELINE:-$REPO_ROOT/scripts/doc-seo-baseline.txt}"
MODE=source
SITE=""
if [ "${1:-}" = "--site" ]; then
  MODE=site
  SITE="${2:?usage: check-doc-seo.sh [--site DIR]}"
elif [ $# -gt 0 ]; then
  echo "usage: check-doc-seo.sh [--site DIR]" >&2
  exit 2
fi
cd "$REPO_ROOT"

MODE="$MODE" SITE="$SITE" BASELINE="$BASELINE" python3 - <<'PYEOF'
import html, os, pathlib, re, sys
from collections import defaultdict

MODE, SITE, BASELINE = os.environ["MODE"], os.environ["SITE"], os.environ["BASELINE"]
SECTIONS = ("getting-started", "guide", "examples", "reference", "migrations", "scala-ai", "advanced", "api", "community")
DENY = {"home", "index", "overview"}
DESC_MIN, DESC_MAX = 50, 170
SEARCH_TITLE_MAX, TITLE_MAX = 60, 70
SITE_NAME = "LLM4S"

def norm(s):
    return re.sub(r"\s+", " ", s).strip().casefold()

def site_description():
    cfg = pathlib.Path("docs/_config.yml")
    if not cfg.exists():
        return ""
    m = re.search(r"^description:[ \t]*(?:>-?|\|-?)?[ \t]*\n?((?:[ \t]+.*\n?)+|.*)", cfg.read_text(encoding="utf-8"), re.M)
    return norm(m.group(1)) if m else ""

def front_matter(text):
    """Top-level scalar keys of the front matter; a folded or literal block is joined with spaces."""
    if not text.startswith("---"):
        return None
    end = text.find("\n---", 3)
    if end < 0:
        return None
    out, key = {}, None
    for line in text[3:end].splitlines():
        m = re.match(r"^([A-Za-z_][\w-]*)\s*:\s*(.*?)\s*$", line)
        if m:
            key, val = m.group(1), m.group(2)
            if val in (">", ">-", "|", "|-"):
                out[key] = ""
            else:
                if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
                    val = val[1:-1]
                out[key] = val
        elif key and line.startswith((" ", "\t")) and out.get(key) is not None and line.strip():
            out[key] = (out[key] + " " + line.strip()).strip()
    return out

def collect_source():
    pages = {}
    files = [pathlib.Path("docs/index.md")]
    for sec in SECTIONS:
        files += sorted(pathlib.Path("docs", sec).rglob("*.md"))
    for p in files:
        if not p.exists():
            continue
        fm = front_matter(p.read_text(encoding="utf-8"))
        if fm is None:      # no front matter: Jekyll copies the file, it is not a page
            continue
        pages["src:" + p.as_posix()] = {
            "title": fm.get("title", ""), "description": fm.get("description"),
            "search_title": fm.get("search_title"),
        }
    return pages

def collect_site():
    root = pathlib.Path(SITE)
    pages = {}
    files = [root / "index.html"]
    for sec in SECTIONS:
        files += sorted((root / sec).rglob("*.html"))
    for p in files:
        if not p.exists():
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        head = text[: text.find("</head>")] if "</head>" in text else text
        if re.search(r'http-equiv="refresh"', head, re.I):   # a redirect_from stub
            continue
        def meta(attr, name):
            m = re.search(r'<meta\s+(?:[^>]*?\s)?%s="%s"\s+content="([^"]*)"' % (attr, re.escape(name)), head)
            return html.unescape(m.group(1)) if m else None
        t = re.search(r"<title>([^<]*)</title>", head)
        pages["site:" + p.relative_to(root).as_posix()] = {
            "rendered_title": html.unescape(t.group(1)).strip() if t else None,
            "og_title": meta("property", "og:title"),
            "description": meta("name", "description"),
        }
    return pages

def label(title):
    """The nav label part of a rendered title: `Home | LLM4S` -> `Home`."""
    t = title or ""
    suffix = " | " + SITE_NAME
    return (t[: -len(suffix)] if t.endswith(suffix) else t).strip()

def check(pages, site_desc):
    problems = defaultdict(list)
    descs, titles = defaultdict(list), defaultdict(list)
    for key, pg in pages.items():
        d = pg.get("description")
        if d is None or not d.strip():
            problems[key].append("has no description (the site-wide one is served)")
        else:
            n = len(d.strip())
            if "{{" in d or "{%" in d:
                problems[key].append("description contains Liquid, whose length cannot be checked")
            if n < DESC_MIN or n > DESC_MAX:
                problems[key].append(f"description is {n} characters, must be {DESC_MIN} to {DESC_MAX}")
            if site_desc and norm(d) == site_desc:
                problems[key].append("description is the site-wide description")
            descs[norm(d)].append(key)
        if MODE == "source":
            if not pg["title"]:
                problems[key].append("has no title")
            st = pg.get("search_title")
            if st is not None:
                if not st.strip():
                    problems[key].append("search_title is empty")
                elif len(st) > SEARCH_TITLE_MAX:
                    problems[key].append(f"search_title is {len(st)} characters, at most {SEARCH_TITLE_MAX}")
                else:
                    titles[norm(st)].append(key)
        else:
            rt = pg["rendered_title"]
            if not rt:
                problems[key].append("has no <title>")
            else:
                if len(rt) > TITLE_MAX:
                    problems[key].append(f"<title> is {len(rt)} characters, at most {TITLE_MAX}")
                if norm(label(rt)) in DENY:
                    problems[key].append(f"<title> is only the nav label '{label(rt)}'")
                titles[norm(rt)].append(key)
            og = pg["og_title"]
            if not og:
                problems[key].append("has no og:title")
            elif norm(og) in DENY:
                problems[key].append(f"og:title is only the nav label '{og}'")
    for what, groups in (("description", descs), ("search title" if MODE == "source" else "<title>", titles)):
        for text, keys in groups.items():
            if len(keys) > 1:
                for k in keys:
                    rest = [o for o in keys if o != k]
                    others = ", ".join(rest[:3]) + (f" and {len(rest) - 3} more" if len(rest) > 3 else "")
                    problems[k].append(f"{what} is shared with {others}")
    return problems

pages = collect_source() if MODE == "source" else collect_site()
if not pages:
    print("no pages found: wrong directory?", file=sys.stderr)
    sys.exit(2)

baseline = set()
bp = pathlib.Path(BASELINE)
if bp.exists():
    for line in bp.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and line.startswith(("src:", "site:")):
            baseline.add(line)
baseline = {b for b in baseline if b.startswith("src:" if MODE == "source" else "site:")}

checked = {k: v for k, v in pages.items() if k not in baseline}
problems = check(checked, site_description())

bad = 0
for key in sorted(problems):
    for reason in problems[key]:
        bad += 1
        print(f"{key}: {reason}")
for key in sorted(baseline):
    # a baselined page is exempt; it must still fail, or the baseline entry is stale
    single = check({key: pages[key]}, site_description()) if key in pages else None
    if single is None or not single.get(key):
        bad += 1
        print(f"{key}: listed in scripts/doc-seo-baseline.txt but {'does not exist' if single is None else 'passes'}: delete its line")

if bad:
    print(f"\n{bad} problem(s). Give each page its own `description` of {DESC_MIN} to {DESC_MAX} characters "
          f"(and, if its nav label is short, a `search_title`); see scripts/check-doc-seo.sh.", file=sys.stderr)
    sys.exit(1)
print(f"ok: {len(pages)} pages checked ({MODE}), each with its own title and description")
PYEOF
