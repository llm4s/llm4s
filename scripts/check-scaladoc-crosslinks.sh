#!/usr/bin/env bash
# Checks the cross-links of the generated Scaladoc (#2291), which the `docs` project's Scaladoc options in build.sbt
# ask for and which nothing else would notice going missing:
#   - a "source" link on the pages, into github.com/llm4s/llm4s at <revision> (the release tag, or `main` for a
#     snapshot), and no source link at any other revision;
#   - links out to the JDK's API (docs.oracle.com, Java 21) and the Scala standard library's (scala-lang.org/api/3.x);
#   - a root page linking back to the guides at llm4s.org.
#
#   scripts/check-scaladoc-crosslinks.sh <scaladoc-dir> <revision>
#
# <scaladoc-dir> is the output of `sbt docs/doc` (modules/docs/target/scala-*/api).
set -euo pipefail

API="${1:?usage: check-scaladoc-crosslinks.sh <scaladoc-dir> <revision>}"
REV="${2:?usage: check-scaladoc-crosslinks.sh <scaladoc-dir> <revision>}"
[ -f "$API/index.html" ] || { echo "::error::$API is not a generated Scaladoc (no index.html)"; exit 1; }

errors=0
err() { echo "::error::$*"; errors=$((errors + 1)); }
pages_with() { { grep -rlE --include="*.html" "$1" "$API" || true; } | wc -l | tr -d " "; }

SRC='https://github\.com/llm4s/llm4s/blob/'
rev_re="$(printf '%s' "$REV" | sed 's/[.[\*^$+?(){}|]/\\&/g')"
ok_src="$(pages_with "${SRC}${rev_re}/modules/[^\"]+\.(scala|java)")"
[ "$ok_src" -gt 0 ] || err "no page links its source at $REV (https://github.com/llm4s/llm4s/blob/$REV/modules/...): is -source-links set?"
# A source link Scaladoc writes ends in a line anchor; a link written in a doc comment may name any revision.
other="$(grep -rhoE --include='*.html' "${SRC}[^\"]+\.(scala|java)#L[0-9]+\"" "$API" | grep -oE "${SRC}[^/]+/" | sort -u |
  grep -vx "https://github.com/llm4s/llm4s/blob/${REV}/" || true)"
[ -z "$other" ] || err "source links at a revision other than $REV: $(printf '%s' "$other" | head -3 | tr '\n' ' ')"

jdk="$(pages_with 'href="https://docs\.oracle\.com/en/java/javase/21/docs/api/java\.base/java/')"
[ "$jdk" -gt 0 ] || err "no link to a JDK type (docs.oracle.com/en/java/javase/21): is -external-mappings set?"
scala="$(pages_with 'href="https://scala-lang\.org/api/3\.x/scala/')"
[ "$scala" -gt 0 ] || err "no link to a Scala library type (scala-lang.org/api/3.x): is -external-mappings set?"

grep -qE 'href="https://llm4s\.org/(getting-started|guide)/' "$API/index.html" ||
  err "$API/index.html does not link to the guides at llm4s.org: is -doc-root-content set?"

[ "$errors" = 0 ] || exit 1
echo "Scaladoc cross-links: $ok_src pages link their source at $REV, $jdk link JDK types, $scala link Scala library types; the root page links the guides."
