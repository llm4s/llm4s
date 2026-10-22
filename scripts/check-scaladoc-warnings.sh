#!/usr/bin/env bash
# Fails when a Scaladoc run warned about an llm4s source file, or about a third-party one that is not on the
# allow-list (#1967).
#
# Usage: scripts/check-scaladoc-warnings.sh <sbt-doc-log> [allow-list]
#   e.g. sbt doc docs/doc 2>&1 | tee scaladoc.log; scripts/check-scaladoc-warnings.sh scaladoc.log
#   The allow-list defaults to scripts/scaladoc-third-party-warnings.txt.
#
# Scala 3's Scaladoc ignores `-Werror` and `-Wconf` (it logs "Skipping unused scalacOptions: -Werror,
# -Wconf"), so neither can make a broken `[[link]]` fail `doc`, and a broken link is only a `[warn]`
# line that nobody reads. This reads the log instead. A warning is attributed to the file named in its
# `-- Warning: <path>:<line>:<col>` header:
#  - a path under `modules/`, the root of every llm4s source, is ours, and fails the check;
#  - any other path is a third-party source Scaladoc walked for a doc comment we inherit (ScalaTest's
#    `Assertions`, cats-effect's `IOApp`). We cannot fix those, and Scaladoc offers no way to silence them, so
#    each known one is listed, with the reason, in the allow-list. One that is not listed fails the check:
#    it means a new class inherits a third-party doc comment Scaladoc cannot resolve, which is noise to remove
#    (give the member a doc comment of its own) or, failing that, to list.
#  - `Flag -classpath set repeatedly` has no path. Scaladoc 3.7 prints it once per run whatever the build
#    passes - it puts sbt's `-classpath` into its context and then hands it to its TASTy inspector again - so
#    it is ignored.
# Scaladoc reports only the first unresolvable link of a comment, so fixing one can reveal the next.
#
# Allow-list lines are `<path> | <link> | <reason>`. The path is the one Scaladoc prints, from the package
# root on (`org/scalatest/Assertions.scala`, not `dotty/core/src/main/scala/org/...`), and no line number, so
# an upgrade that moves the comment or rebuilds the library under another Scala version still matches.
# Blank lines and lines starting with `#` are ignored.
#
# Exit code 0 = no unexpected warning. 1 = each one, with the link it could not resolve. 2 = usage error.
set -euo pipefail

ALLOW_DEFAULT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scaladoc-third-party-warnings.txt"
if [[ $# -lt 1 || $# -gt 2 || ! -f "$1" || ! -f "${2:-$ALLOW_DEFAULT}" ]]; then
  echo "usage: $0 <sbt-doc-log> [allow-list]" >&2
  exit 2
fi

python3 - "$1" "${2:-$ALLOW_DEFAULT}" <<'PYEOF'
import re, sys

ANSI = re.compile(r'\x1b\[[0-9;]*m')
HEADER = re.compile(r'^\[warn\] -- Warning: (\S+?):(\d+):(\d+)')
LINK = re.compile(r"Couldn't resolve a member for the given link query: (.*)$")
# A library's own source root, up to its package root: `.../src/main/scala/` or `.../src_managed/main/`.
SOURCE_ROOT = re.compile(r'^.*?(?:src/main/scala|src_managed/main)/')

def package_path(path):
    return SOURCE_ROOT.sub("", path)

allowed, problems = {}, []
for n, raw in enumerate(open(sys.argv[2], encoding="utf-8"), 1):
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    parts = [p.strip() for p in line.split(" | ", 2)]
    if len(parts) != 3 or not all(parts):
        problems.append(f"{sys.argv[2]}:{n}: expected `<path> | <link> | <reason>`")
        continue
    allowed[(parts[0], parts[1])] = parts[2]
if problems:
    print("\n".join(problems))
    sys.exit(2)

ours, theirs, current = [], [], None
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = ANSI.sub("", raw.rstrip("\n"))
    h = HEADER.match(line)
    if h:
        current = {"where": f"{h.group(1)}:{h.group(2)}", "path": h.group(1), "link": None}
        (ours if h.group(1).startswith("modules/") else theirs).append(current)
        continue
    if current is not None and current["link"] is None:
        m = LINK.search(line)
        if m:
            current["link"] = m.group(1).strip()

def describe(w):
    return "unresolved link [[" + w["link"] + "]]" if w["link"] is not None else "see the log"

def uniq(ws, key):
    seen, out = set(), []
    for w in ws:
        if key(w) not in seen:
            seen.add(key(w))
            out.append(w)
    return out

ours = uniq(ours, lambda w: (w["where"], w["link"]))
theirs = uniq(theirs, lambda w: (w["where"], w["link"]))
known = [w for w in theirs if (package_path(w["path"]), w["link"]) in allowed]
unknown = [w for w in theirs if (package_path(w["path"]), w["link"]) not in allowed]
seen = {(package_path(w["path"]), w["link"]) for w in known}
unused = [k for k in allowed if k not in seen]

if known:
    print(f"{len(known)} Scaladoc warning(s) in third-party sources, allow-listed in {sys.argv[2]}.")
if unused:
    print(f"{len(unused)} allow-list entr(y/ies) not seen in this log (fine for a partial run; remove once gone for good):")
    for path, link in unused:
        print(f"  {path} | {link}")
failed = False
if ours:
    failed = True
    print(f"{len(ours)} Scaladoc warning(s) in llm4s sources:")
    for w in ours:
        print(f"  {w['where']}: {describe(w)}")
    print("Qualify the link (Scaladoc does not resolve names through imports), use `X$.member` / `X!.member`")
    print("for a companion's or a class's member, or write it as `code` when the target is in a module")
    print("this one does not depend on.")
if unknown:
    failed = True
    print(f"{len(unknown)} Scaladoc warning(s) in third-party sources that are not on the allow-list:")
    for w in unknown:
        print(f"  {w['where']}: {describe(w)}  [allow-list key: {package_path(w['path'])} | {w['link'] or '?'}]")
    print("A class of ours now inherits a third-party doc comment Scaladoc cannot resolve. Give the member a doc")
    print(f"comment of its own, or, when that is not possible, add the key with the reason to {sys.argv[2]}.")
if failed:
    sys.exit(1)
print("No unexpected Scaladoc warnings.")
PYEOF
