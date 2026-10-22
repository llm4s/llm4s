#!/usr/bin/env bash
# Fails when a Scaladoc run warned about an llm4s source file (#1967).
#
# Usage: scripts/check-scaladoc-warnings.sh <sbt-doc-log>
#   e.g. sbt doc docs/doc 2>&1 | tee scaladoc.log; scripts/check-scaladoc-warnings.sh scaladoc.log
#
# Scala 3's Scaladoc ignores `-Werror` and `-Wconf` (it logs "Skipping unused scalacOptions: -Werror,
# -Wconf"), so neither can make a broken `[[link]]` fail `doc`, and a broken link is only a `[warn]`
# line that nobody reads. This reads the log instead. A warning is attributed to the file named in its
# `-- Warning: <path>:<line>:<col>` header; it fails the check when that path is one of ours - under
# `modules/`, the root of every llm4s source. Two kinds of warning are reported but do not fail it:
#  - warnings in third-party sources Scaladoc walks for a doc comment we inherit (ScalaTest's
#    `Assertions`, cats-effect's `IOApp`): their paths are the library's own (`dotty/core/...`), and we
#    cannot fix them;
#  - `Flag -classpath set repeatedly`, which Scaladoc 3.7 prints once per run whatever the build
#    passes: it puts sbt's `-classpath` into its context and then hands it to its TASTy inspector again.
# Scaladoc reports only the first unresolvable link of a comment, so fixing one can reveal the next.
#
# Exit code 0 = no warning in an llm4s source. Non-zero = each one, with the link it could not resolve.
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "usage: $0 <sbt-doc-log>" >&2
  exit 2
fi

python3 - "$1" <<'PYEOF'
import re, sys

ANSI = re.compile(r'\x1b\[[0-9;]*m')
HEADER = re.compile(r'^\[warn\] -- Warning: (\S+?):(\d+):(\d+)')
LINK = re.compile(r"Couldn't resolve a member for the given link query: (.*)$")

ours, theirs, current = [], [], None
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = ANSI.sub("", raw.rstrip("\n"))
    h = HEADER.match(line)
    if h:
        current = {"where": f"{h.group(1)}:{h.group(2)}", "path": h.group(1), "what": ""}
        (ours if h.group(1).startswith("modules/") else theirs).append(current)
        continue
    if current is not None and not current["what"]:
        m = LINK.search(line)
        if m:
            current["what"] = "unresolved link [[" + m.group(1).strip() + "]]"

def uniq(ws):
    seen, out = set(), []
    for w in ws:
        key = (w["where"], w["what"])
        if key not in seen:
            seen.add(key)
            out.append(w)
    return out

ours, theirs = uniq(ours), uniq(theirs)
if theirs:
    print(f"{len(theirs)} Scaladoc warning(s) in third-party sources, not checked (inherited doc comments).")
if ours:
    print(f"{len(ours)} Scaladoc warning(s) in llm4s sources:")
    for w in ours:
        print(f"  {w['where']}: {w['what'] or 'see the log'}")
    print("Qualify the link (Scaladoc does not resolve names through imports), use `X$.member` / `X!.member`")
    print("for a companion's or a class's member, or write it as `code` when the target is in a module")
    print("this one does not depend on.")
    sys.exit(1)
print("No Scaladoc warnings in llm4s sources.")
PYEOF
