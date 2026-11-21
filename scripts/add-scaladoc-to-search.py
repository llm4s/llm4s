#!/usr/bin/env python3
"""Adds the Scaladoc's types to the docs site's search index, so one search finds guides and API pages (#2291).

    scripts/add-scaladoc-to-search.py <site-dir>

Run on the built site (pages.yml, after `jekyll build`), whose scaladoc/ holds the `sbt docs/doc` output. It reads
the Scaladoc's own index, scaladoc/scripts/searchData.js, and appends one entry per class, trait, object, enum and
type alias to assets/js/search-data.json, the index just-the-docs's search box loads: the box, its ranking and its
result list stay just-the-docs's, and an API page appears among the guide pages. A companion object is not listed
apart from its class, which links to it. Members (defs, vals) are left to the Scaladoc's own search.

Running it twice gives the same file: entries under scaladoc/ from an earlier run are replaced.
"""
import json
import re
import sys
from pathlib import Path

KINDS = {"class", "trait", "object", "enum", "type"}


def fail(msg):
    print(f"::error::{msg}", file=sys.stderr)
    sys.exit(1)


def scaladoc_entries(search_js):
    text = search_js.read_text(encoding="utf-8").strip()
    m = re.match(r"^\s*pages\s*=\s*(\[.*\])\s*;?\s*$", text, flags=re.S)
    if not m:
        fail(f"{search_js} is not the Scaladoc search index this script reads (pages = [...])")
    pages = json.loads(m.group(1))
    types = [p for p in pages if p.get("k") in KINDS]
    classes = {(p["d"], p["n"]) for p in types if p["k"] != "object"}
    return [p for p in types if not (p["k"] == "object" and (p["d"], p["n"]) in classes)]


def main(argv):
    if len(argv) != 2:
        fail("usage: add-scaladoc-to-search.py <site-dir>")
    site = Path(argv[1])
    data_file = site / "assets" / "js" / "search-data.json"
    search_js = site / "scaladoc" / "scripts" / "searchData.js"
    if not data_file.is_file():
        fail(f"{data_file} is missing: build the site with search_enabled first")
    if not search_js.is_file():
        fail(f"{search_js} is missing: copy the `sbt docs/doc` output to {site / 'scaladoc'} first")

    data = json.loads(data_file.read_text(encoding="utf-8"))
    guides = [e for e in data.values() if not e["relUrl"].startswith("/scaladoc/")]
    if not guides:
        fail(f"{data_file} has no guide pages")
    # The site's base URL, as just-the-docs wrote it: url is the base URL followed by relUrl.
    first = guides[0]
    base = first["url"][: len(first["url"]) - len(first["relUrl"])] if first["url"].endswith(first["relUrl"]) else ""

    added = []
    for p in scaladoc_entries(search_js):
        rel = "/scaladoc/" + p["l"].split("#")[0] + ("#" + p["l"].split("#", 1)[1] if p["k"] == "type" else "")
        fqn = f"{p['d']}.{p['n']}" if p["d"] else p["n"]
        kind = "type alias" if p["k"] == "type" else p["k"]
        added.append({
            "doc": p["n"],
            "title": p["n"],
            "content": f"{fqn}: {kind} in {p['d']}. API reference (Scaladoc). ",
            "url": base + rel,
            "relUrl": rel,
        })
    if not added:
        fail(f"{search_js} lists no types")

    merged = {str(i): e for i, e in enumerate(guides + added)}
    data_file.write_text(json.dumps(merged, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Search index: {len(guides)} guide entries and {len(added)} Scaladoc types in {data_file}")


if __name__ == "__main__":
    main(sys.argv)
