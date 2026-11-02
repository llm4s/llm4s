#!/usr/bin/env bash
# Builds llms.txt, llms-full.txt and llm4s-rules.md from the docs as pages.yml does, and fails when the build fails, a
# file is missing, llms.txt links a page that does not exist, or a Scala block of a page outside `DocSnippets.pages`
# lacks its "(not compile-checked)" marker (#2287). Also proves the check can fail: it removes a marker and a file
# from a copy and expects each to be reported. Needs python3 only.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN=scripts/generate-llms-txt.py
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

VERSION=$(sed -n 's/^latest_release: "\(.*\)"/\1/p' docs/_data/project.yml)
python3 "$GEN" --version "$VERSION" --out "$OUT"
python3 "$GEN" --check "$OUT"
for f in llm4s-rules.md; do test -s "$OUT/$f" || { echo "error: $f was not built (docs/ai-assistants.md lost its rules markers?)"; exit 1; }; done

# The rules block is what the page says to copy: it must still hold the ten rules.
n=$(grep -cE '^### [0-9]+\. ' "$OUT/llm4s-rules.md" || true)
[ "$n" -eq 10 ] || { echo "error: llm4s-rules.md has $n numbered rules, expected 10"; exit 1; }

# Negative tests: the check must fail on a missing marker and on a missing file.
BAD="$(mktemp -d)"
trap 'rm -rf "$OUT" "$BAD"' EXIT
cp "$OUT"/* "$BAD"/
grep -v '^(not compile-checked)$' "$OUT/llms-full.txt" > "$BAD/llms-full.txt"
if python3 "$GEN" --check "$BAD" 2>/dev/null; then echo "error: the check accepted a llms-full.txt without its markers"; exit 1; fi
rm "$BAD/llms.txt"
if python3 "$GEN" --check "$BAD" 2>/dev/null; then echo "error: the check accepted a missing llms.txt"; exit 1; fi
echo "llms.txt, llms-full.txt and llm4s-rules.md build and check cleanly ($VERSION)."
