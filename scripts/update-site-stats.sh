#!/usr/bin/env bash
# Refreshes the GitHub numbers in docs/_data/stats.yml (the home page stats band and the Foundation page) from the
# GitHub API, so llm4s.org shows what https://github.com/llm4s/llm4s shows on the day it is built. pages.yml runs it
# before the Jekyll build, on every deploy and on a daily schedule; the result is never committed.
#
# Needs `gh` with a token (GH_TOKEN). If any number cannot be fetched, the file is left as committed and the build
# goes on: a stale number is better than a missing or wrong one.
#
# Usage: scripts/update-site-stats.sh [repo] [stats-file]
set -euo pipefail

REPO="${1:-llm4s/llm4s}"
FILE="${2:-docs/_data/stats.yml}"

fail() {
  echo "::warning::update-site-stats: $1; keeping the numbers committed in $FILE."
  exit 0
}

repo_json=$(gh api "repos/$REPO") || fail "could not read repos/$REPO"
stars=$(jq -r .stargazers_count <<<"$repo_json")
forks=$(jq -r .forks_count <<<"$repo_json")
# Bots (dependabot[bot], github-actions[bot]) are not contributors.
contributors=$(gh api --paginate "repos/$REPO/contributors?per_page=100" --jq '.[] | select(.type != "Bot") | .login' | wc -l | tr -d " ") ||
  fail "could not list contributors"
merged=$(gh api "search/issues?q=repo:$REPO+is:pr+is:merged&per_page=1" --jq .total_count) || fail "could not count merged pull requests"
releases=$(gh api --paginate "repos/$REPO/releases?per_page=100" --jq '.[] | select(.draft | not) | .tag_name' | wc -l | tr -d " ") ||
  fail "could not list releases"

for n in "$stars" "$forks" "$contributors" "$merged" "$releases"; do
  [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -gt 0 ] || fail "got a non-numeric or zero value ($n)"
done

STARS="$stars" FORKS="$forks" CONTRIBUTORS="$contributors" MERGED="$merged" RELEASES="$releases" \
  AS_OF="$(date -u '+%-d %b %Y')" python3 - "$FILE" <<'EOF'
import os, re, sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
as_of = os.environ["AS_OF"]
values = {
    "GitHub stars": os.environ["STARS"],
    "Forks": os.environ["FORKS"],
    "Contributors": os.environ["CONTRIBUTORS"],
    "Merged pull requests": os.environ["MERGED"],
    "Releases": os.environ["RELEASES"],
}

def sub(pattern, repl):
    global text
    text, n = re.subn(pattern, repl, text, count=1, flags=re.M)
    if n != 1:
        sys.exit(f"update-site-stats: no match for {pattern!r} in {path}")

sub(r'^as_of: ".*"$', f'as_of: "{as_of}"')
sub(r'(^source_note: "GitHub, as of )[^.]*(\.)', rf"\g<1>{as_of}\g<2>")
for label, value in values.items():
    sub(rf'(- value: )"[^"]*"(\n\s+label: {re.escape(label)}$)', rf'\g<1>"{int(value):,}"\g<2>')

open(path, "w", encoding="utf-8").write(text)
EOF

echo "Updated $FILE:"
cat "$FILE"
