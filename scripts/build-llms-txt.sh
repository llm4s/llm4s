#!/usr/bin/env bash
# Builds the files of #2287 into docs/ for the Jekyll build (pages.yml): llms.txt, llms-full.txt and llm4s-rules.md for
# the release being deployed, a copy of each under docs/llms/<major.minor>/, and the same for the newest release of
# the minor line before it, built from that release's tag so a project pinned to it has a stable path.
#
#   scripts/build-llms-txt.sh <release version>      e.g. 0.5.1
#
# The previous minor is best effort: a tag whose docs this generator cannot read (an older layout) is reported as a
# warning and skipped. The files of the release being deployed are not: any failure there fails the build.
set -euo pipefail

VERSION="${1:?usage: build-llms-txt.sh <release version>}"
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN=scripts/generate-llms-txt.py
MINOR="${VERSION%.*}"

python3 "$GEN" --version "$VERSION" --out docs
python3 "$GEN" --version "$VERSION" --path-prefix "/llms/$MINOR" --out "docs/llms/$MINOR"
python3 "$GEN" --check docs
python3 "$GEN" --check "docs/llms/$MINOR"

# The newest release tag of the highest earlier minor line.
PREV_TAG=$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's/^v//' \
  | awk -v cur="$MINOR" -F. 'function key(a,b){return a*1000+b} BEGIN{split(cur,c,".")} key($1,$2) < key(c[1],c[2])' \
  | sort -V | tail -1)
if [ -z "$PREV_TAG" ]; then
  echo "No release of an earlier minor line than $MINOR: no previous-minor files."
  exit 0
fi
PREV_MINOR="${PREV_TAG%.*}"
SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
# A release from before the compiled snippets has no DocSnippets.scala: all its Scala blocks are then marked unchecked.
git archive "v$PREV_TAG" project/DocSnippets.scala 2>/dev/null | tar -x -C "$SRC" 2>/dev/null || true
if git archive "v$PREV_TAG" docs | tar -x -C "$SRC" \
   && python3 "$GEN" --docs "$SRC/docs" --snippets "$SRC/project/DocSnippets.scala" --lenient --version "$PREV_TAG" \
        --path-prefix "/llms/$PREV_MINOR" --out "docs/llms/$PREV_MINOR" \
   && python3 "$GEN" --check "docs/llms/$PREV_MINOR" --docs "$SRC/docs" --snippets "$SRC/project/DocSnippets.scala"; then
  echo "Built the $PREV_MINOR files from v$PREV_TAG."
else
  rm -rf "docs/llms/$PREV_MINOR"
  echo "::warning::Could not build the llms.txt files of v$PREV_TAG ($PREV_MINOR); the deploy goes on without them."
fi
