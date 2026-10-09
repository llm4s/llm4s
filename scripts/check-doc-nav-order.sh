#!/usr/bin/env bash
# Fails when two docs pages under the same parent declare the same `nav_order`.
#
# The docs site (just-the-docs) orders a parent's children by their `nav_order` front-matter key. Two
# children with the same value sort by title instead, so the order a page was given stops meaning
# anything, and the page that "wins" changes when a title does. `docs/guide/permission-based-rag.md`
# and `docs/guide/rag-evaluation.md` both declared `nav_order: 4` under `User Guide` for months without
# anyone noticing, because the site renders either way.
#
# A group is the pair (grand_parent, parent) from the front matter; pages without a parent are the
# top-level group. A page without `nav_order` is not checked (the site puts it last).
# Exit code 0 = every group's nav_order values are unique. Non-zero = each duplicated value, with the
# group and the pages that share it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PYEOF'
import re, sys, pathlib
from collections import defaultdict

SKIP_DIRS = ("docs/_site/", "docs/vendor/")
KEY = re.compile(r'^(title|parent|grand_parent|nav_order)\s*:\s*(.*?)\s*$')

def front_matter(text):
    if not text.startswith("---"):
        return None
    end = text.find("\n---", 3)
    if end < 0:
        return None
    out = {}
    for line in text[3:end].splitlines():
        m = KEY.match(line)
        if m:
            out[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return out

groups = defaultdict(lambda: defaultdict(list))   # (grand_parent, parent) -> nav_order -> [pages]
for p in sorted(pathlib.Path("docs").rglob("*.md")):
    rel = p.as_posix()
    if rel.startswith(SKIP_DIRS):
        continue
    fm = front_matter(p.read_text(encoding="utf-8"))
    if not fm or "nav_order" not in fm:
        continue
    group = (fm.get("grand_parent", ""), fm.get("parent", ""))
    groups[group][fm["nav_order"]].append(rel)

bad = 0
for group in sorted(groups):
    for order, pages in sorted(groups[group].items(), key=lambda kv: (len(kv[0]), kv[0])):
        if len(pages) > 1:
            bad += 1
            gp, parent = group
            where = (f"{gp} > {parent}" if gp else parent) or "(top level)"
            print(f"nav_order {order} under '{where}' is shared by {len(pages)} pages:")
            for page in pages:
                print(f"  {page}")

if bad:
    print(f"\n{bad} duplicated nav_order value(s). Give each page under a parent its own nav_order.", file=sys.stderr)
    sys.exit(1)
print("ok: every nav_order is unique within its parent")
PYEOF
